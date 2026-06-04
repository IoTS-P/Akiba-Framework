package org.iotsplab.akiba.llm.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.iotsplab.akiba.llm.agent.ParsedToolCall
import org.iotsplab.akiba.llm.client.ChatCompletion
import kotlin.collections.get

// ============================================================
//  Tool call parsing — shared across strategies
// ============================================================

object ToolCallParser {
    private val mapper = jacksonObjectMapper()

    /** Pattern to detect the start of a json code block containing tool_call. */
    private val codeBlockStart = Regex("""```(?:json|tool_call)\s*""")

    /** Complete reasoning-channel block: <think>...</think> (case-insensitive, spans newlines). */
    private val thinkBlock = Regex("""(?is)<\s*think\s*>.*?<\s*/\s*think\s*>""")

    /** Any stray opening/closing think tag left over after block removal. */
    private val strayThinkTag = Regex("""(?is)<\s*/?\s*think\s*>""")

    /**
     * Strip reasoning-channel markup that some models (e.g. R1-style) leak into
     * the visible content. Handles three cases:
     *  1. Well-formed <think>...</think> blocks → removed entirely.
     *  2. A leaked closing </think> with no opening tag (the model's reasoning
     *     prefix bled into content) → everything up to and including it is dropped.
     *  3. Any remaining stray <think> / </think> tags → removed.
     *
     * The result is trimmed. This is intentionally conservative: it only touches
     * think tags and never alters tool_call JSON or normal prose.
     */
    fun stripThinking(content: String): String {
        if (content.isEmpty()) return content

        var text = thinkBlock.replace(content, "")

        // Case 2: a closing tag survived without a matching opener. Treat all
        // text before the LAST such closing tag as reasoning and discard it.
        val lastClose = strayThinkTag.findAll(text)
            .lastOrNull { it.value.contains("/") }
        if (lastClose != null) {
            text = text.substring(lastClose.range.last + 1)
        }

        // Case 3: drop any leftover stray tags.
        text = strayThinkTag.replace(text, "")

        return text.trim()
    }

    /**
     * Try to parse a tool call from the assistant's text response.
     *
     * Returns null if no tool call is found.
     */
    fun parse(response: String): ParsedToolCall? {
        return parseAll(response).firstOrNull()
    }

    /**
     * Parse ALL tool calls from the assistant's response, in textual order.
     * Useful for letting the LLM batch multiple actions in one response.
     *
     * Returns an empty list if none are found.
     */
    fun parseAll(response: String): List<ParsedToolCall> {
        val results = mutableListOf<ParsedToolCall>()
        val seenJsonRanges = mutableSetOf<IntRange>()

        // 1. Code block extraction
        var cbMatch = codeBlockStart.find(response)
        while (cbMatch != null) {
            val jsonStart = cbMatch.range.last + 1
            val braceStart = response.indexOf('{', jsonStart)
            if (braceStart >= 0) {
                val (jsonStr, endIdx) = extractBalancedJsonWithEnd(response, braceStart)
                if (jsonStr != null) {
                    val range = braceStart..endIdx
                    if (seenJsonRanges.none { it.first <= range.first && it.last >= range.last }) {
                        val result = tryParseToolCallJson(jsonStr)
                        if (result != null) {
                            results.add(result)
                            seenJsonRanges.add(range)
                        }
                    }
                }
            }
            cbMatch = codeBlockStart.find(response, cbMatch.range.last + 1)
        }

        // 2. Bare JSON containing "tool_call"
        val toolCallKeyword = "\"tool_call\""
        var searchFrom = 0
        while (searchFrom < response.length) {
            val keyIdx = response.indexOf(toolCallKeyword, searchFrom)
            if (keyIdx < 0) break

            val braceStart = findOpeningBrace(response, keyIdx)
            if (braceStart >= 0) {
                val (jsonStr, endIdx) = extractBalancedJsonWithEnd(response, braceStart)
                if (jsonStr != null) {
                    val range = braceStart..endIdx
                    // Skip if this range is already inside a previously-found JSON block
                    if (seenJsonRanges.none { it.first <= range.first && it.last >= range.last }) {
                        val result = tryParseToolCallJson(jsonStr)
                        if (result != null) {
                            results.add(result)
                            seenJsonRanges.add(range)
                        }
                    }
                }
            }
            searchFrom = keyIdx + toolCallKeyword.length
        }

        // Sort by position in source so the call order matches the LLM's intent
        return results.zip(seenJsonRanges).sortedBy { it.second.first }.map { it.first }
    }

