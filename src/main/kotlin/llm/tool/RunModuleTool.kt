package org.iotsplab.akiba.llm.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.runBlocking
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.module.RuntimeReport
import java.io.PrintWriter
import java.io.StringWriter
import java.util.HashSet
import kotlin.system.measureTimeMillis

/**
 * Create a tool that runs another [AkibaModule] on the current or a different binary.
 *
 * The agent provides the fully-qualified class name of the target module,
 * an optional target binary id (defaults to the current binary), and an
 * optional JSON config snippet. The module is invoked via
 * [AkibaModule.callModule], which handles classpath resolution,
 * lifecycle, and result collection.
 *
 * If [targetId] is specified and differs from the current module's [id],
 * the tool will automatically resolve the corresponding Ghidra [Program]
 * from the project using [AkibaModule.getProgram].
 *
 * The returned string is a JSON object containing:
 * - `success` — whether the module finished without errors
 * - `failureSign` — the module's [AkibaModule.failureSign] value
 * - `data` — the module's [RuntimeReport] data (if any)
 * - `executionTimeMs` — wall-clock time in milliseconds
 */
fun RunModuleTool(parent: AkibaModule): Tool = Tool(
    name = "run_module",
    description = buildString {
        appendLine("Run another analysis module and return its result.")
        appendLine("The module must be a fully-qualified class name of an AkibaModule subclass.")
        appendLine("You can optionally pass a target binary ID to run the module on a different binary,")
        appendLine("and a JSON config snippet to configure the module.")
        appendLine("Use this to delegate specialized analysis tasks (e.g. decompilation, ")
        appendLine("string extraction, vulnerability scanning) to purpose-built modules.")
    },
    parameters = listOf(
        ToolParameter(
            "className", "string",
            "Fully-qualified class name of the AkibaModule to run, e.g. 'com.example.DecompileAgent'",
            required = true
        ),
        ToolParameter(
            "targetId", "integer",
            "Binary ID to run the module on. Defaults to the current binary.",
            required = false
        ),
        ToolParameter(
            "configJson", "string",
            "Optional JSON config string for the called module. Must match the module's @WithConfigClass schema.",
            required = false
        ),
        ToolParameter(
            "timeout", "integer",
            "Timeout in seconds for the module execution. Default: 180.",
            required = false
        ),
        ToolParameter(
            "skipDbWrite", "boolean",
            "If true, the module will only write results to the in-memory runtime report " +
                "and skip all database operations. Useful when you only need the result returned " +
                "and don't want database side-effects. Default: false.",
            required = false
        )
    )
) { args ->
    val className = args["className"] as? String
        ?: return@Tool "Error: 'className' parameter is required"

    val targetId = (args["targetId"] as? Number)?.toInt() ?: parent.id
    val configJson = args["configJson"] as? String
    val timeout = (args["timeout"] as? Number)?.toInt() ?: AkibaModule.DEFAULT_TIMEOUT
    val skipDbWrite = args["skipDbWrite"] as? Boolean ?: false

    val mapper = jacksonObjectMapper()

    try {
        var resultJson = ""
        measureTimeMillis {
            runBlocking {
                // Resolve the program for the target binary
                val targetProgram = if (targetId == parent.id) {
                    parent.program ?: parent.getProgram(targetId)
                } else {
                    parent.getProgram(targetId)
                        ?: return@runBlocking run {
                            resultJson = mapper.writeValueAsString(mapOf(
                                "success" to false,
                                "error" to "Program not found for binary id=$targetId"
                            ))
                        }
                }

                val instance = parent.callModule(
                    program = targetProgram,
                    mainClassName = className,
                    configJson = configJson,
                    targetId = targetId,
                    timeout = timeout,
                    skipDbWrite = skipDbWrite
                )

                val report = instance.runtimeReportView
                val success = instance.failureSign == AkibaModule.SUCCESS
                @Suppress("UNCHECKED_CAST")
                val data = report?.get(RuntimeReport.KEY_DATA) as? Map<String, Any?>
                val errMsg = report?.get(RuntimeReport.KEY_ERR_MSG) as? String
                val traceback = report?.get(RuntimeReport.KEY_TRACEBACK) as? String

                resultJson = mapper.writeValueAsString(mapOf(
                    "success" to success,
                    "failureSign" to instance.failureSign,
                    "error" to if (success) null else (errMsg ?: "Module finished with failureSign=${instance.failureSign} but did not report an error message"),
                    "traceback" to traceback,
                    "data" to data,
                    "executionTimeMs" to report?.get(RuntimeReport.KEY_EXECUTION_TIME_MS)
                ))
            }
        }
        resultJson
    } catch (e: ClassNotFoundException) {
        mapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Module class '$className' not found. Ensure the module jar is in the modules/ directory.",
            "exceptionType" to e.javaClass.name,
            "traceback" to tracebackOf(e)
        ))
    } catch (e: IllegalArgumentException) {
        mapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to describeThrowable(e),
            "exceptionType" to rootCause(e).javaClass.name,
            "traceback" to tracebackOf(e)
        ))
    } catch (e: LinkageError) {
        mapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Error loading module '$className': ${describeThrowable(e)}",
            "exceptionType" to rootCause(e).javaClass.name,
            "traceback" to tracebackOf(e)
        ))
    } catch (e: Exception) {
        mapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Error running module '$className': ${describeThrowable(e)}",
            "exceptionType" to rootCause(e).javaClass.name,
            "traceback" to tracebackOf(e)
        ))
    }
}

private fun rootCause(t: Throwable): Throwable {
    var current = t
    val seen = HashSet<Throwable>()
    while (current.cause != null && current.cause !in seen) {
        seen.add(current)
        current = current.cause!!
    }
    return current
}

private fun describeThrowable(t: Throwable): String {
    val root = rootCause(t)
    val message = root.message?.takeIf { it.isNotBlank() }
        ?: t.message?.takeIf { it.isNotBlank() }
    return if (message != null) {
        "${root.javaClass.simpleName}: $message"
    } else {
        root.javaClass.name
    }
}

private fun tracebackOf(t: Throwable): String {
    val sw = StringWriter()
    t.printStackTrace(PrintWriter(sw))
    return sw.toString()
}
