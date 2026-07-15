package org.iotsplab.akiba.llm.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.runBlocking
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.module.RuntimeReport
import org.iotsplab.akiba.script.ScriptInstance
import kotlin.system.measureTimeMillis

/**
 * A library of pre-built, battle-tested Kotlin scripts for common binary
 * analysis tasks. Scripts are bundled as resources at
 * `script_library/ *.kts` and loaded at first use.
 *
 * ### Supported actions
 *
 * - **search**: Find scripts matching a keyword (or list all if keyword is empty).
 * - **run**: Execute a script by name, optionally passing parameters.
 *
 * ### Script metadata format
 *
 * Each `.kts` file begins with comment-style annotations:
 * ```
* // @name: list_functions
* // @author: Akiba
* // @description: List all functions in the binary
* // @parameters: none
 * ```
 *
 * ### Why use this instead of run_script?
 *
 * - **Zero compilation errors**: pre-built scripts are known to work.
 * - **Faster**: no API lookup or trial-and-error needed.
 * - **Learning**: read script source to understand Ghidra API patterns.
 */
fun ScriptLibraryTool(parent: AkibaModule, agentDbClient: AgentDatabaseClient): Tool = Tool(
    name = "script_library",
    description = buildString {
        appendLine("Access a library of pre-built Kotlin scripts for common binary analysis tasks.")
        appendLine("These scripts are guaranteed to compile and work correctly.")
        appendLine()
        appendLine("Actions:")
        appendLine("  search — Find scripts by keyword. Empty keyword returns all available scripts.")
        appendLine("  read   — Read the full source code of a script (for debugging or learning).")
        appendLine("  run    — Execute a script by name with optional parameters.")
        appendLine()
        appendLine("IMPORTANT: Prefer using scripts from this library over writing custom scripts")
        appendLine("with run_script. Only write custom scripts when the library doesn't cover your need.")
        appendLine("User-saved scripts are also listed here; only reusable scripts with metadata comments")
        appendLine("should be saved to avoid polluting future sessions with one-off analysis code.")
        appendLine()
        appendLine("Available scripts include: binary_info, list_functions, disassemble_function,")
        appendLine("find_dangerous_calls, list_strings, get_xrefs, decompile_function, and more.")
        appendLine()
        appendLine("Example usage:")
        appendLine("  search: {\"action\":\"search\", \"keyword\":\"\"} → list all scripts")
        appendLine("  search: {\"action\":\"search\", \"keyword\":\"disassemble\"} → find disassembly-related scripts")
        appendLine("  read:   {\"action\":\"read\", \"scriptName\":\"disassemble_function\"} → return its full source")
        appendLine("  run:    {\"action\":\"run\", \"scriptName\":\"disassemble_function\", \"parameters\":{\"target\":\"main\"}}")
        appendLine("  run:    {\"action\":\"run\", \"scriptName\":\"list_functions\"}")
        appendLine()
        appendLine("CRITICAL: 'parameters' MUST be a nested JSON object, NOT a string!")
        appendLine("  ✅ CORRECT: \"parameters\": {\"target\": \"main\", \"direction\": \"to\"}")
        appendLine("  ❌ WRONG:   \"parameters\": \"{\\\"target\\\": \\\"main\\\"}\"")
        appendLine()
        appendLine("CRITICAL: The key name is \"parameters\", NOT \"scriptArgs\"!")
        appendLine("  ✅ CORRECT: \"action\":\"run\", \"scriptName\":\"search_strings\", \"parameters\":{\"query\":\"fmt\"}")
        appendLine("  ❌ WRONG:   \"action\":\"run\", \"scriptName\":\"search_strings\", \"scriptArgs\":{\"query\":\"fmt\"}")
        appendLine("  (\"scriptArgs\" is the VARIABLE name used inside scripts to READ the parameters —")
        appendLine("   the API parameter key that carries the values is always \"parameters\".)")
    },
    parameters = listOf(
        ToolParameter(
            "action", "string",
            "Action: 'search' to find scripts, 'read' to view a script's source code, 'run' to execute a script.",
            required = true,
            enum = listOf("search", "read", "run")
        ),
        ToolParameter(
            "keyword", "string",
            "For 'search': keyword to filter scripts (empty string = list all).",
            required = false
        ),
        ToolParameter(
            "scriptName", "string",
            "For 'read' and 'run': the name of the script (from search results).",
            required = false
        ),
        ToolParameter(
            "parameters", "object",
            "For 'run': the script's runtime parameters as a JSON object. Key name MUST be \"parameters\" — do NOT use \"scriptArgs\" (that is the internal variable name inside script source code, not the API key). Example: {\"target\":\"main\",\"direction\":\"to\"}.",
            required = false
        )
    ),
    // Per-script dedup strategy: read-only scripts use RESULT_HASH (default),
    // state-changing scripts declare // @dedup: args_only in their header.
    // The resolver is invoked at call time with the tool's arguments so the
    // detector knows which strategy to apply for the specific script being run.
    dedupStrategyResolver = { toolArgs ->
        val action = toolArgs["action"] as? String
        if (action != "run") {
            // 'search' and 'read' are read-only metadata queries — RESULT_HASH
            // (the default) is appropriate and avoids false positives from
            // slightly different search keyword normalisation.
            ToolDedupStrategy.RESULT_HASH
        } else {
            val scriptName = toolArgs["scriptName"] as? String
            if (scriptName != null) {
                resolveScriptDedupStrategy(agentDbClient, scriptName)
            } else {
                ToolDedupStrategy.RESULT_HASH
            }
        }
    }
) { args ->
    val mapper = jacksonObjectMapper()
    val action = args["action"] as? String
        ?: return@Tool "Error: 'action' parameter is required ('search' or 'run')"

    when (action) {
        "search" -> {
            val keyword = (args["keyword"] as? String)?.trim() ?: ""
            searchScripts(agentDbClient, keyword)
        }
        "read" -> {
            val scriptName = args["scriptName"] as? String
                ?: return@Tool "Error: 'scriptName' parameter is required for 'read' action"
            readScript(agentDbClient, scriptName)
        }
        "run" -> {
            val scriptName = args["scriptName"] as? String
                ?: return@Tool "Error: 'scriptName' parameter is required for 'run' action"

            // Accept `parameters` as either an object/map (the canonical form)
            // or a JSON string (some LLMs serialize nested objects as strings).
            val rawParams = args["parameters"]
            val parameters: Map<String, Any?> = when (rawParams) {
                null -> emptyMap()
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    rawParams as Map<String, Any?>
                }
                is String -> {
                    val trimmed = rawParams.trim()
                    if (trimmed.isEmpty()) emptyMap()
                    else try {
                        @Suppress("UNCHECKED_CAST")
                        mapper.readValue(trimmed, Map::class.java) as Map<String, Any?>
                    } catch (e: Exception) {
                        return@Tool "Error: 'parameters' must be a JSON object " +
                            "(map of string→value), got string that is not valid JSON: ${e.message}"
                    }
                }
                else -> return@Tool "Error: 'parameters' must be a JSON object or a JSON string, " +
                    "got ${rawParams.javaClass.simpleName}"
            }
            runLibraryScript(agentDbClient, parent, scriptName, parameters, mapper)
        }
        else -> "Error: unknown action '$action'. Use 'search', 'read', or 'run'."
    }
}

