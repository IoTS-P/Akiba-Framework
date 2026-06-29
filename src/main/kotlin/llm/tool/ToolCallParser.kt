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
        val parsedCalls = mutableListOf<Pair<IntRange, ParsedToolCall>>()
        val seenJsonRanges = mutableListOf<IntRange>()

        fun alreadyCovered(range: IntRange): Boolean =
            seenJsonRanges.any { it.first <= range.first && it.last >= range.last }

        fun addParsed(range: IntRange, jsonStr: String): Boolean {
            if (alreadyCovered(range)) return false
            val calls = tryParseToolCallJson(jsonStr)
            if (calls.isEmpty()) return false
            calls.forEachIndexed { idx, call ->
                // Keep the source order stable even when one JSON object contains
                // multiple calls (e.g. {"tool_calls": [...]}).
                parsedCalls.add((range.first + idx)..range.last to call)
            }
            seenJsonRanges.add(range)
            return true
        }

        // 1. Prefer JSON/tool_call code blocks. They are the strongest signal
        // that the LLM intended executable tool syntax rather than prose examples.
        var cbMatch = codeBlockStart.find(response)
        while (cbMatch != null) {
            val jsonStart = cbMatch.range.last + 1
            val braceStart = response.indexOf('{', jsonStart)
            if (braceStart >= 0) {
                val (jsonStr, endIdx) = extractBalancedJsonWithEnd(response, braceStart)
                if (jsonStr != null) addParsed(braceStart..endIdx, jsonStr)
            }
            cbMatch = codeBlockStart.find(response, cbMatch.range.last + 1)
        }

        // 2. Parse any balanced JSON object in the visible text. This recovers
        // common near-miss formats such as:
        //   {"name":"tool", "arguments":{...}}
        //   {"function":{"name":"tool", "arguments":"{...}"}}
        //   {"action":"run", "scriptName":"...", "parameters":{...}}
        // We only accept objects that can be normalized into a real tool call.
        var searchFrom = 0
        while (searchFrom < response.length) {
            val braceStart = response.indexOf('{', searchFrom)
            if (braceStart < 0) break
            if (alreadyCovered(braceStart..braceStart)) {
                searchFrom = braceStart + 1
                continue
            }
            val (jsonStr, endIdx) = extractBalancedJsonWithEnd(response, braceStart)
            if (jsonStr == null) {
                searchFrom = braceStart + 1
                continue
            }
            val parsed = addParsed(braceStart..endIdx, jsonStr)
            searchFrom = if (parsed) endIdx + 1 else braceStart + 1
        }

        // 3. ReAct-style fallback: some models emit "Action: tool_name" plus
        // "Action Input: {...}" instead of fenced JSON. Convert that shape too.
        parsedCalls.addAll(parseActionInputBlocks(response).map { it.first to it.second })

        return parsedCalls
            .distinctBy { it.second.name + "\u0000" + it.second.argumentsJson }
            .sortedBy { it.first.first }
            .map { it.second }
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
     * Parse a JSON string as one or more tool-call objects.
     *
     * Accepted shapes intentionally cover common LLM/provider variants:
     * - {"tool_call":{"name":"...","arguments":{...}}}
     * - {"name":"...","arguments":{...}}
     * - {"function":{"name":"...","arguments":"{...}"}}
     * - {"tool_calls":[{"function":{"name":"...","arguments":"{...}"}}]}
     * - Expanded script_library/query_ghidra_api calls that include no wrapper.
     */
    private fun tryParseToolCallJson(jsonStr: String): List<ParsedToolCall> {
        return try {
            val parsed = mapper.readValue<Map<String, Any?>>(jsonStr)
            parseToolCallsFromMap(parsed)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseToolCallsFromMap(parsed: Map<String, Any?>): List<ParsedToolCall> {
        val calls = mutableListOf<ParsedToolCall>()

        // OpenAI-style batch wrapper.
        val toolCalls = parsed["tool_calls"] as? List<*>
        if (toolCalls != null) {
            for (item in toolCalls) {
                val itemMap = item as? Map<*, *> ?: continue
                parseSingleToolCallMap(toStringKeyMap(itemMap))?.let { calls.add(it) }
            }
            if (calls.isNotEmpty()) return calls
        }

        parseSingleToolCallMap(parsed)?.let { calls.add(it) }
        return calls
    }

    private fun parseSingleToolCallMap(parsed: Map<String, Any?>): ParsedToolCall? {
        val candidate: Map<String, Any?> = when {
            parsed["tool_call"] is Map<*, *> -> toStringKeyMap(parsed["tool_call"] as Map<*, *>)
            parsed["function_call"] is Map<*, *> -> toStringKeyMap(parsed["function_call"] as Map<*, *>)
            parsed["function"] is Map<*, *> -> toStringKeyMap(parsed["function"] as Map<*, *>)
            parsed["type"] == "tool_use" -> parsed
            hasAnyKey(parsed, listOf("name", "tool", "tool_name", "toolName")) -> parsed
            else -> inferExpandedToolCall(parsed) ?: return null
        }

        // Defensive fix: if the candidate carries the signature of
        // `spawn_sub_agent` (templateId + inputs), it is almost certainly
        // an LLM that emitted the tool's arguments WITHOUT the outer
        // `{"tool_call": {...}}` wrapper. In that case the top-level
        // `name` field is the *child session display name* (a parameter),
        // not the tool name — and using it as the tool name produces
        // "Unknown tool: <child-session-name>" in the parent registry.
        // Force the tool name to `spawn_sub_agent` and keep the rest of
        // the fields as arguments (where `name` correctly belongs). The
        // wrapped form is unaffected because templateId / inputs live
        // one level deeper, inside `arguments`.
        if (candidate["templateId"] is String && candidate["inputs"] != null) {
            val args = candidate.toMutableMap()
            return ParsedToolCall(
                callId = "tc_${System.nanoTime()}",
                name = "spawn_sub_agent",
                arguments = args,
                argumentsJson = mapper.writeValueAsString(args)
            )
        }

        val nameKeys = listOf("name", "tool", "tool_name", "toolName", "type")
        val argKeys = listOf("arguments", "args", "parameters", "input", "params")
        val name = firstString(candidate, listOf("name", "tool", "tool_name", "toolName")) ?: return null
        val rawArgs = firstPresent(candidate, argKeys)
        val args = if (rawArgs == null) {
            // Some models put expanded arguments next to the name instead of
            // under an `arguments` object: {"name":"tool", "foo":"bar"}.
            candidate.filterKeys { it !in nameKeys && it !in argKeys }
        } else {
            parseArguments(rawArgs) ?: emptyMap()
        }
        return ParsedToolCall(
            callId = "tc_${System.nanoTime()}",
            name = name,
            arguments = args,
            argumentsJson = mapper.writeValueAsString(args)
        )
    }

    /** Infer the tool when the LLM emitted already-expanded tool arguments. */
    private fun inferExpandedToolCall(parsed: Map<String, Any?>): Map<String, Any?>? {
        val action = parsed["action"] as? String ?: return null
        return when {
            parsed["scriptName"] is String && action in setOf("search", "read", "run") -> {
                val args = parsed.toMutableMap()
                // Common shorthand used by models; canonical tool arg is `parameters`.
                if ("parameters" !in args && parsed["params"] is Map<*, *>) {
                    args["parameters"] = parsed["params"]
                    args.remove("params")
                }
                mapOf("name" to "script_library", "arguments" to args)
            }
            parsed["keyword"] is String && action in setOf("search", "read_class") ->
                mapOf("name" to "query_ghidra_api", "arguments" to parsed)
            else -> null
        }
    }

    private fun toStringKeyMap(map: Map<*, *>): Map<String, Any?> =
        map.entries.mapNotNull { (k, v) -> (k as? String)?.let { it to v } }.toMap()

    private fun hasAnyKey(map: Map<String, Any?>, keys: List<String>): Boolean =
        keys.any { it in map }

    private fun firstString(map: Map<String, Any?>, keys: List<String>): String? =
        keys.firstNotNullOfOrNull { key -> (map[key] as? String)?.takeIf { it.isNotBlank() } }

    private fun firstPresent(map: Map<String, Any?>, keys: List<String>): Any? {
        for (key in keys) if (map.containsKey(key)) return map[key]
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseArguments(raw: Any?): Map<String, Any?>? = when (raw) {
        null -> emptyMap()
        is Map<*, *> -> toStringKeyMap(raw)
        is String -> {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) emptyMap()
            else try { mapper.readValue(trimmed, Map::class.java) as Map<String, Any?> }
            catch (_: Exception) { mapOf("_raw" to raw) }
        }
        else -> mapOf("_raw" to raw)
    }

    /** Parse common text-only ReAct fallback: Action: <tool> + Action Input: {...}. */
    private fun parseActionInputBlocks(response: String): List<Pair<IntRange, ParsedToolCall>> {
        val actionRegex = Regex(
            "(?is)(?:^|\\n)\\s*(?:Action|Tool)\\s*:\\s*([A-Za-z_][A-Za-z0-9_\\-.]*)" +
                ".{0,400}?(?:Action\\s*Input|Input|Arguments|Args)\\s*:\\s*"
        )
        val results = mutableListOf<Pair<IntRange, ParsedToolCall>>()
        for (match in actionRegex.findAll(response)) {
            val toolName = match.groupValues[1]
            val braceStart = response.indexOf('{', match.range.last + 1)
            if (braceStart < 0) continue
            val (jsonStr, endIdx) = extractBalancedJsonWithEnd(response, braceStart)
            if (jsonStr == null) continue
            val args = parseArguments(jsonStr) ?: emptyMap()
            val call = ParsedToolCall(
                callId = "tc_${System.nanoTime()}",
                name = toolName.substringAfterLast('.'),
                arguments = args,
                argumentsJson = mapper.writeValueAsString(args)
            )
            results.add(match.range.first..endIdx to call)
        }
        return results
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
        val sb = StringBuilder()

        while (i < text.length) {
            val c = text[i]
            when {
                inString -> {
                    sb.append(c)
                    if (c == '\\') {
                        i++
                        if (i < text.length) { sb.append(text[i]) }
                    } else if (c == '"') {
                        inString = false
                    }
                }
                c == '"' -> { sb.append(c); inString = true }
                c == '{' -> { sb.append(c); depth++ }
                c == '}' -> {
                    sb.append(c); depth--
                    if (depth == 0) {
                        return sb.toString() to i
                    }
                }
                else -> sb.append(c)
            }
            i++
        }

        // Reached end with unclosed braces — try to auto-close
        if (depth > 0) {
            repeat(depth) { sb.append('}') }
            return sb.toString() to (i - 1)
        }
        return null to -1
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