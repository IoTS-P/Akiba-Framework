package org.iotsplab.akiba.llm.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.iotsplab.akiba.utils.ProcedureArgumentsDeserializer

/**
 * Create a tool that lists all available AkibaModule classes that
 * can be invoked via `run_module`.
 *
 * The tool reads the module registry ([ProcedureArgumentsDeserializer.allModules])
 * which is populated at startup by scanning the `modules/` directory for jar
 * files with a `Main-Class` manifest attribute.
 *
 * Returns a JSON array of objects, each containing:
 * - `className` — the fully-qualified class name
 * - `jarFile` — the jar file that provides this module
 */
fun ListModulesTool(): Tool = Tool(
    name = "list_modules",
    description = buildString {
        appendLine("List all available analysis modules that can be invoked via `run_module`.")
        appendLine("Each entry includes the fully-qualified class name and the source jar file.")
        appendLine("Use this to discover which modules are available before calling `run_module`.")
    },
    parameters = emptyList()
) {
    val mapper = jacksonObjectMapper()

    try {
        val modules = ProcedureArgumentsDeserializer.allModules.map { (className, path) ->
            mapOf(
                "className" to className,
                "jarFile" to path.fileName.toString()
            )
        }
        mapper.writeValueAsString(modules)
    } catch (e: Exception) {
        "Error listing modules: ${e.message}"
    }
}
