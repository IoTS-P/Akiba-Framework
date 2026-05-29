package org.iotsplab.akiba.llm.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.runBlocking
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.agent.Tool
import org.iotsplab.akiba.llm.agent.ToolParameter
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.module.RuntimeReport
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
fun RunScriptTool(parent: AkibaModule): Tool = Tool(
    name = "run_script",
    description = buildString {
        appendLine("Compile and run a Kotlin script that analyzes the current binary.")
        appendLine("The script must define a class extending AkibaScript with an execute() method.")
        appendLine("Inside execute(), you can use currentProgram to access the Ghidra Program,")
        appendLine("appendOutput()/appendLine() to produce text output, and updateData() for structured results.")
        appendLine("Each run uses an isolated ClassLoader, so class name conflicts are avoided.")
        appendLine("Use this for one-off analysis tasks that don't warrant a full module.")
    },
    parameters = listOf(
        ToolParameter(
            "source", "string",
            "Full Kotlin source code of the script. Must define a class extending AkibaScript.",
            required = true
        ),
        ToolParameter(
            "className", "string",
            "Simple class name for the script. Defaults to 'AkibaDynamicScript'.",
            required = false
        ),
        ToolParameter(
            "targetId", "integer",
            "Binary ID to run the script on. Defaults to the current binary.",
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

    val mapper = jacksonObjectMapper()

    // 1. Validate
    val validationIssues = ScriptCompiler.validate(source)
    if (validationIssues.isNotEmpty()) {
        return@Tool "Error: script validation failed: ${validationIssues.joinToString("; ")}"
    }

    // 2. Resolve program
    val targetProgram = if (targetId == parent.id) {
        parent.currentProgram
    } else {
        parent.getProgram(targetId)
            ?: return@Tool mapper.writeValueAsString(mapOf(
                "success" to false,
                "error" to "Program not found for binary id=$targetId"
            ))
    }

    // 3. Compile, load, run
    try {
        var resultJson = ""

        // 3a. Record the script execution in the per-instance DB
        var executionId: Int? = null
        try {
            val scriptId = AgentDatabaseClient.createScript(
                name = className,
                code = source,
                saveResult = false,
                maxOutputSize = 10 * 1024 * 1024
            )
            executionId = AgentDatabaseClient.createScriptExecution(scriptId, targetId)
            AgentDatabaseClient.updateScriptExecution(executionId, null, "running", null)
        } catch (_: Exception) {
            // Recording is best-effort; don't block execution if it fails
        }

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
            (node as? com.fasterxml.jackson.databind.node.ObjectNode)?.put("totalTimeMs", totalElapsed)
            node?.toString() ?: resultJson
        } catch (_: Exception) {
            resultJson
        }

        // 3b. Update the execution record
        try {
            if (executionId != null) {
                val output = (mapper.readTree(finalResult)["output"]?.asText() ?: "").take(10000)
                AgentDatabaseClient.updateScriptExecution(
                    executionId, output, "completed", null
                )
            }
        } catch (_: Exception) { }

        finalResult
    } catch (e: org.iotsplab.akiba.script.CompilationException) {
        "Error: script compilation failed: ${e.message}"
    } catch (e: IllegalStateException) {
        "Error: script instantiation failed: ${e.message}"
    } catch (e: Exception) {
        "Error running script: ${e.message}"
    }
}