    /**
     * Walk backwards from [fromIndex] to find the nearest '{'.
     * Skips whitespace and colon characters.
     */
    private fun findOpeningBrace(text: String, fromIndex: Int): Int {
        var i = fromIndex - 1
        while (i >= 0) {
            when {
                text[i] == '{' -> return i
                text[i].isWhitespace() || text[i] == ':' -> i--
                else -> break
            }
        }
        return -1
    }

    /**
     * Parse a JSON string as a tool_call object.
     * Returns null if parsing fails or the structure is invalid.
     */
    private fun tryParseToolCallJson(jsonStr: String): ParsedToolCall? {
        return try {
            val parsed = mapper.readValue<Map<String, Any?>>(jsonStr)
            val toolCallObj = parsed["tool_call"] as? Map<*, *> ?: return null
            val name = toolCallObj["name"] as? String ?: return null
            @Suppress("UNCHECKED_CAST")
            val args = toolCallObj["arguments"] as? Map<String, Any?> ?: emptyMap()
            ParsedToolCall(
                callId = "tc_${System.nanoTime()}",
                name = name,
                arguments = args,
                argumentsJson = mapper.writeValueAsString(args)
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extract a complete JSON object from [text] starting at [startIndex]
     * by counting balanced braces. Handles nested objects and strings
     * (with escaped quotes).
     *
     * Returns the JSON substring, or null if braces are unbalanced.
     */
    private fun extractBalancedJson(text: String, startIndex: Int): String? {
        return extractBalancedJsonWithEnd(text, startIndex).first
    }

    /**
     * Variant of [extractBalancedJson] that also returns the end index of
     * the balanced JSON (inclusive of the closing brace), or -1 if not found.
     */
    private fun extractBalancedJsonWithEnd(text: String, startIndex: Int): Pair<String?, Int> {
        if (startIndex >= text.length || text[startIndex] != '{') return null to -1

        var depth = 0
        var inString = false
        var i = startIndex

        while (i < text.length) {
            val c = text[i]
            when {
                inString -> {
                    if (c == '\\') {
                        i++ // skip escaped character
                    } else if (c == '"') {
                        inString = false
                    }
                }
                c == '"' -> inString = true
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) {
                        return text.substring(startIndex, i + 1) to i
                    }
                }
            }
            i++
        }
        return null to -1 // unbalanced
    }

    /**
     * Check if the completion contains native tool calls.
     */
    fun isNativeToolCall(completion: ChatCompletion): Boolean {
        return completion.toolCalls.isNotEmpty() ||
                completion.finishReason == "tool_calls" ||
                completion.finishReason == "function_call"
    }

    /**
     * Parse tool calls from a completion.
     *
     * Priority:
     * 1. Native tool calls (from provider's function calling protocol)
     * 2. Text-embedded tool calls (JSON in assistant text)
     *
     * Returns the first tool call found (multiple tool calls in a single
     * response are supported via [parseAllFromCompletion]).
     */
    fun parseFromCompletion(completion: ChatCompletion): ParsedToolCall? {
        // 1. Check for native tool calls from the provider
        if (completion.toolCalls.isNotEmpty()) {
            val first = completion.toolCalls.first()
            val args: Map<String, Any?> = try {
                mapper.readValue(first.argumentsJson)
            } catch (_: Exception) {
                emptyMap()
            }
            return ParsedToolCall(
                callId = first.id,
                name = first.name,
                arguments = args,
                argumentsJson = first.argumentsJson
            )
        }

        // 2. Fall back to text-based parsing
        val content = completion.content
        if (content.isNotBlank()) {
            return parse(content)
        }
        return null
    }

    /**
     * Like [parseFromCompletion] but returns ALL tool calls in the completion,
     * supporting batch invocations. Native provider tool calls take precedence;
     * if none, falls back to parsing all JSON tool_call blocks from the text.
     */
    fun parseAllFromCompletion(completion: ChatCompletion): List<ParsedToolCall> {
        // 1. Native provider tool calls
        if (completion.toolCalls.isNotEmpty()) {
            return completion.toolCalls.map { tc ->
                val args: Map<String, Any?> = try {
                    mapper.readValue(tc.argumentsJson)
                } catch (_: Exception) {
                    emptyMap()
                }
                ParsedToolCall(
                    callId = tc.id,
                    name = tc.name,
                    arguments = args,
                    argumentsJson = tc.argumentsJson
                )
            }
        }

        // 2. Fall back to text-based parsing of all JSON tool_call blocks
        val content = completion.content
        return if (content.isNotBlank()) parseAll(content) else emptyList()
    }
}