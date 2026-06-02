package org.iotsplab.akiba.llm.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.runBlocking
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.agent.Tool
import org.iotsplab.akiba.llm.agent.ToolParameter
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
fun ScriptLibraryTool(parent: AkibaModule): Tool = Tool(
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
        appendLine()
        appendLine("Available scripts include: binary_info, list_functions, decompile_function,")
        appendLine("find_dangerous_calls, list_strings, get_xrefs, and more.")
        appendLine()
        appendLine("Example usage:")
        appendLine("  search: {\"action\":\"search\", \"keyword\":\"\"} → list all scripts")
        appendLine("  search: {\"action\":\"search\", \"keyword\":\"decompile\"} → find decompile-related scripts")
        appendLine("  read:   {\"action\":\"read\", \"scriptName\":\"decompile_function\"} → return its full source")
        appendLine("  run:    {\"action\":\"run\", \"scriptName\":\"decompile_function\", \"parameters\":{\"target\":\"main\"}}")
        appendLine("  run:    {\"action\":\"run\", \"scriptName\":\"list_functions\"}")
        appendLine()
        appendLine("CRITICAL: 'parameters' MUST be a nested JSON object, NOT a string!")
        appendLine("  ✅ CORRECT: \"parameters\": {\"target\": \"main\", \"direction\": \"to\"}")
        appendLine("  ❌ WRONG:   \"parameters\": \"{\\\"target\\\": \\\"main\\\"}\"")
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
            "For 'run': optional parameters to pass to the script (as JSON object).",
            required = false
        )
    )
) { args ->
    val mapper = jacksonObjectMapper()
    val action = args["action"] as? String
        ?: return@Tool "Error: 'action' parameter is required ('search' or 'run')"

    when (action) {
        "search" -> {
            val keyword = (args["keyword"] as? String)?.trim() ?: ""
            searchScripts(keyword)
        }
        "read" -> {
            val scriptName = args["scriptName"] as? String
                ?: return@Tool "Error: 'scriptName' parameter is required for 'read' action"
            readScript(scriptName)
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
            runLibraryScript(parent, scriptName, parameters, mapper)
        }
        else -> "Error: unknown action '$action'. Use 'search', 'read', or 'run'."
    }
}

// ============================================================
//  Script metadata and library management
// ============================================================

/**
 * Metadata parsed from script file headers.
 */
private data class ScriptMeta(
    val name: String,
    val description: String,
    val author: String,
    val parameters: String,
    val source: String,
    val className: String
)

/** Cached script library entries. */
@Volatile
private var scriptLibrary: List<ScriptMeta>? = null

/**
 * Load user-saved library scripts from the `scripts` table.
 */
