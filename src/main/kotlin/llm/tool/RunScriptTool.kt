package org.iotsplab.akiba.llm.tool

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.runBlocking
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.module.RuntimeReport
import org.iotsplab.akiba.script.CompilationException
import org.iotsplab.akiba.script.ScriptCompiler
import org.iotsplab.akiba.script.ScriptInstance
import kotlin.system.measureTimeMillis

/**
 * Create a tool that dynamically compiles and runs a Kotlin script.
 *
 * The agent provides the full Kotlin source code of a class that extends
 * `AkibaScript`. The tool compiles the code at runtime, loads it in an
 * **isolated ClassLoader**, instantiates it, runs [AkibaScript.execute],
 * and returns the output and any structured data from the RuntimeReport.
 *
 * ### ClassLoader isolation
 *
 * Each invocation creates a fresh [ScriptInstance] with its own
 * [ScriptClassLoader][org.iotsplab.akiba.script.ScriptClassLoader], so
 * successive calls with the same class name do not conflict and the
 * script can be "hot-reloaded" without restarting the agent.
 *
 * ### Script requirements
 *
 * The source code must define a **single class** that extends
 * `AkibaScript` and overrides `execute()`. Example:
 *
 * ```kotlin
 * import org.iotsplab.akiba.script.AkibaScript
 *
 * class AnalyzeStrings : AkibaScript() {
 *     override suspend fun execute() {
 *         val program = currentProgram
 *         if (program == null) {
 *             appendLine("No program loaded")
 *             return
 *         }
 *         val listing = program.listing
 *         val iter = listing.getDefinedStrings(true)
 *         while (iter.hasNext()) {
 *             appendLine(iter.next().value)
 *         }
 *     }
 * }
 * ```
 */
