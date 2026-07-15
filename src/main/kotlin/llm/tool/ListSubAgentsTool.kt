package org.iotsplab.akiba.llm.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.agent.AgentModule

// ============================================================
//  ListSubAgentsTool — list/search an agent's sub-agents
// ============================================================
//
// Companion to `spawn_sub_agent`.  While `get_agent_status` can
// show a capped listing of direct children when the caller queries
// itself, there is no dedicated, intuitive tool for an agent to
// discover "what children do I have and what are they doing?".
//
// This tool fills that gap:
//
//   * Lists all direct children of the calling agent by default.
//   * Optionally filters by `status` (e.g. "running" to see only
//     active children, "closed" to see finished ones).
//   * Optionally filters by `name` — fuzzy, case-insensitive,
//     multi-keyword search.  Underscores in names are ignored
//     so "foobar" matches "foo_bar".
//   * Optionally walks the tree recursively (`depth` parameter)
//     so the caller can inspect grandchildren without N serial
//     `get_agent_status` calls.
//   * Returns a flat list with `depth` and `parentSessionId`
//     fields so the LLM can reconstruct the tree if needed.
//
// Typical use:
//   - After spawning several children, check which are still
//     running vs. finished.
//   - Search for a specific child by name fragment.
//   - Before calling `await_multiple_children`, discover the
//     child sessionIds (though normally the caller already has
//     them from `spawn_sub_agent` return values).
//   - Diagnose a stalled pipeline: "show me all my descendants
//     and their states".

/**
 * Build the `list_sub_agents` tool.
 *
 * @param parent      The owning [AgentModule] — its `agentSessionId`
 *                    identifies the caller.
 * @param agentDbClient  Database client for session queries.
 */
