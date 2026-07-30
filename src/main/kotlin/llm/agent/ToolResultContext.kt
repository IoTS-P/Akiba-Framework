package org.iotsplab.akiba.llm.agent

import org.iotsplab.akiba.llm.memory.AgentChatMessage
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object ToolResultContext {
    const val CURRENT_CONTEXT_MAX_BYTES = 40_000
    const val CURRENT_HEAD_BYTES = 35_000
    const val CURRENT_TAIL_BYTES = 5_000
    const val HISTORICAL_CONTEXT_MAX_BYTES = 5_000
    const val HISTORICAL_HEAD_BYTES = 4_000
    const val HISTORICAL_TAIL_BYTES = 1_000
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
                val outcome = boundedHeadTail(
                    text = message.content,
                    maxBytes = HISTORICAL_CONTEXT_MAX_BYTES,
                    preferredHeadBytes = HISTORICAL_HEAD_BYTES,
                    preferredTailBytes = HISTORICAL_TAIL_BYTES,
                    prefix = prefix
                )
                message.copy(content = outcome.result)
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
        // ── Unified truncation warning ──────────────────────────────────
        // Whether the tool itself truncated the result (stored.truncated)
        // or the context-view budget will truncate it below, the LLM sees
        // the SAME prominent marker.  This is the single chokepoint that
        // makes "⚠️ TRUNCATED" appear for EVERY tool and EVERY script
        // without each one having to implement it individually.
        //
        // The warning is placed at the VERY TOP of the header so the LLM
        // cannot miss it (earlier per-tool implementations buried it as a
        // JSON field that the model frequently ignored).
        val toolSelfTruncated = stored.truncated
        val header = buildString {
            if (toolSelfTruncated) {
                appendLine("⚠️ TRUNCATED: The tool itself truncated this result before returning.")
                appendLine("  original_bytes=${stored.originalBytes}, stored_bytes=${stored.storedBytes}")
                appendLine("  The content below may be incomplete. Do NOT assume it is the full output.")
                if (resultUuid != null) {
                    appendLine("  To retrieve the stored (possibly larger) snapshot, call read_history_tool_call with uuid=$resultUuid.")
                }
                appendLine()
            }
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
        val outcome = boundedHeadTail(raw, maxBytes, preferredHeadBytes, preferredTailBytes, header)
        // If boundedHeadTail had to cut the text further (context budget),
        // the marker it inserts already carries the ⚠️ TRUNCATED wording
        // (see boundedHeadTail).  No extra work needed here.
        return outcome.result
    }

    /**
     * Result of [boundedHeadTail]: the formatted string plus a flag
     * indicating whether the [text] had to be trimmed to fit [maxBytes].
     */
    private data class HeadTailOutcome(
        val result: String,
        val truncated: Boolean,
    )

    /**
     * Fit `prefix + text` into [maxBytes] (UTF-8).  When the text fits
     * entirely, returns it verbatim with `truncated=false`.  Otherwise a
     * head/tail snapshot is taken with a prominent `⚠️ TRUNCATED` marker
     * between the two halves so the LLM cannot overlook the cut.
     */
    private fun boundedHeadTail(
        text: String,
        maxBytes: Int,
        preferredHeadBytes: Int,
        preferredTailBytes: Int,
        prefix: String
    ): HeadTailOutcome {
        val prefixBytes = byteSize(prefix)
        if (prefixBytes >= maxBytes) {
            return HeadTailOutcome(takeUtf8Prefix(prefix, maxBytes), truncated = true)
        }
        val remaining = maxBytes - prefixBytes
        if (byteSize(text) <= remaining) {
            return HeadTailOutcome(prefix + text, truncated = false)
        }

        val originalTextBytes = byteSize(text)
        val marker = buildString {
            appendLine()
            append("⚠️ TRUNCATED: ${originalTextBytes} original bytes in this section; ")
            append("only head + tail shown below. ")
            append("The middle portion was omitted to fit the context budget.")
            appendLine()
            appendLine()
        }
        val markerBytes = byteSize(marker)
        val contentBudget = (remaining - markerBytes).coerceAtLeast(0)
        val headBudget = minOf(preferredHeadBytes, (contentBudget * 3) / 4)
        val tailBudget = minOf(preferredTailBytes, contentBudget - headBudget)
        val adjustedHead = (contentBudget - tailBudget).coerceAtLeast(0)
        val result = prefix + takeUtf8Prefix(text, adjustedHead) + marker + takeUtf8Suffix(text, tailBudget)
        return HeadTailOutcome(result, truncated = true)
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
