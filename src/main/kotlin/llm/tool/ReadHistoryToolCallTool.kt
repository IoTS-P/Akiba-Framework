package org.iotsplab.akiba.llm.tool

import org.iotsplab.akiba.data.database.AgentDatabaseClient

fun ReadHistoryToolCallTool(agentDbClient: AgentDatabaseClient): Tool = Tool(
    name = "read_history_tool_call",
    description = buildString {
        appendLine("Read a stored historical tool-call result by result UUID.")
        appendLine("Use this when an observation says its full stored result can be inspected with read_history_tool_call.")
        appendLine("Returns at most 40000 characters per call. Use offset/limit for paging or grep/around for targeted lookup.")
    },
    parameters = listOf(
        ToolParameter("uuid", "string", "The result_uuid shown in a tool observation.", required = false),
        ToolParameter("resultUuid", "string", "Alias for uuid.", required = false),
        ToolParameter("offset", "integer", "Character offset into the stored result. Default 0.", required = false),
        ToolParameter("limit", "integer", "Maximum characters to return, capped at 40000. Default 40000.", required = false),
        ToolParameter("grep", "string", "Optional case-insensitive text to search for; returns nearby lines instead of offset paging.", required = false),
        ToolParameter("around", "integer", "Number of context lines around a grep match. Default 3.", required = false)
    )
) { args ->
    val uuid = (args["uuid"] as? String)
        ?: (args["resultUuid"] as? String)
        ?: return@Tool "Tool argument error for 'read_history_tool_call': missing required parameter 'uuid'."
    val offset = (args["offset"] as? Number)?.toInt() ?: 0
    val limit = (args["limit"] as? Number)?.toInt() ?: 40000
    val grep = args["grep"] as? String
    val around = (args["around"] as? Number)?.toInt() ?: 3

    try {
        val result = agentDbClient.getToolCallResult(
            resultUuid = uuid,
            offset = offset,
            limit = limit,
            grep = grep,
            around = around
        )
        buildString {
            appendLine("=== Historical Tool Call Result ===")
            appendLine("result_uuid: ${result.resultUuid}")
            appendLine("tool: ${result.toolName}")
            appendLine("tool_call_id: ${result.toolCallId ?: "(none)"}")
            appendLine("created_at: ${result.createdAt ?: "(unknown)"}")
            appendLine("original_bytes: ${result.originalBytes}")
            appendLine("stored_bytes: ${result.storedBytes}")
            appendLine("storage_policy: ${result.storagePolicy ?: "(unknown)"}")
            appendLine("stored_result_truncated: ${result.truncated}")
            appendLine("sha256: ${result.sha256 ?: "(none)"}")
            if (grep.isNullOrBlank()) {
                appendLine("offset: ${result.offset}")
            } else {
                appendLine("grep: $grep")
            }
            appendLine("returned_chars: ${result.returnedChars}")
            appendLine()
            appendLine("--- BEGIN RESULT ---")
            append(result.content)
            if (!result.content.endsWith("\n")) appendLine()
            appendLine("--- END RESULT ---")
            if (grep.isNullOrBlank() && result.offset + result.returnedChars < result.storedChars) {
                appendLine("More content is available. Call read_history_tool_call with uuid=${result.resultUuid}, offset=${result.offset + result.returnedChars}.")
            }
        }
    } catch (e: Exception) {
        "Error reading historical tool call result: ${e.message ?: e.javaClass.simpleName}"
    }
}
