package org.iotsplab.akiba.llm.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.iotsplab.akiba.data.database.DatabaseClient
import org.iotsplab.akiba.llm.agent.Tool
import org.iotsplab.akiba.llm.agent.ToolParameter
import org.iotsplab.akiba.module.AkibaModule

/**
 * Create a tool that queries the analysis results stored by
 * previously-run modules for the current binary.
 *
 * The agent specifies a table name (corresponding to a module's
 * result table) and optionally a list of columns. The tool returns
 * the matching rows as a JSON array.
 */
fun QueryModuleDataTool(parent: AkibaModule): Tool = Tool(
    name = "query_module_data",
    description = buildString {
        appendLine("Query analysis results from the database for the current binary.")
        appendLine("Specify a module result table name (e.g. 'decompile_results') and ")
        appendLine("optionally the columns you need. Returns rows as a JSON array.")
        appendLine("Use this to retrieve results from modules that have already run.")
    },
    parameters = listOf(
        ToolParameter(
            "tableName", "string",
            "Name of the module result table to query, e.g. 'decompile_results'.",
            required = true
        ),
        ToolParameter(
            "columns", "string",
            "Comma-separated list of column names to retrieve. " +
                "If omitted, all columns are returned.",
            required = false
        )
    )
) { args ->
    val tableName = args["tableName"] as? String
        ?: return@Tool "Error: 'tableName' parameter is required"

    val columnsStr = args["columns"] as? String
    val columns = columnsStr?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }

    val mapper = jacksonObjectMapper()

    try {
        val data = DatabaseClient.getModuleData(
            id = parent.id.toLong(),
            tableName = tableName,
            columns = columns
        )
        mapper.writeValueAsString(data)
    } catch (e: DatabaseClient.DatabaseDaemonException) {
        "Error querying table '$tableName': ${e.statusMsg ?: e.statusCode?.description}"
    } catch (e: Exception) {
        "Error querying table '$tableName': ${e.message}"
    }
}
