package org.iotsplab.akiba.llm.tool.analysis

import kotlinx.coroutines.runBlocking
import org.iotsplab.akiba.llm.tool.core.Tool
import org.iotsplab.akiba.llm.tool.core.ToolParameter
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.module.RuntimeReport
import org.iotsplab.akiba.utils.AkibaJson

/**
 * Identify a binary's architecture from its file header.
 *
 * Import records only the cheap facts (checksum, size, one copy), so `arch` and
 * `format` start out empty and analysis tools refuse to run until they are
 * filled in. This tool runs the `AkibaArch` module, which reads the ELF, PE or
 * Mach-O header and writes both columns back.
 *
 * It is separate from [RunModuleTool] on purpose: that tool resolves a Ghidra
 * program for the target first, and a binary with no architecture has no program
 * to resolve — which is exactly the case this tool exists for.
 */
fun IdentifyArchitectureTool(parent: AkibaModule): Tool = Tool(
    name = "identify_architecture",
    description = buildString {
        appendLine("Identify the architecture and format of a binary from its file header.")
        appendLine("Run this before any analysis tool when a binary reports an unknown architecture.")
        appendLine("It reads only the header, so it is cheap and can be run on many binaries.")
        appendLine("Returns the identified arch/format, or an explanation when the header is not recognised.")
    },
    parameters = listOf(
        ToolParameter(
            "binaryId", "integer",
            "Binary ID to identify. Defaults to the binary the current task is working on.",
            required = false
        )
    ),
    execute = { args -> runBlocking {
        val targetId = (args["binaryId"] as? Number)?.toInt() ?: parent.id
        if (parent.skipDbWrite) {
            AkibaJson.mapper.writeValueAsString(
                mapOf("success" to false, "error" to "This task runs without database writes")
            )
        } else {
            val module = try {
                parent.callModule(
                    program = null,
                    mainClassName = "org.iotsplab.akiba.process.AkibaArch",
                    targetId = targetId,
                )
            } catch (e: Exception) {
                return@runBlocking AkibaJson.mapper.writeValueAsString(
                    mapOf("success" to false, "error" to "Arch identification failed: ${e.message}")
                )
            }
            val success = module.failureSign == AkibaModule.SUCCESS
            AkibaJson.mapper.writeValueAsString(
                mapOf(
                    "success" to success,
                    "binaryId" to targetId,
                    "error" to if (success) null else
                        (module.runtimeReportView?.get(RuntimeReport.KEY_ERR_MSG) as? String
                            ?: "Header not recognised"),
                )
            )
        } }
    },
)
