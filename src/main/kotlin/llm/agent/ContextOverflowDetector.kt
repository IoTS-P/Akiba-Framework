package org.iotsplab.akiba.llm.agent

// ============================================================
//  ContextOverflowDetector — opencode-style two-layer detection
// ============================================================

/**
 * Detects provider context-length-overflow errors with two layers,
 * mirroring opencode's approach:
 *
 * 1. **Regex matching** on the error text (cause chain included) —
 *    covers the many phrasings providers use, since the wording is NOT
 *    standardised. Exclusion patterns (rate limit / throttling) win
 *    first so quota errors are never misread as overflow.
 * 2. **Structured checks** — HTTP 413, a `context_length_exceeded`
 *    error code in the body, or a bare 400 with no body (a common
 *    provider behaviour on oversized prompts).
 *
 * Detection is deliberately text-first and does NOT require a specific
 * exception type: langchain4j surfaces provider errors as
 * `HttpException`, but gateways may wrap them differently.
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
