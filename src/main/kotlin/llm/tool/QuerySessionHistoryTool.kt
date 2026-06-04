package org.iotsplab.akiba.llm.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.iotsplab.akiba.data.database.AgentDatabaseClient

/**
 * Create a tool that retrieves the conversation history of an
 * agent session from the database.
 *
 * Useful for agents that need to review what happened in previous
 * analysis sessions for the same binary.
 */
fun QuerySessionHistoryTool(agentDbClient: AgentDatabaseClient): Tool = Tool(
    name = "query_session_history",
    description = buildString {
        appendLine("Retrieve the conversation history of an agent session from the database.")
        appendLine("You can look up sessions by session ID or list recent sessions for a binary.")
        appendLine("Returns messages as a JSON array with role, content, and tool call details.")
    },
    parameters = listOf(
        ToolParameter(
            "sessionId", "string",
            "The session ID to query. If omitted, lists recent sessions instead.",
            required = false
        ),
        ToolParameter(
            "binaryId", "integer",
            "Filter sessions by binary ID. Defaults to the current binary.",
            required = false
        ),
        ToolParameter(
            "limit", "integer",
            "Maximum number of messages or sessions to return. Default: 50.",
            required = false
        )
    )
) { args ->
    val sessionId = args["sessionId"] as? String
    val binaryId = (args["binaryId"] as? Number)?.toInt()
    val limit = (args["limit"] as? Number)?.toInt() ?: 50

    val mapper = jacksonObjectMapper()

    try {
        if (sessionId != null) {
            val messages = agentDbClient.getMessages(
                sessionId = sessionId,
                fromIndex = 0,
                limit = limit
            )
            val summaries = messages.map { msg ->
                mapOf(
                    "index" to msg.messageIndex,
                    "role" to msg.role,
                    "content" to msg.content?.take(2000),
                    "toolName" to msg.toolName,
                    "toolResult" to msg.toolResult?.take(1000)
                )
            }
            mapper.writeValueAsString(summaries)
        } else {
            val sessions = agentDbClient.listSessions(
                binaryId = binaryId,
                limit = limit
            )
            val summaries = sessions.map { s ->
                mapOf(
                    "sessionId" to s.sessionId,
                    "sessionName" to s.sessionName,
                    "status" to s.status,
                    "moduleName" to s.moduleName,
                    "modelName" to s.modelName,
                    "createdAt" to s.createdAt
                )
            }
            mapper.writeValueAsString(summaries)
        }
    } catch (e: Exception) {
        "Error querying session history: ${e.message}"
    }
}
