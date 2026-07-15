package org.iotsplab.akiba.llm.agent

import org.iotsplab.akiba.llm.memory.AgentChatMessage
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object ToolResultContext {
    const val CURRENT_CONTEXT_MAX_BYTES = 8_000
    const val CURRENT_HEAD_BYTES = 6_000
    const val CURRENT_TAIL_BYTES = 2_000
    const val HISTORICAL_CONTEXT_MAX_BYTES = 2_000
    const val HISTORICAL_HEAD_BYTES = 1_500
    const val HISTORICAL_TAIL_BYTES = 500
    const val STORED_MAX_BYTES = 100 * 1024
    const val STORED_HEAD_BYTES = 70 * 1024
    const val STORED_TAIL_BYTES = 30 * 1024
    const val RECENT_FULL_TOOL_RESULTS = 5

    data class StoredResult(
        val content: String,
        val originalBytes: Int,
        val storedBytes: Int,
        val truncated: Boolean,
        val sha256: String,
        val storagePolicy: String
    )

    fun prepareForStorage(raw: String): StoredResult {
        val originalBytes = byteSize(raw)
        val sha256 = sha256Hex(raw)
        if (originalBytes <= STORED_MAX_BYTES) {
            return StoredResult(raw, originalBytes, originalBytes, truncated = false, sha256 = sha256, storagePolicy = "full")
        }

        val marker = "\n... [stored result truncated: original_bytes=$originalBytes, kept head/tail snapshot] ...\n"
        val markerBytes = byteSize(marker)
        val contentBudget = (STORED_MAX_BYTES - markerBytes).coerceAtLeast(0)
        val headBudget = minOf(STORED_HEAD_BYTES, (contentBudget * 0.7).toInt())
        val tailBudget = (contentBudget - headBudget).coerceAtLeast(0)
        val stored = takeUtf8Prefix(raw, headBudget) + marker + takeUtf8Suffix(raw, tailBudget)
        return StoredResult(
            content = stored,
            originalBytes = originalBytes,
            storedBytes = byteSize(stored),
            truncated = true,
            sha256 = sha256,
            storagePolicy = "head_tail_100kb"
        )
    }

    fun formatCurrentResult(raw: String, resultUuid: String?, stored: StoredResult): String =
        formatResultForContext(
            raw = raw,
            resultUuid = resultUuid,
            stored = stored,
            maxBytes = CURRENT_CONTEXT_MAX_BYTES,
            preferredHeadBytes = CURRENT_HEAD_BYTES,
            preferredTailBytes = CURRENT_TAIL_BYTES,
            historical = false
        )

    fun compactHistoricalToolMessages(messages: List<AgentChatMessage>, keepRecentToolResults: Int = RECENT_FULL_TOOL_RESULTS): List<AgentChatMessage> {
        val toolIndexes = messages.indices.filter { messages[it].role == "tool" }
        val keep = toolIndexes.takeLast(keepRecentToolResults).toSet()
        return messages.mapIndexed { index, message ->
            if (message.role != "tool" || index in keep || byteSize(message.content) <= HISTORICAL_CONTEXT_MAX_BYTES) {
                message
            } else {
                val uuid = extractResultUuid(message.content)
                val prefix = buildString {
                    append("[Historical tool result compacted for context")
                    if (uuid != null) append("; result_uuid=$uuid")
                    appendLine("]")
                    if (uuid != null) appendLine("To inspect stored result, call read_history_tool_call with uuid=$uuid.")
                }
                val compacted = boundedHeadTail(
                    text = message.content,
                    maxBytes = HISTORICAL_CONTEXT_MAX_BYTES,
                    preferredHeadBytes = HISTORICAL_HEAD_BYTES,
                    preferredTailBytes = HISTORICAL_TAIL_BYTES,
                    prefix = prefix
                )
                message.copy(content = compacted)
            }
        }
    }

    private fun formatResultForContext(
        raw: String,
        resultUuid: String?,
        stored: StoredResult,
        maxBytes: Int,
        preferredHeadBytes: Int,
        preferredTailBytes: Int,
        historical: Boolean
    ): String {
        val header = buildString {
            appendLine("[Tool result ${if (historical) "historical" else "context"} view]")
            if (resultUuid != null) {
                appendLine("result_uuid: $resultUuid")
            }
            appendLine("original_bytes: ${stored.originalBytes}")
            appendLine("stored_bytes: ${stored.storedBytes}")
            appendLine("storage_policy: ${stored.storagePolicy}")
            appendLine("sha256: ${stored.sha256}")
            if (resultUuid != null) {
                appendLine("To inspect stored result, call read_history_tool_call with uuid=$resultUuid.")
            }
        }
        return boundedHeadTail(raw, maxBytes, preferredHeadBytes, preferredTailBytes, header)
    }

    private fun boundedHeadTail(
        text: String,
        maxBytes: Int,
        preferredHeadBytes: Int,
        preferredTailBytes: Int,
        prefix: String
    ): String {
        val prefixBytes = byteSize(prefix)
        if (prefixBytes >= maxBytes) return takeUtf8Prefix(prefix, maxBytes)
        val remaining = maxBytes - prefixBytes
        if (byteSize(text) <= remaining) return prefix + text

        val marker = "\n... [omitted ${byteSize(text)} original bytes; showing head/tail] ...\n"
        val markerBytes = byteSize(marker)
        val contentBudget = (remaining - markerBytes).coerceAtLeast(0)
        val headBudget = minOf(preferredHeadBytes, (contentBudget * 3) / 4)
        val tailBudget = minOf(preferredTailBytes, contentBudget - headBudget)
        val adjustedHead = (contentBudget - tailBudget).coerceAtLeast(0)
        return prefix + takeUtf8Prefix(text, adjustedHead) + marker + takeUtf8Suffix(text, tailBudget)
    }

    fun byteSize(text: String): Int = text.toByteArray(StandardCharsets.UTF_8).size

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun extractResultUuid(text: String): String? =
        Regex("result_uuid[:=]\\s*([0-9a-fA-F-]{36})").find(text)?.groupValues?.getOrNull(1)

    private fun takeUtf8Prefix(text: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        val out = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val s = String(Character.toChars(cp))
            val b = byteSize(s)
            if (bytes + b > maxBytes) break
            out.append(s)
            bytes += b
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    private fun takeUtf8Suffix(text: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        val parts = ArrayDeque<String>()
        var bytes = 0
        var i = text.length
        while (i > 0) {
            val cp = text.codePointBefore(i)
            val s = String(Character.toChars(cp))
            val b = byteSize(s)
            if (bytes + b > maxBytes) break
            parts.addFirst(s)
            bytes += b
            i -= Character.charCount(cp)
        }
        return parts.joinToString("")
    }
}