// ============================================================
//  Script metadata and library management
// ============================================================

/**
 * Metadata parsed from script file headers.
 *
 * [dedupStrategy] controls how the [DefaultToolResultDuplicateDetector]
 * handles repeated calls to this specific script.  It is parsed from the
 * optional `// @dedup:` header annotation and defaults to
 * [ToolDedupStrategy.RESULT_HASH] (compare output SHA-256), which works
 * for read-only scripts.  State-changing scripts (rename, comment-write,
 * data-type-edit, …) should declare `// @dedup: args_only` so that any
 * repeat call with the same arguments is flagged regardless of output.
 */
private data class ScriptMeta(
    val name: String,
    val description: String,
    val author: String,
    val parameters: String,
    val source: String,
    val className: String,
    val dbId: Int? = null,  // script row id in the DB, set when loaded from library
    val dedupStrategy: ToolDedupStrategy = ToolDedupStrategy.RESULT_HASH,
)

/** Cached script library entries. */
@Volatile
private var scriptLibrary: List<ScriptMeta>? = null

/**
 * Cached mapping of `scriptName → dedupStrategy`, populated lazily when
 * scripts are first loaded from the DB.  Used by the
 * [dedupStrategyResolver] on the `script_library` tool so that the
 * [DefaultToolResultDuplicateDetector] can apply per-script dedup logic
 * without re-querying the database on every tool call.
 */
