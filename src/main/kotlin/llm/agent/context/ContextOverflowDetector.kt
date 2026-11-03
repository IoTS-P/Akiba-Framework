package org.iotsplab.akiba.llm.agent.context

// ============================================================
//  ContextOverflowDetector — opencode-style two-layer detection
// ============================================================

/**
 * Detects provider context-length-overflow errors in two layers, mirroring
 * opencode: regex over the error text (cause chain included), then structured
 * checks (HTTP 413, `context_length_exceeded`, or a bare 400 with no body).
 *
 * Exclusion patterns (rate limit / throttling) win first so quota errors are
 * never misread as overflow. Detection is text-first and needs no specific
 * exception type: gateways may wrap langchain4j's `HttpException` differently.
 */
object ContextOverflowDetector {

    /** Rate-limit / quota phrasings that must NOT be treated as overflow. */
    private val EXCLUSION_PATTERNS = listOf(
        Regex("rate[ _-]?limit", RegexOption.IGNORE_CASE),
        Regex("too many requests", RegexOption.IGNORE_CASE),
        Regex("throttl", RegexOption.IGNORE_CASE),
        Regex("quota", RegexOption.IGNORE_CASE),
        Regex("insufficient.*(balance|fund|credit)", RegexOption.IGNORE_CASE),
        Regex("\\b429\\b"),
    )

    /** Overflow phrasings observed across providers (case-insensitive). */
    private val OVERFLOW_PATTERNS = listOf(
        "prompt is too long",
        "context[_ ]length[_ ]exceeded",
        "context_length_exceeded",
        "maximum context length is \\d+ tokens",
        "max(imum)? context length",
        "context window",
        "exceeds the context",
        "too many tokens",
        "token limit exceeded",
        "exceeds? the maximum (number of )?tokens",
        "reduce the length",
        "input tokens",
        "input is too long",
        "input too long",
        "sequence (is )?too long",
        "request entity too large",
        "payload too large",
        "request too large",
        "prompt too large",
        "content too long",
        "string too long",
        "context size",
        "model's maximum context",
        "larger than the context",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    /** Concatenated messages of the whole cause chain (regex input). */
    private fun collectMessages(e: Throwable): String {
        val sb = StringBuilder()
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < 8) {
            cur.message?.let { sb.append(it).append('\n') }
            cur = cur.cause
            depth++
        }
        return sb.toString()
    }

    /** Walk the cause chain for a langchain4j HttpException (for status checks). */
    private fun findHttpException(e: Throwable?): dev.langchain4j.exception.HttpException? {
        var cur = e
        var depth = 0
        while (cur != null && depth < 8) {
            if (cur is dev.langchain4j.exception.HttpException) return cur
            cur = cur.cause
            depth++
        }
        return null
    }

    /** "maximum context length is 131072 tokens" → 131072. */
    private val PROVIDER_LIMIT_PATTERN =
        Regex("maximum context length is (\\d+) tokens", RegexOption.IGNORE_CASE)

    /**
     * Extract the exact context-window size from a provider overflow
     * error, when the error text states it. Returns null when the
     * provider did not name a number.
     */
    fun extractProviderLimit(e: Throwable): Int? =
        PROVIDER_LIMIT_PATTERN.find(collectMessages(e))?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Whether [e] is a provider context-length rejection.
     * Exclusions are evaluated first: a rate-limit/quota error is never
     * overflow, even if some overflow pattern also matches.
     */
    fun isContextOverflow(e: Throwable): Boolean {
        val text = collectMessages(e)
        if (EXCLUSION_PATTERNS.any { it.containsMatchIn(text) }) return false

        // Layer 1: regex on the error text.
        if (OVERFLOW_PATTERNS.any { it.containsMatchIn(text) }) return true

        // Layer 2: structured checks.
        val httpEx = findHttpException(e)
        val status = httpEx?.let { runCatching { it.statusCode() }.getOrNull() }
        if (status == 413) return true
        if (status == 400 && text.contains("no body", ignoreCase = true)) return true
        return false
    }
}