fun ListSubAgentsTool(
    parent: AgentModule,
    agentDbClient: AgentDatabaseClient,
): Tool = Tool(
    name = "list_sub_agents",
    description = buildString {
        appendLine("List the sub-agents (children) spawned by this agent, with their current")
        appendLine("runtime states.  Use this to discover which children are still running,")
        appendLine("which have finished, and which have errored — without needing to know")
        appendLine("each child's sessionId in advance.")
        appendLine()
        appendLine("Returns a flat array of child sessions, each with:")
        appendLine("  - sessionId, sessionName, runtimeState, lifecycle")
        appendLine("  - moduleName, modelName, createdAt, updatedAt, completedAt")
        appendLine("  - closingReason (for terminal sessions)")
        appendLine("  - depth (0 = direct child, 1 = grandchild, ...) and parentSessionId")
        appendLine()
        appendLine("Optional:")
        appendLine("  status — filter by runtime state: running | standby | msghandle |")
        appendLine("           cancelling | closed | error.  Default: no filter (all children).")
        appendLine("  name   — fuzzy search by session name.  Case-insensitive, supports")
        appendLine("           multiple keywords separated by spaces or commas (AND logic: all")
        appendLine("           keywords must match).  Underscores in names are ignored, so")
        appendLine("           'foobar' matches 'foo_bar' and vice versa.")
        appendLine("  depth  — how deep to recurse. 0 = direct children only (default).")
        appendLine("           1 = include grandchildren.  Max 5 to prevent excessive queries.")
        appendLine()
        appendLine("Typical use:")
        appendLine("  - After spawning several sub-agents, check which are still running.")
        appendLine("  - Find children that have errored (status='error') to decide whether")
        appendLine("    to retry or proceed.")
        appendLine("  - Search for a child by name: name='vuln layer1'.")
        appendLine("  - Get a quick overview of the entire sub-agent tree (depth=5).")
    },
    parameters = listOf(
        ToolParameter(
            "status", "string",
            "Filter by runtime state: running | standby | msghandle | cancelling | " +
                "closed | error.  Omit to list all children regardless of state.",
            required = false,
            enum = listOf(
                "running", "standby", "msghandle", "cancelling", "closed", "error",
            ),
        ),
        ToolParameter(
            "name", "string",
            "Fuzzy search by session name.  Case-insensitive.  Multiple keywords " +
                "can be separated by spaces or commas (AND: all must match). " +
                "Underscores in names are ignored ('foobar' matches 'foo_bar'). " +
                "Aliases: 'search', 'filter' (same behaviour).",
            required = false,
        ),
        ToolParameter(
            "search", "string",
            "Alias for 'name' — fuzzy search by session name.",
            required = false,
        ),
        ToolParameter(
            "filter", "string",
            "Alias for 'name' — fuzzy search by session name.",
            required = false,
        ),
        ToolParameter(
            "depth", "integer",
            "Recursion depth. 0 = direct children only (default). " +
                "1 = include grandchildren. Max 5.",
            required = false,
        ),
    ),
) { args ->
    val callerSessionId = parent.agentSessionId
        ?: return@Tool "Error: list_sub_agents has no caller sessionId; the owning " +
            "AgentModule must be initialised before this tool is invoked."

    val statusFilter = (args["status"] as? String)?.trim()?.takeIf { it.isNotBlank() }
    val nameSearch = ((args["name"] ?: args["search"] ?: args["filter"]) as? String)
        ?.trim()?.takeIf { it.isNotBlank() }
    val maxDepth = (args["depth"] as? Number)?.toInt()?.coerceIn(0, 5) ?: 0

    // ---- Name search helpers -------------------------------------------------
    //
    // Fuzzy, case-insensitive, multi-keyword matching.
    //
    //   * Keywords are split by whitespace, commas, semicolons.
    //   * ALL keywords must be found in the name (AND logic).
    //   * Both the keyword and the name are normalised by lowercasing
    //     and removing underscores, so "foobar" matches "foo_bar"
    //     and "Foo_Bar" matches "foobar".

    val nameKeywords = nameSearch
        ?.split(Regex("[\\s,;]+"))
        ?.map { it.trim().lowercase().replace("_", "") }
        ?.filter { it.isNotEmpty() }
        ?: emptyList()

    fun matchesName(sessionName: String?): Boolean {
        if (nameKeywords.isEmpty()) return true
        if (sessionName.isNullOrBlank()) return false
        val normalized = sessionName.lowercase().replace("_", "")
        return nameKeywords.all { kw -> normalized.contains(kw) }
    }

    data class ChildEntry(
        val sessionId: String,
        val sessionName: String?,
        val runtimeState: String?,
        val lifecycle: String?,
        val moduleName: String?,
        val modelName: String?,
        val parentSessionId: String?,
        val createdAt: String?,
        val updatedAt: String?,
        val completedAt: String?,
        val closingReason: String?,
        val depth: Int,
    )

    fun matchesFilter(state: String?): Boolean {
        if (statusFilter == null) return true
        return state == statusFilter
    }

    // Recursive collection
    val results = mutableListOf<ChildEntry>()
    val visited = mutableSetOf<String>()  // guard against cycles

    fun collectChildren(parentId: String, currentDepth: Int) {
        if (currentDepth > maxDepth) return
        val children = try {
            agentDbClient.getSessionChildren(parentId)
        } catch (e: Exception) {
            // If the DB query fails for one branch, continue with others.
            return
        }
        for (child in children) {
            if (child.sessionId in visited) continue
            visited.add(child.sessionId)

            // Only include in results if it matches both the status
            // filter and the name search.  But still recurse into
            // non-matching children (their descendants might match).
            if (matchesFilter(child.runtimeState) && matchesName(child.sessionName)) {
                results.add(
                    ChildEntry(
                        sessionId = child.sessionId,
                        sessionName = child.sessionName,
                        runtimeState = child.runtimeState,
                        lifecycle = child.lifecycle,
                        moduleName = child.moduleName,
                        modelName = child.modelName,
                        parentSessionId = child.parentSessionId,
                        createdAt = child.createdAt,
                        updatedAt = child.updatedAt,
                        completedAt = child.completedAt,
                        closingReason = child.closingReason,
                        depth = currentDepth,
                    )
                )
            }

            // Recurse into this child's children if within depth limit.
            if (currentDepth < maxDepth) {
                collectChildren(child.sessionId, currentDepth + 1)
            }
        }
    }

    try {
        collectChildren(callerSessionId, 0)

        val mapper = jacksonObjectMapper()
        if (results.isEmpty()) {
            mapper.writeValueAsString(
                mapOf(
                    "status" to "ok",
                    "count" to 0,
                    "message" to buildString {
                        append("No sub-agents found")
                        val parts = mutableListOf<String>()
                        if (statusFilter != null) parts.add("status='$statusFilter'")
                        if (nameKeywords.isNotEmpty()) parts.add("name~='$nameSearch'")
                        if (parts.isNotEmpty()) append(" matching ${parts.joinToString(", ")}")
                        append(".")
                    },
                    "callerSessionId" to callerSessionId,
                )
            )
        } else {
            mapper.writeValueAsString(
                mapOf(
                    "status" to "ok",
                    "count" to results.size,
                    "callerSessionId" to callerSessionId,
                    "filter" to (statusFilter ?: "none"),
                    "nameSearch" to (nameSearch ?: "none"),
                    "maxDepth" to maxDepth,
                    "subAgents" to results.map { c ->
                        mapOf(
                            "sessionId" to c.sessionId,
                            "sessionName" to c.sessionName,
                            "runtimeState" to c.runtimeState,
                            "lifecycle" to c.lifecycle,
                            "moduleName" to c.moduleName,
                            "modelName" to c.modelName,
                            "parentSessionId" to c.parentSessionId,
                            "createdAt" to c.createdAt,
                            "updatedAt" to c.updatedAt,
                            "completedAt" to c.completedAt,
                            "closingReason" to c.closingReason,
                            "depth" to c.depth,
                        )
                    },
                )
            )
        }
    } catch (e: Exception) {
        "Error: list_sub_agents failed: ${e.message ?: e.javaClass.simpleName}"
    }
}