@Volatile
private var scriptDedupCache: Map<String, ToolDedupStrategy>? = null

/**
 * Resolve the dedup strategy for a given library script.
 *
 * Looks up the cached mapping first; on cache miss (or when the script
 * is not yet known) reloads all scripts from the DB, refreshes the
 * cache, and returns the strategy.  Unknown scripts default to
 * [ToolDedupStrategy.RESULT_HASH].
 */
private fun resolveScriptDedupStrategy(
    agentDbClient: AgentDatabaseClient,
    scriptName: String,
): ToolDedupStrategy {
    scriptDedupCache?.let { cache ->
        cache[scriptName]?.let { return it }
    }
    // Cache miss — reload all scripts and rebuild the cache.
    val strategies = loadDbLibraryScripts(agentDbClient)
        .associate { it.name to it.dedupStrategy }
    scriptDedupCache = strategies
    return strategies[scriptName] ?: ToolDedupStrategy.RESULT_HASH
}

/**
 * Load user-saved library scripts from the `scripts` table.
 */
private fun loadDbLibraryScripts(agentDbClient: AgentDatabaseClient): List<ScriptMeta> {
    return try {
        val allScripts = agentDbClient.listScripts(limit = 500)
        allScripts.mapNotNull { info ->
            val source = agentDbClient.getScript(info.id).code ?: return@mapNotNull null
            val displayName = info.name
            val classNameRegex = Regex("""class\s+(\w+)\s*""")
            val className = classNameRegex.find(source)?.groupValues?.get(1) ?: return@mapNotNull null

            // Try to parse annotations from source, fallback to DB fields
            val meta = parseScriptMeta(source, "$displayName.kts")
            meta?.copy(
                name = displayName,
                description = meta.description.ifBlank { (info.description ?: "") },
                author = meta.author.ifBlank { (info.author ?: "") },
                dbId = if (meta.name == displayName) info.id else null
            ) ?: ScriptMeta(
                name = displayName,
                description = info.description ?: "",
                author = info.author ?: "",
                parameters = "none",
                source = source,
                className = className,
                dbId = info.id
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Parse metadata annotations from script source.
 *
 * Recognised header annotations (all optional except `@name` which
 * falls back to the file basename):
 *  - `// @name:`        — script display name
 *  - `// @author:`      — author tag
 *  - `// @description:` — short description
 *  - `// @parameters:`  — parameter documentation
 *  - `// @dedup:`       — dedup strategy: `result_hash` (default) or
 *                         `args_only`.  Use `args_only` for state-changing
 *                         scripts so repeat calls with identical arguments
 *                         are flagged as duplicates regardless of output.
 */
private fun parseScriptMeta(source: String, fileName: String): ScriptMeta? {
    var name = fileName.removeSuffix(".kts")
    var description = ""
    var author = ""
    var parameters = "none"
    var dedupStrategy = ToolDedupStrategy.RESULT_HASH

    for (line in source.lines()) {
        val trimmed = line.trim()
        if (!trimmed.startsWith("//")) break  // stop at first non-comment line

        when {
            trimmed.startsWith("// @name:") ->
                name = trimmed.removePrefix("// @name:").trim()
            trimmed.startsWith("// @author:") ->
                author = trimmed.removePrefix("// @author:").trim()
            trimmed.startsWith("// @description:") ->
                description = trimmed.removePrefix("// @description:").trim()
            trimmed.startsWith("// @parameters:") ->
                parameters = trimmed.removePrefix("// @parameters:").trim()
            trimmed.startsWith("// @dedup:") -> {
                val v = trimmed.removePrefix("// @dedup:").trim().lowercase()
                dedupStrategy = when (v) {
                    "args_only", "args-only", "argsonly" -> ToolDedupStrategy.ARGS_ONLY
                    "result_hash", "result-hash", "resulthash" -> ToolDedupStrategy.RESULT_HASH
                    else -> ToolDedupStrategy.RESULT_HASH
                }
            }
        }
    }

    // Extract class name from the source
    val classNameRegex = Regex("""class\s+(\w+)\s*""")
    val className = classNameRegex.find(source)?.groupValues?.get(1) ?: return null

    return ScriptMeta(name, description, author, parameters, source, className, dedupStrategy = dedupStrategy)
}

// ============================================================
//  Action implementations
// ============================================================

/**
 * Return the full source code of a library script along with its parsed
 * metadata header. Useful for debugging when a `run` action fails: the agent
 * can inspect what the script actually does, find why a parameter or call
 * site goes wrong, and decide whether to fix it (own-authored scripts) or
 * rewrite a replacement via `run_script` (system/other-authored scripts).
 */
private fun readScript(agentDbClient: AgentDatabaseClient, scriptName: String): String {
    val script = loadDbLibraryScripts(agentDbClient).firstOrNull { it.name == scriptName }
        ?: return "Error: Script '$scriptName' not found in library. Use 'search' action to see available scripts."

    val sb = StringBuilder()
    sb.appendLine("=== Script: ${script.name} ===")
    sb.appendLine("Author:     ${script.author.ifBlank { "(unknown)" }}")
    sb.appendLine("Description: ${script.description.ifBlank { "(none)" }}")
    sb.appendLine("Parameters: ${script.parameters}")
    sb.appendLine("Class:      ${script.className}")
    sb.appendLine("Source size: ${script.source.length} chars")
    sb.appendLine()
    sb.appendLine("--- BEGIN SOURCE ---")
    sb.append(script.source)
    if (!script.source.endsWith("\n")) sb.append("\n")
    sb.appendLine("--- END SOURCE ---")
    return sb.toString()
}

private fun searchScripts(agentDbClient: AgentDatabaseClient, keyword: String): String {
    // Also load user-saved scripts from the database
    val allScripts = loadDbLibraryScripts(agentDbClient)

    val matches = if (keyword.isEmpty()) {
        allScripts
    } else {
        val lowerKeyword = keyword.lowercase()
        allScripts.filter {
            it.name.lowercase().contains(lowerKeyword) ||
                it.description.lowercase().contains(lowerKeyword) ||
                it.parameters.lowercase().contains(lowerKeyword)
        }
    }

    if (matches.isEmpty()) {
        return "No scripts found matching '$keyword'."
    }

    val sb = StringBuilder()
    sb.appendLine("=== Script Library (${matches.size} scripts) ===")
    sb.appendLine()
    for (script in matches) {
        sb.appendLine("📜 ${script.name}")
        sb.appendLine("   Description: ${script.description}")
        sb.appendLine("   Author:      ${script.author}")
        sb.appendLine("   Parameters:  ${script.parameters}")
        sb.appendLine()
    }
    sb.appendLine("Use action 'run' with scriptName to execute a script.")
    return sb.toString()
}

private fun kotlinLiteral(value: Any?): String = when (value) {
    null -> "null"
    is String -> buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\u000C")
                '$' -> append("\\${'$'}")
                else -> {
                    if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
                }
            }
        }
        append('"')
    }
    is Boolean -> value.toString()
    is Number -> value.toString()
    is Map<*, *> -> value.entries.joinToString(prefix = "mapOf(", postfix = ")") { (k, v) ->
        "${kotlinLiteral(k?.toString() ?: "")} to ${kotlinLiteral(v)}"
    }
    is Iterable<*> -> value.joinToString(prefix = "listOf(", postfix = ")") { kotlinLiteral(it) }
    is Array<*> -> value.joinToString(prefix = "listOf(", postfix = ")") { kotlinLiteral(it) }
    else -> kotlinLiteral(value.toString())
}

private fun runLibraryScript(
    agentDbClient: AgentDatabaseClient,
    parent: AkibaModule,
    scriptName: String,
    parameters: Map<String, Any?>,
    mapper: com.fasterxml.jackson.databind.ObjectMapper
): String {
    val script = loadDbLibraryScripts(agentDbClient).firstOrNull { it.name == scriptName }
        ?: return "Error: Script '$scriptName' not found in library. Use 'search' action to see available scripts."

    // -----------------------------------------------------------------
    //  Reverse-mistake guard: detect when the LLM put a tool_call JSON
    //  inside the `parameters` map (or one of its values) instead of
    //  passing the script's actual runtime arguments. Without this
    //  guard the script would receive the JSON string as a literal
    //  parameter and likely fail in a confusing way.
    // -----------------------------------------------------------------
    val mistakenlyWrappedTool = ToolCallParser.parametersLookLikeToolCall(parameters)
    if (mistakenlyWrappedTool != null) {
        return mapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "The 'parameters' of script_library action=run are passed to the " +
                "script as its runtime arguments (the `scriptArgs` variable inside the " +
                "script). One of the provided parameter values looks like a tool_call JSON " +
                "for '$mistakenlyWrappedTool' — the LLM is treating a script run as a tool call.",
            "hint" to "If you wanted to invoke '$mistakenlyWrappedTool', emit it as a separate " +
                "Action tool_call JSON block in the same response. Do NOT nest tool calls " +
                "inside the `parameters` object of script_library; those values are forwarded " +
                "to the script verbatim. Inspect the script's `// @parameters:` header to see " +
                "the keys it actually expects (e.g. {\"query\": \"...\"} or {\"target\": \"...\"}).",
            "remediation" to "emit '${mistakenlyWrappedTool}' as its own tool_call block"
        ))
    }

    // Inject parameters as scriptArgs by prepending a property to the source
    val sourceWithArgs = if (parameters.isNotEmpty()) {
        // Inject a scriptArgs map accessible within execute()
        val injection = buildString {
            val paramsJson = mapper.writeValueAsString(parameters)
            val paramsB64 = java.util.Base64.getEncoder()
                .encodeToString(paramsJson.toByteArray(Charsets.UTF_8))
            appendLine("// Auto-injected scriptArgs")
            appendLine("private val __akibaScriptArgsJson = String(java.util.Base64.getDecoder().decode(${kotlinLiteral(paramsB64)}), Charsets.UTF_8)")
            appendLine("val scriptArgs: Map<String, Any?> = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readValue(__akibaScriptArgsJson, object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {})")
        }
        // Insert the injection after the imports, before the class definition
        val classIdx = script.source.indexOf("class ${script.className}")
        if (classIdx >= 0) {
            script.source.substring(0, classIdx) + injection + "\n" + script.source.substring(classIdx)
        } else {
            injection + "\n" + script.source
        }
    } else {
        // Provide empty scriptArgs
        val classIdx = script.source.indexOf("class ${script.className}")
        val injection = "val scriptArgs: Map<String, Any?> = emptyMap()\n\n"
        if (classIdx >= 0) {
            script.source.substring(0, classIdx) + injection + script.source.substring(classIdx)
        } else {
            injection + script.source
        }
    }

    // Record execution in DB (best-effort)
    var executionId: Int? = null
    val scriptDbId: Int = script.dbId ?: try {
        // Fallback: if no dbId, look up the script by name from all scripts
        agentDbClient.listScripts(limit = 500).firstOrNull { it.name == script.name }?.id
            ?: agentDbClient.createScript(
                name = script.name, code = sourceWithArgs, maxOutputSize = 10 * 1024 * 1024
            )
    } catch (_: Exception) { -1 }
    if (scriptDbId >= 0) {
        try {
            executionId = agentDbClient.createScriptExecution(scriptDbId, parent.id)
            agentDbClient.updateScriptExecution(executionId, null, "running", null)
        } catch (_: Exception) { }
    }

    return try {
        var resultJson = ""
        val totalElapsed = measureTimeMillis {
            runBlocking {
                val instance = ScriptInstance.compile(sourceWithArgs, script.className)
                val scriptObj = instance.newInstance(
                    binaryId = parent.id,
                    program = parent.program,
                    skipDbWrite = true
                )
                scriptObj.startProcess(AkibaModule.DEFAULT_TIMEOUT)

                val report = scriptObj.runtimeReportView
                val success = scriptObj.failureSign == AkibaModule.SUCCESS

                // ── Result payload returned to the strategy ───────────────
                // IMPORTANT: do NOT include `executionTimeMs` / `totalTimeMs`
                // (or any other per-call non-deterministic field) in this
                // JSON.  The strategy's tool-result duplicate detector
                // ([DefaultToolResultDuplicateDetector]) hashes the ENTIRE
                // raw result string via SHA-256 and looks for byte-for-byte
                // identical previous results in the same session.  A
                // single-digit change in a timing field flips the hash and
                // silently defeats dedup, so the LLM can re-issue the same
                // `disassemble_function(address=X, ...)` call indefinitely
                // without ever seeing a `[Repeated tool result warning]`
                // prefix.
                //
                // Timing is still measured below (for [report] and
                // [totalElapsed]) and may be inspected via the
                // [ScriptInstance.runtimeReportView] / the surrounding
                // `measureTimeMillis` block, but it must NEVER enter the
                // returned [resultJson].  If you need to surface timing
                // for an audit, write it to a server-side log file --
                // never to the LLM-visible tool result.
                val scriptExecutionTimeMs =
                    report?.get(RuntimeReport.KEY_EXECUTION_TIME_MS) ?: 0L
                resultJson = mapper.writeValueAsString(mapOf(
                    "success" to success,
                    "scriptName" to script.name,
                    "output" to scriptObj.getOutput()
                ))
            }
        }
        // [totalElapsed] and the inner [scriptExecutionTimeMs] are
        // intentionally NOT patched back into [resultJson] -- see the
        // comment above the mapOf call.  They are kept in scope so a
        // future change can route them to a logger / metrics sink
        // without re-introducing the non-deterministic-field bug.
        @Suppress("UNUSED_VARIABLE")
        val _totalElapsedMs = totalElapsed

        val finalResult = resultJson

        val outputText = (mapper.readTree(finalResult)["output"]?.asText() ?: "").take(10000)

        // Update execution record and script output
        try {
            if (executionId != null) {
                agentDbClient.updateScriptExecution(executionId, outputText, "completed", null)
            }
        } catch (_: Exception) { }
        try {
            if (scriptDbId >= 0) {
                agentDbClient.updateScriptOutput(scriptDbId, outputText, "completed")
            }
        } catch (_: Exception) { }

        finalResult
    } catch (e: org.iotsplab.akiba.script.CompilationException) {
        try {
            if (executionId != null)
                agentDbClient.updateScriptExecution(executionId, null, "failed", "Compilation: ${e.message}")
            if (scriptDbId >= 0)
                agentDbClient.updateScriptOutput(scriptDbId, "Compilation: ${e.message}", "failed")
        } catch (_: Exception) { }
        "Error: library script '${script.name}' compilation failed: ${e.message}"
    } catch (e: Exception) {
        val detail = e.message ?: buildString {
            append(e.javaClass.name)
            val firstLine = e.stackTrace.firstOrNull()?.toString()
            if (firstLine != null) append(" at $firstLine")
        }
        try {
            if (executionId != null)
                agentDbClient.updateScriptExecution(executionId, null, "failed", "${e.javaClass.simpleName}: $detail")
            if (scriptDbId >= 0)
                agentDbClient.updateScriptOutput(scriptDbId, "Error: $detail", "failed")
        } catch (_: Exception) { }
        "Error running library script '${script.name}': $detail"
    }
}