private fun loadDbLibraryScripts(): List<ScriptMeta> {
    return try {
        val allScripts = AgentDatabaseClient.listScripts(limit = 500)
        allScripts.mapNotNull { info ->
            val source = AgentDatabaseClient.getScript(info.id).code ?: return@mapNotNull null
            val displayName = info.name
            val classNameRegex = Regex("""class\s+(\w+)\s*""")
            val className = classNameRegex.find(source)?.groupValues?.get(1) ?: return@mapNotNull null

            // Try to parse annotations from source, fallback to DB fields
            val meta = parseScriptMeta(source, "$displayName.kts")
            meta?.copy(
                name = displayName,
                description = meta.description.ifBlank { (info.description ?: "") },
                author = meta.author.ifBlank { (info.author ?: "") }
            ) ?: ScriptMeta(
                name = displayName,
                description = info.description ?: "",
                author = info.author ?: "",
                parameters = "none",
                source = source,
                className = className
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Parse metadata annotations from script source.
 */
private fun parseScriptMeta(source: String, fileName: String): ScriptMeta? {
    var name = fileName.removeSuffix(".kts")
    var description = ""
    var author = ""
    var parameters = "none"

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
        }
    }

    // Extract class name from the source
    val classNameRegex = Regex("""class\s+(\w+)\s*""")
    val className = classNameRegex.find(source)?.groupValues?.get(1) ?: return null

    return ScriptMeta(name, description, author, parameters, source, className)
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
private fun readScript(scriptName: String): String {
    val script = loadDbLibraryScripts().firstOrNull { it.name == scriptName }
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

private fun searchScripts(keyword: String): String {
    // Also load user-saved scripts from the database
    val allScripts = loadDbLibraryScripts()

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

private fun runLibraryScript(
    parent: AkibaModule,
    scriptName: String,
    parameters: Map<String, Any?>,
    mapper: com.fasterxml.jackson.databind.ObjectMapper
): String {
    val script = loadDbLibraryScripts().firstOrNull { it.name == scriptName }
        ?: return "Error: Script '$scriptName' not found in library. Use 'search' action to see available scripts."

    // Inject parameters as scriptArgs by prepending a property to the source
    val sourceWithArgs = if (parameters.isNotEmpty()) {
        // Inject a scriptArgs map accessible within execute()
        val injection = buildString {
            appendLine("// Auto-injected scriptArgs")
            appendLine("val scriptArgs: Map<String, Any?> = mapOf(")
            parameters.entries.forEachIndexed { i, (k, v) ->
                val valueStr = when (v) {
                    is String -> "\"${v.replace("\"", "\\\"")}\""
                    null -> "null"
                    else -> v.toString()
                }
                append("    \"$k\" to $valueStr")
                if (i < parameters.size - 1) append(",")
                appendLine()
            }
            appendLine(")")
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
    try {
        val scriptId = AgentDatabaseClient.createScript(
            name = script.name,
            code = sourceWithArgs,
            saveResult = false,
            maxOutputSize = 10 * 1024 * 1024
        )
        executionId = AgentDatabaseClient.createScriptExecution(scriptId, parent.id)
        AgentDatabaseClient.updateScriptExecution(executionId, null, "running", null)
    } catch (_: Exception) { }

    return try {
        var resultJson = ""
        val totalElapsed = measureTimeMillis {
            runBlocking {
                val instance = ScriptInstance.compile(sourceWithArgs, script.className)
                val scriptObj = instance.newInstance(
                    binaryId = parent.id,
                    program = parent.currentProgram,
                    skipDbWrite = true
                )
                scriptObj.startProcess(AkibaModule.DEFAULT_TIMEOUT)

                val report = scriptObj.runtimeReportView
                val success = scriptObj.failureSign == AkibaModule.SUCCESS

                resultJson = mapper.writeValueAsString(mapOf(
                    "success" to success,
                    "scriptName" to script.name,
                    "output" to scriptObj.getOutput(),
                    "executionTimeMs" to (report?.get(RuntimeReport.KEY_EXECUTION_TIME_MS) ?: 0L)
                ))
            }
        }

        // Patch totalTimeMs
        val finalResult = try {
            val node = mapper.readTree(resultJson)
            (node as? com.fasterxml.jackson.databind.node.ObjectNode)?.put("totalTimeMs", totalElapsed)
            node?.toString() ?: resultJson
        } catch (_: Exception) { resultJson }

        // Update DB record
        try {
            if (executionId != null) {
                val output = (mapper.readTree(finalResult)["output"]?.asText() ?: "").take(10000)
                AgentDatabaseClient.updateScriptExecution(executionId, output, "completed", null)
            }
        } catch (_: Exception) { }

        finalResult
    } catch (e: org.iotsplab.akiba.script.CompilationException) {
        try {
            if (executionId != null)
                AgentDatabaseClient.updateScriptExecution(executionId, null, "failed", "Compilation: ${e.message}")
        } catch (_: Exception) { }
        "Error: library script '${script.name}' compilation failed: ${e.message}"
    } catch (e: Exception) {
        try {
            if (executionId != null)
                AgentDatabaseClient.updateScriptExecution(executionId, null, "failed", "${e.javaClass.simpleName}: ${e.message}")
        } catch (_: Exception) { }
        "Error running library script '${script.name}': ${e.message}"
    }
}
