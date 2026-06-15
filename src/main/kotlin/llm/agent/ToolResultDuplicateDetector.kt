package org.iotsplab.akiba.llm.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import java.security.MessageDigest

/**
 * Pluggable policy for detecting whether a freshly executed tool result is
 * repeating evidence already seen earlier in the same agent session.
 *
 * Implementations must be advisory only: they should not block tool execution,
 * because tools may be non-idempotent or intentionally re-run for validation.
 */
interface ToolResultDuplicateDetector {
    fun inspect(request: ToolResultInspectionRequest): ToolResultDuplicateDetection
}

data class ToolResultInspectionRequest(
    val sessionId: String?,
    val iteration: Int,
    val toolCall: ParsedToolCall,
    val resultUuid: String,
    val stored: ToolResultContext.StoredResult
)

data class ToolResultDuplicateDetection(
    val matches: List<ToolResultDuplicateMatch> = emptyList(),
    val totalAppearancesIncludingCurrent: Int = 1,
    val severity: ToolResultDuplicateSeverity = ToolResultDuplicateSeverity.NONE,
    val skippedReason: String? = null
) {
    val isDuplicate: Boolean get() = matches.isNotEmpty()
}

data class ToolResultDuplicateMatch(
    val resultUuid: String,
    val callId: Long?,
    val iteration: Int?,
    val toolName: String,
    val toolArgs: String?,
    val resultSha256: String?,
    val originalBytes: Int?,
    val createdAt: String?,
    val source: String
)

enum class ToolResultDuplicateSeverity {
    NONE,
    NOTICE,
    WARNING
}

/**
 * Default exact-hash duplicate detector.
 *
 * The first version intentionally avoids blocking repeated calls. It compares
 * exact SHA-256 result hashes across the whole session, exempts very short
 * outputs to reduce false positives, and emits a stronger warning once the
 * same output has appeared several times.
 */
class DefaultToolResultDuplicateDetector(
    private val sessionId: String? = null,
    private val agentDbClient: AgentDatabaseClient? = null,
    private val minComparableBytes: Int = 80,
    private val warningThreshold: Int = 3,
    private val maxDbMatches: Int = 20,
    private val logger: Logger? = null
) : ToolResultDuplicateDetector {

    private val mapper = jacksonObjectMapper()
    private val inMemoryHistory = mutableListOf<ToolResultDuplicateMatch>()

    override fun inspect(request: ToolResultInspectionRequest): ToolResultDuplicateDetection {
        val hash = request.stored.sha256.takeIf { it.isNotBlank() }
        val current = request.toMatch(hash)

        try {
            if (hash == null) {
                inMemoryHistory.add(current)
                return ToolResultDuplicateDetection(skippedReason = "missing result hash")
            }

            if (request.stored.originalBytes < minComparableBytes) {
                inMemoryHistory.add(current)
                return ToolResultDuplicateDetection(skippedReason = "short result below ${minComparableBytes} bytes")
            }

            val matchesByUuid = linkedMapOf<String, ToolResultDuplicateMatch>()

            loadDbMatches(request, hash).forEach { match ->
                if (match.resultUuid != request.resultUuid) {
                    matchesByUuid[match.resultUuid] = match
                }
            }

            inMemoryHistory
                .asSequence()
                .filter { it.resultSha256 == hash && it.resultUuid != request.resultUuid }
                .forEach { match -> matchesByUuid.putIfAbsent(match.resultUuid, match) }

            val matches = matchesByUuid.values.toList()
            inMemoryHistory.add(current)

            if (matches.isEmpty()) {
                return ToolResultDuplicateDetection()
            }

            val appearances = matches.size + 1
            val severity = if (appearances >= warningThreshold) {
                ToolResultDuplicateSeverity.WARNING
            } else {
                ToolResultDuplicateSeverity.NOTICE
            }

            return ToolResultDuplicateDetection(
                matches = matches,
                totalAppearancesIncludingCurrent = appearances,
                severity = severity
            )
        } catch (e: Exception) {
            logger?.warn("Tool result duplicate detection failed: ${e.message}")
            inMemoryHistory.add(current)
            return ToolResultDuplicateDetection(skippedReason = "duplicate detection failed")
        }
    }

    private fun loadDbMatches(
        request: ToolResultInspectionRequest,
        hash: String
    ): List<ToolResultDuplicateMatch> {
        val sid = request.sessionId ?: sessionId ?: return emptyList()
        val client = agentDbClient ?: return emptyList()

        return try {
            client.findToolCallResults(
                sessionId = sid,
                resultSha256 = hash,
                limit = maxDbMatches
            ).map { info ->
                ToolResultDuplicateMatch(
                    resultUuid = info.resultUuid,
                    callId = info.callId,
                    iteration = null,
                    toolName = info.toolName,
                    toolArgs = info.toolArgs,
                    resultSha256 = info.sha256,
                    originalBytes = info.originalBytes,
                    createdAt = info.createdAt,
                    source = "database"
                )
            }
        } catch (e: Exception) {
            logger?.warn("Failed to load historical tool result hashes: ${e.message}")
            emptyList()
        }
    }

    private fun ToolResultInspectionRequest.toMatch(hash: String?): ToolResultDuplicateMatch =
        ToolResultDuplicateMatch(
            resultUuid = resultUuid,
            callId = null,
            iteration = iteration,
            toolName = toolCall.name,
            toolArgs = canonicalJson(toolCall.argumentsJson),
            resultSha256 = hash,
            originalBytes = stored.originalBytes,
            createdAt = null,
            source = "current-run"
        )

    private fun canonicalJson(text: String): String = try {
        mapper.readTree(text).toString()
    } catch (_: Exception) {
        text
    }

    @Suppress("unused")
    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

fun ToolResultDuplicateDetection.toObservationPrefix(currentToolName: String): String? {
    if (!isDuplicate) return null

    val first = matches.first()
    val header = when (severity) {
        ToolResultDuplicateSeverity.WARNING -> "[Repeated tool result warning]"
        ToolResultDuplicateSeverity.NOTICE -> "[Duplicate tool result notice]"
        ToolResultDuplicateSeverity.NONE -> return null
    }

    return buildString {
        appendLine(header)
        appendLine("The output of the current `$currentToolName` call is byte-for-byte identical to a previous tool result in this session.")
        appendLine("same_result_appearances_including_current: $totalAppearancesIncludingCurrent")
        appendLine("first_matching_result_uuid: ${first.resultUuid}")
        first.callId?.let { appendLine("first_matching_call_id: $it") }
        first.iteration?.let { appendLine("first_matching_iteration: $it") }
        appendLine("first_matching_tool: ${first.toolName}")
        first.toolArgs?.let { appendLine("first_matching_args: $it") }
        first.createdAt?.let { appendLine("first_matching_created_at: $it") }
        if (severity == ToolResultDuplicateSeverity.WARNING) {
            appendLine("This may indicate a large reasoning loop. Do not repeat this analysis path unless you can state what new evidence it provides; produce **Final Answer:** if the task is already answerable, or switch to a meaningfully different tool/argument.")
        } else {
            appendLine("Avoid repeating this path unless there is a clear validation reason.")
        }
    }.trim()
}
