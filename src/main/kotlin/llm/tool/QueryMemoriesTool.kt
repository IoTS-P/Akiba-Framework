package org.iotsplab.akiba.llm.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.agent.Tool
import org.iotsplab.akiba.llm.agent.ToolParameter

/**
 * Create a tool that queries the cognitive memory store.
 *
 * Agents can search for past findings, insights, plans, and other
 * memories associated with the current session or binary.
 */
fun QueryMemoriesTool(): Tool = Tool(
    name = "query_memories",
    description = buildString {
        appendLine("Query the agent's long-term memory store for past findings, insights, ")
        appendLine("plans, and other recorded knowledge. You can filter by memory type, ")
        appendLine("session, or minimum importance. Memory types: finding, insight, plan, ")
        appendLine("observation, note. Returns memories as a JSON array.")
    },
    parameters = listOf(
        ToolParameter(
            "sessionId", "string",
            "Filter by session ID. If omitted, searches across all sessions.",
            required = false
        ),
        ToolParameter(
            "memoryType", "string",
            "Filter by memory type: 'finding', 'insight', 'plan', 'observation', 'note'.",
            required = false,
            enum = listOf("finding", "insight", "plan", "observation", "note")
        ),
        ToolParameter(
            "key", "string",
            "Filter by memory key (exact match).",
            required = false
        ),
        ToolParameter(
            "minImportance", "number",
            "Minimum importance threshold (0.0 to 1.0). Only memories at or above this level are returned.",
            required = false
        ),
        ToolParameter(
            "limit", "integer",
            "Maximum number of memories to return. Default: 20.",
            required = false
        )
    )
) { args ->
    val sessionId = args["sessionId"] as? String
    val memoryType = args["memoryType"] as? String
    val key = args["key"] as? String
    val minImportance = (args["minImportance"] as? Number)?.toDouble()
    val limit = (args["limit"] as? Number)?.toInt() ?: 20

    val mapper = jacksonObjectMapper()

    try {
        val memories = AgentDatabaseClient.queryMemories(
            sessionId = sessionId,
            memoryType = memoryType,
            key = key,
            minImportance = minImportance,
            limit = limit
        )
        val summaries = memories.map { m ->
            mapOf(
                "id" to m.memoryId,
                "type" to m.memoryType,
                "scope" to m.scope,
                "key" to m.key,
                "content" to m.content.take(1500),
                "importance" to m.importance,
                "createdAt" to m.createdAt
            )
        }
        mapper.writeValueAsString(summaries)
    } catch (e: Exception) {
        "Error querying memories: ${e.message}"
    }
}