fun RunScriptTool(parent: AkibaModule, agentDbClient: AgentDatabaseClient): Tool = Tool(
    name = "run_script",
    description = buildString {
        appendLine("Compile and run a Kotlin script that analyzes the current binary loaded in Ghidra.")
        appendLine("The script must define a class extending AkibaScript with an execute() method.")
        appendLine("Inside execute(), use `currentProgram` to access the Ghidra Program object,")
        appendLine("`appendOutput()`/`appendLine()` to produce text output, and `updateData()` for structured results.")
        appendLine("Each run uses an isolated ClassLoader, so class name conflicts are avoided.")
        appendLine()
        appendLine("IMPORTANT: Scripts are written in Kotlin, NOT Java or Jython.")
        appendLine("- `currentProgram` is the loaded Ghidra Program (same as `program` in standard Ghidra scripts)")
        appendLine("- All Ghidra API packages (ghidra.program.model.*, ghidra.util.*, etc.) are available")
        appendLine("- Use `appendLine(text)` instead of `println()` — println output is not captured")
        appendLine("- The execute() method is `suspend` but you can call blocking Ghidra APIs directly")
        appendLine("- IMPORTANT: You MUST NOT write scripts that invoke the decompiler (DecompInterface,")
        appendLine("  FlatDecompilerAPI, ghidra.app.decompiler.*) before the target function has been")
        appendLine("  disassembled via `disassemble_function`. Decompilation is ONLY allowed AFTER disassembly.")
        appendLine()
        appendLine("=== EXAMPLE 1: List all functions ===")
        appendLine("```kotlin")
        appendLine("import org.iotsplab.akiba.script.AkibaScript")
        appendLine("")
        appendLine("class ListFunctions : AkibaScript() {")
        appendLine("    override suspend fun execute() {")
        appendLine("        val fm = currentProgram!!.functionManager")
        appendLine("        val iter = fm.getFunctions(true)")
        appendLine("        var count = 0")
        appendLine("        while (iter.hasNext()) {")
        appendLine("            val func = iter.next()")
        appendLine($$"            appendLine(\"${func.name} @ ${func.entryPoint}\")")
        appendLine("            count++")
        appendLine("        }")
        appendLine($$"        appendLine(\"Total: $count functions\")")
        appendLine("    }")
        appendLine("}")
        appendLine("```")
        appendLine()
        appendLine("=== EXAMPLE 2: Disassemble a function ===")
        appendLine("```kotlin")
        appendLine("import org.iotsplab.akiba.script.AkibaScript")
        appendLine("import ghidra.program.model.lang.Instruction")
        appendLine("")
        appendLine("class DisassembleMain : AkibaScript() {")
        appendLine("    override suspend fun execute() {")
        appendLine("        val listing = currentProgram!!.listing")
        appendLine("        val func = currentProgram!!.functionManager.getFunctionAt(")
        appendLine("            currentProgram!!.minAddress")
        appendLine("        ) ?: run { appendLine(\"No function at min address\"); return }")
        appendLine("        val iter = listing.getInstructions(func.body, true)")
        appendLine("        while (iter.hasNext()) {")
        appendLine("            val inst = iter.next()")
        appendLine($$"            appendLine(\"${inst.address}  ${inst.mnemonicString}  ${inst.defaultOperandRepresentation}\")")
        appendLine("        }")
        appendLine("    }")
        appendLine("}")
        appendLine("```")
        appendLine()
        appendLine("=== EXAMPLE 3: Find calls to dangerous functions ===")
        appendLine("```kotlin")
        appendLine("import org.iotsplab.akiba.script.AkibaScript")
        appendLine("import ghidra.program.model.symbol.ReferenceManager")
        appendLine("")
        appendLine("class FindDangerousCalls : AkibaScript() {")
        appendLine("    override suspend fun execute() {")
        appendLine("        val dangerousFns = listOf(\"gets\", \"strcpy\", \"sprintf\", \"strcat\", \"scanf\")")
        appendLine("        val fm = currentProgram!!.functionManager")
        appendLine("        val iter = fm.getFunctions(true)")
        appendLine("        while (iter.hasNext()) {")
        appendLine("            val func = iter.next()")
        appendLine("            if (func.name in dangerousFns) {")
        appendLine("                val refs = currentProgram!!.referenceManager")
        appendLine("                    .getReferencesTo(func.entryPoint)")
        appendLine($$"                appendLine(\"${func.name} @ ${func.entryPoint} — called from:\")")
        appendLine("                refs.forEach { ref ->")
        appendLine("                    val caller = fm.getFunctionContaining(ref.fromAddress)")
        appendLine($$"                    appendLine(\"  ${caller?.name ?: \"unknown\"} @ ${ref.fromAddress}\")")
        appendLine("                }")
        appendLine("            }")
        appendLine("        }")
        appendLine("    }")
        appendLine("}")
        appendLine("```")
    },
    parameters = listOf(
        ToolParameter(
            "source", "string",
            "Full Kotlin source code of the script. Must define a class extending AkibaScript " +
                "with `override suspend fun execute()`. Use appendLine() for output.",
            required = true
        ),
        ToolParameter(
            "className", "string",
            "Simple class name for the script (must match the class name in source). " +
                "Defaults to 'AkibaDynamicScript'.",
            required = false
        ),
        ToolParameter(
            "targetId", "integer",
            "Binary ID to run the script on. Defaults to the current binary.",
            required = false
        ),
        ToolParameter(
            "saveToLibrary", "boolean",
            "If true, save this script to the script library for future reuse (author='LLM Agent'). " +
                "Only set this to true when the script runs successfully and performs a useful, reusable task. " +
                "Note: a script can only be overwritten by the same author. You cannot overwrite scripts authored by 'Akiba'. " +
                "Defaults to false.",
            required = false
        ),
        ToolParameter(
            "description", "string",
            "Description of what the script does. Required when saveToLibrary is true.",
            required = false
        )
    )
) { args ->
    val source = args["source"] as? String
        ?: return@Tool "Error: 'source' parameter is required"

    if (source.isBlank()) {
        return@Tool "Error: source code is empty"
    }

    val className = args["className"] as? String ?: "AkibaDynamicScript"
    val targetId = (args["targetId"] as? Number)?.toInt() ?: parent.id
    val saveToLibrary = args["saveToLibrary"] as? Boolean ?: false
    val scriptDescription = args["description"] as? String ?: ""

    val mapper = jacksonObjectMapper()

    // 1. Record the script in the per-instance DB (best-effort, before any processing)
    var executionId: Int? = null
    try {
        val scriptId = agentDbClient.createScript(
            name = className,
            code = source,
            saveResult = false,
            maxOutputSize = 10 * 1024 * 1024
        )
        executionId = agentDbClient.createScriptExecution(scriptId, targetId)
        agentDbClient.updateScriptExecution(executionId, null, "running", null)
    } catch (_: Exception) {
        // Recording is best-effort; don't block execution if it fails
    }

    // 2. Validate
    val validationIssues = ScriptCompiler.validate(source)
    if (validationIssues.isNotEmpty()) {
        val errorMsg = "Validation failed: ${validationIssues.joinToString("; ")}"
        try {
            if (executionId != null) {
                agentDbClient.updateScriptExecution(executionId, null, "failed", errorMsg)
            }
        } catch (_: Exception) { }
        return@Tool "Error: script validation failed: ${validationIssues.joinToString("; ")}"
    }

    // 3. Resolve program
    val targetProgram = if (targetId == parent.id) {
        parent.currentProgram
    } else {
        parent.getProgram(targetId)
            ?: run {
                try {
                    if (executionId != null) {
                        agentDbClient.updateScriptExecution(
                            executionId, null, "failed", "Program not found for binary id=$targetId"
                        )
                    }
                } catch (_: Exception) { }
                return@Tool mapper.writeValueAsString(mapOf(
                    "success" to false,
                    "error" to "Program not found for binary id=$targetId"
                ))
            }
    }

    // 4. Compile, load, run
    try {
        var resultJson = ""

        val totalElapsed = measureTimeMillis {
            runBlocking {
                val instance = ScriptInstance.compile(source, className)

                val script = instance.newInstance(
                    binaryId = targetId,
                    program = targetProgram,
                    skipDbWrite = true
                )

                // Run with timeout
                script.startProcess(AkibaModule.DEFAULT_TIMEOUT)

                val report = script.runtimeReportView
                val success = script.failureSign == AkibaModule.SUCCESS
                @Suppress("UNCHECKED_CAST")
                val data = report?.get(RuntimeReport.KEY_DATA) as? Map<String, Any?>

                resultJson = mapper.writeValueAsString(mapOf(
                    "success" to success,
                    "failureSign" to script.failureSign,
                    "output" to script.getOutput(),
                    "data" to data,
                    "executionTimeMs" to report?.get(RuntimeReport.KEY_EXECUTION_TIME_MS),
                    "totalTimeMs" to 0L  // placeholder, overwritten below
                ))
            }
        }
        // Patch the totalTimeMs into the result
        val finalResult = try {
            val node = mapper.readTree(resultJson)
            (node as? ObjectNode)?.put("totalTimeMs", totalElapsed)
            node?.toString() ?: resultJson
        } catch (_: Exception) {
            resultJson
        }

        // Update execution record as completed
        try {
            if (executionId != null) {
                val output = (mapper.readTree(finalResult)["output"]?.asText() ?: "").take(10000)
                agentDbClient.updateScriptExecution(
                    executionId, output, "completed", null
                )
            }
        } catch (_: Exception) { }

        // Save to script library if requested and execution was successful
        if (saveToLibrary) {
            try {
                val resultNode = mapper.readTree(finalResult)
                val success = resultNode["success"]?.asBoolean() ?: false
                if (success) {
                    agentDbClient.createScript(
                        name = className,
                        description = scriptDescription,
                        author = "LLM Agent",
                        code = source,
                        language = "kotlin",
                        saveResult = true,
                        maxOutputSize = 10 * 1024 * 1024
                    )
                }
            } catch (_: Exception) { }
        }

        finalResult
    } catch (e: CompilationException) {
        // Update execution record with compilation error
        try {
            if (executionId != null) {
                agentDbClient.updateScriptExecution(
                    executionId, null, "failed", "Compilation error: ${e.message}"
                )
            }
        } catch (_: Exception) { }
        "Error: script compilation failed: ${e.message}"
    } catch (e: IllegalStateException) {
        // Update execution record with instantiation error
        try {
            if (executionId != null) {
                agentDbClient.updateScriptExecution(
                    executionId, null, "failed", "Instantiation error: ${e.message}"
                )
            }
        } catch (_: Exception) { }
        "Error: script instantiation failed: ${e.message}"
    } catch (e: Exception) {
        // Update execution record with runtime error
        try {
            if (executionId != null) {
                val errorMsg = "${e.javaClass.simpleName}: ${e.message}"
                agentDbClient.updateScriptExecution(
                    executionId, null, "failed", errorMsg
                )
            }
        } catch (_: Exception) { }
        "Error running script: ${e.message}"
    }
}
