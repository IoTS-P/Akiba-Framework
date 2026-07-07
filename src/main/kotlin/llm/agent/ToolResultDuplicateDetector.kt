package org.iotsplab.akiba.llm.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.tool.ToolDedupStrategy
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
    val stored: ToolResultContext.StoredResult,
    val isError: Boolean = false,
    val dedupStrategy: ToolDedupStrategy = ToolDedupStrategy.RESULT_HASH,
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
            // ── ARGS_ONLY strategy ──────────────────────────────
            // Compare canonical args (tool name + sorted JSON args)
            // regardless of output.  Used for tools where calling
            // with the same arguments is always wasteful (e.g.
            // `vuln_memory action=record_function function=main`).
            // Short results are NOT exempted.
            if (request.dedupStrategy == ToolDedupStrategy.ARGS_ONLY) {
                return inspectByArgs(request, current, hash)
            }

            // ── RESULT_HASH strategy (default) ──────────────────
            if (hash == null) {
                inMemoryHistory.add(current)
                return ToolResultDuplicateDetection(skippedReason = "missing result hash")
            }

            // Successful results are exempt from the duplicate check
            // when they are very short — that mostly catches the "empty
            // OK" outputs that aren't really evidence of a loop. Failed
            // tool results, on the other hand, are *always* checked: a
            // repeated short error message is the canonical signature of
            // an agent that's stuck retrying a broken tool, and we want
            // to surface the loop as soon as possible instead of waiting
            // for the LLM to escalate to a long error string.
            if (!request.isError && request.stored.originalBytes < minComparableBytes) {
                inMemoryHistory.add(current)
                return ToolResultDuplicateDetection(
                    skippedReason = "short result below ${minComparableBytes} bytes (success)"
                )
            }

            return inspectByResultHash(request, current, hash)
        } catch (e: Exception) {
            logger?.warn("Tool result duplicate detection failed: ${e.message}")
            inMemoryHistory.add(current)
            return ToolResultDuplicateDetection(skippedReason = "duplicate detection failed")
        }
    }

    /**
     * ARGS_ONLY dedup: compare canonical args across the whole session.
     * Any previous call to the SAME tool with the SAME args counts as
     * a match, regardless of the output.
     */
    private fun inspectByArgs(
        request: ToolResultInspectionRequest,
        current: ToolResultDuplicateMatch,
        hash: String?,
    ): ToolResultDuplicateDetection {
        val currentArgsKey = canonicalArgsKey(request.toolCall)

        val matchesByUuid = linkedMapOf<String, ToolResultDuplicateMatch>()

        // DB matches: find previous calls to the same tool with the same args.
        loadDbMatchesByArgs(request, currentArgsKey).forEach { match ->
            if (match.resultUuid != request.resultUuid) {
                matchesByUuid[match.resultUuid] = match
            }
        }

        // In-memory matches.
        inMemoryHistory
            .asSequence()
            .filter { it.resultUuid != request.resultUuid }
            .filter { canonicalArgsKey(it.toolName, it.toolArgs) == currentArgsKey }
            .forEach { match -> matchesByUuid.putIfAbsent(match.resultUuid, match) }

        inMemoryHistory.add(current)

        if (matchesByUuid.isEmpty()) {
            return ToolResultDuplicateDetection()
        }

        val appearances = matchesByUuid.size + 1
        val severity = if (appearances >= warningThreshold) {
            ToolResultDuplicateSeverity.WARNING
        } else {
            ToolResultDuplicateSeverity.NOTICE
        }

        return ToolResultDuplicateDetection(
            matches = matchesByUuid.values.toList(),
            totalAppearancesIncludingCurrent = appearances,
            severity = severity,
        )
    }

    /**
     * RESULT_HASH dedup (original logic): compare SHA-256 of the output.
     */
    private fun inspectByResultHash(
        request: ToolResultInspectionRequest,
        current: ToolResultDuplicateMatch,
        hash: String,
    ): ToolResultDuplicateDetection {
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

        inMemoryHistory.add(current)

        if (matchesByUuid.isEmpty()) {
            return ToolResultDuplicateDetection()
        }

        val appearances = matchesByUuid.size + 1
        val severity = if (appearances >= warningThreshold) {
            ToolResultDuplicateSeverity.WARNING
        } else {
            ToolResultDuplicateSeverity.NOTICE
        }

        return ToolResultDuplicateDetection(
            matches = matchesByUuid.values.toList(),
            totalAppearancesIncludingCurrent = appearances,
            severity = severity,
        )
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

    /**
     * Build a canonical args key for ARGS_ONLY dedup:
     * `"<toolName>:<canonicalJson(args)>"`.
     */
    private fun canonicalArgsKey(toolCall: ParsedToolCall): String =
        canonicalArgsKey(toolCall.name, toolCall.argumentsJson)

    private fun canonicalArgsKey(toolName: String, argsJson: String?): String {
        val canonical = argsJson?.let { canonicalJson(it) } ?: "{}"
        return "$toolName:$canonical"
    }

    /**
     * Load previous calls to the same tool with the same canonical args
     * from the DB.  Used by ARGS_ONLY dedup.
     */
    private fun loadDbMatchesByArgs(
        request: ToolResultInspectionRequest,
        argsKey: String,
    ): List<ToolResultDuplicateMatch> {
        val sid = request.sessionId ?: sessionId ?: return emptyList()
        val client = agentDbClient ?: return emptyList()

        return try {
            client.findToolCallResults(
                sessionId = sid,
                toolName = request.toolCall.name,
                limit = maxDbMatches,
            ).filter { match ->
                canonicalArgsKey(match.toolName, match.toolArgs) == argsKey
            }.map { info ->
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
            logger?.warn("Failed to load historical tool calls by args: ${e.message}")
            emptyList()
        }
    }

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
        ToolResultDuplicateSeverity.WARNING -> "[Repeated tool call warning]"
        ToolResultDuplicateSeverity.NOTICE -> "[Duplicate tool call notice]"
        ToolResultDuplicateSeverity.NONE -> return null
    }

    return buildString {
        appendLine(header)
        appendLine("This `$currentToolName` call repeats a previous call with the same arguments in this session.")
        appendLine("same_call_appearances_including_current: $totalAppearancesIncludingCurrent")
        appendLine("first_matching_result_uuid: ${first.resultUuid}")
        first.callId?.let { appendLine("first_matching_call_id: $it") }
        first.iteration?.let { appendLine("first_matching_iteration: $it") }
        appendLine("first_matching_tool: ${first.toolName}")
        first.toolArgs?.let { appendLine("first_matching_args: $it") }
        first.createdAt?.let { appendLine("first_matching_created_at: $it") }
        if (severity == ToolResultDuplicateSeverity.WARNING) {
            appendLine("This may indicate a reasoning loop. Do not repeat this call with the same arguments unless you can state what new information it provides; produce **Final Answer:** if the task is already answerable, or switch to a meaningfully different tool/argument.")
        } else {
            appendLine("Avoid repeating this call unless there is a clear reason.")
        }
    }.trim()
}
