package org.iotsplab.akiba.llm.tool

import org.iotsplab.akiba.data.database.AgentDatabaseClient

fun SearchHistoryToolCallsTool(agentDbClient: AgentDatabaseClient): Tool = Tool(
    name = "search_history_tool_calls",
    description = buildString {
        appendLine("Search and list historical tool-call records by tool name, tool arguments text, or result UUID.")
        appendLine("Use this when the context is too long to find a specific past tool call, or when you need to")
        appendLine("locate a result_uuid that was mentioned in a compacted observation.")
        appendLine("Returns a summary list (UUID, tool name, args preview, timestamps) — NOT the full result content.")
        appendLine("Use read_history_tool_call with a result_uuid from the results to inspect the full stored content.")
        appendLine("At least one of toolName, toolArgsContains, or resultUuid must be provided.")
    },
    parameters = listOf(
        ToolParameter("toolName", "string", "Filter by tool name (exact, case-sensitive match, e.g. \"disassemble_function\").", required = false),
        ToolParameter("toolArgsContains", "string", "Case-insensitive substring search on the tool arguments JSON text (e.g. \"00117d0c\" or \"manage_func_signature\").", required = false),
        ToolParameter("resultUuid", "string", "Find a specific record by its result_uuid (exact match).", required = false),
        ToolParameter("limit", "integer", "Maximum number of results to return (default 20, max 100).", required = false)
    )
) { args ->
    val toolName = (args["toolName"] as? String)?.takeIf { it.isNotBlank() }
    val toolArgsContains = (args["toolArgsContains"] as? String)?.takeIf { it.isNotBlank() }
    val resultUuid = (args["resultUuid"] as? String)?.takeIf { it.isNotBlank() }
    val limit = (args["limit"] as? Number)?.toInt() ?: 20

    if (toolName == null && toolArgsContains == null && resultUuid == null) {
        return@Tool "Error: at least one search parameter (toolName, toolArgsContains, or resultUuid) is required."
    }

    try {
        val results = agentDbClient.searchToolCallResults(
            toolName = toolName,
            toolArgsContains = toolArgsContains,
            resultUuid = resultUuid,
            limit = limit
        )

        if (results.isEmpty()) {
            return@Tool "No matching tool call records found."
        }

        buildString {
            appendLine("=== Historical Tool Call Search Results (${results.size} match(es)) ===")
            appendLine()
            for (r in results) {
                appendLine("result_uuid: ${r.resultUuid}")
                appendLine("  tool: ${r.toolName}")
                val argsPreview = (r.toolArgs ?: "").take(200)
                val argsEllipsis = if ((r.toolArgs ?: "").length > 200) "..." else ""
                appendLine("  tool_args: $argsPreview$argsEllipsis")
                appendLine("  original_bytes: ${r.originalBytes}  stored_bytes: ${r.storedBytes}  truncated: ${r.truncated}")
                appendLine("  created_at: ${r.createdAt ?: "(unknown)"}")
                appendLine("  session_id: ${r.sessionId}")
                appendLine()
            }
            appendLine("Use read_history_tool_call with a result_uuid above to inspect the full stored result.")
        }
    } catch (e: Exception) {
        "Error searching tool call history: ${e.message ?: e.javaClass.simpleName}"
    }
}
