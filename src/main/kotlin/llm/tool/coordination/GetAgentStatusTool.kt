package org.iotsplab.akiba.llm.tool.coordination

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.tool.core.Tool
import org.iotsplab.akiba.llm.tool.core.ToolParameter

// ============================================================
//  GetAgentStatusTool — peek any visible agent's runtime state
// ============================================================
//
// Companion to `spawn_sub_agent` and `await_multiple_children`: polls one
// target session (self or a direct child) and reports its runtime state,
// lifecycle, last message time, token totals, tool-call count and a capped
// listing of its direct children.
//
// Access is auto-detected from `parent_session_id`, never from a caller-supplied
// `scope`: the default policy admits `self` and `direct_child` only, and
// everything else returns `status='forbidden'` carrying the detected
// relationship so the LLM can see why it was denied.  Cross-relationship reads
// (parent/sibling) are deferred until there is a real permission model.

/**
 * Build the `get_agent_status` tool.
 */
fun GetAgentStatusTool(
    agentDbClient: AgentDatabaseClient,
    callerSessionId: String?,
): Tool = Tool(
    name = "get_agent_status",
    description = buildString {
        appendLine("Query a single agent's runtime state and aggregated counters. " +
            "Companion to `spawn_sub_agent` / `await_multiple_children`: lets a parent agent " +
            "decide whether a child is still progressing, parked, or already " +
            "terminal, and lets a standby-capable agent check its OWN state before " +
            "calling `await_condition` to park.")
        appendLine()
        appendLine("Returns, for the target session:")
        appendLine("  - `runtimeState` (running | standby | msghandle | cancelling | closed | error)")
        appendLine("  - `lifecycle` (one_shot | standby)")
        appendLine("  - `lastMessageAt` (timestamp of the most recent agent_messages row, or null)")
        appendLine("  - `totalInputTokens` / `totalOutputTokens` (cumulative across the whole session)")
        appendLine("  - `totalToolCalls` (count of agent_tool_calls rows)")
        appendLine("  - `childCount` (number of direct children) and `runningChildCount` (subset in running/msghandle)")
        appendLine("  - `directChildren[]` (capped listing of the target's own direct children)")
        appendLine("  - `relationship` (auto-detected: self | direct_child | direct_parent | sibling | other)")
        appendLine()
        appendLine("Required:")
        appendLine("  targetSessionId — UUID of the agent session to inspect. Pass the")
        appendLine("                   caller's own session id to query its own state (e.g. to")
        appendLine("                   check whether it is a standby-capable agent).")
        appendLine()
        appendLine("Optional:")
        appendLine("  childLimit — cap on directChildren rows. Default 64, max 256.")
        appendLine()
        appendLine("Access policy (auto-detected, enforced server-side):")
        appendLine("  - The relationship between caller and target is auto-detected from")
        appendLine("    `parent_session_id`; the tool does NOT accept a `scope` field.")
        appendLine("  - Default: only `self` and `direct_child` reads are admitted.")
        appendLine("  - `direct_parent` / `sibling` / `other` are detected (so the LLM")
        appendLine("    can see WHY access was denied) but currently rejected with")
        appendLine("    `status='forbidden'` + a hint. Cross-relationship reads will be")
        appendLine("    enabled after a future permission model is added.")
        appendLine()
        appendLine("Typical use:")
        appendLine("  - After `spawn_sub_agent` (the child is registered but the parent")
        appendLine("    cannot see whether it has actually started).")
        appendLine("  - A standby-capable agent queries its OWN row to confirm its")
        appendLine("    `lifecycle` before emitting the `Enter standby mode.` marker.")
        appendLine("  - While `await_multiple_children` is in flight, to peek progress without blocking.")
        appendLine("  - When the caller needs to know whether to send a follow-up mailbox")
        appendLine("    message (a `closed`/`error` child cannot receive mail).")
    },
    parameters = listOf(
        ToolParameter(
            "targetSessionId", "string",
            "UUID of the agent session to inspect. Pass the caller's own session id " +
                "to query its own state. Required.",
            required = true,
        ),
        ToolParameter(
            "childLimit", "integer",
            "Cap on directChildren rows. Default 64, hard max 256.",
            required = false,
        ),
    ),
) { args ->
    val caller = callerSessionId
        ?: return@Tool "Error: get_agent_status has no caller sessionId; the owning " +
            "AgentModule must be initialised before this tool is invoked."
    val target = (args["targetSessionId"] as? String)?.trim()
        ?: return@Tool "Error: 'targetSessionId' is required"
    if (target.isBlank()) return@Tool "Error: 'targetSessionId' must not be blank"
    val childLimit = (args["childLimit"] as? Number)?.toInt()?.coerceIn(1, 256) ?: 64

    try {
        when (val result = agentDbClient.getAgentStatus(
            callerSessionId = caller,
            targetSessionId = target,
            childLimit = childLimit,
        )) {
            is AgentDatabaseClient.AgentStatusResult.Ok -> jacksonObjectMapper().writeValueAsString(
                mapOf(
                    "status" to "ok",
                    "relationship" to result.info.relationship.wire(),
                    "callerSessionId" to caller,
                    "target" to mapOf(
                        "sessionId" to result.info.targetSessionId,
                        "sessionName" to null,  // daemon does not return this for the target row; left null
                        "runtimeState" to result.info.targetRuntimeState,
                        "lifecycle" to result.info.targetLifecycle,
                        "parentSessionId" to result.info.targetParentSessionId,
                        "binaryId" to result.info.targetBinaryId,
                        "moduleName" to result.info.targetModuleName,
                        "modelName" to result.info.targetModelName,
                        "closingReason" to result.info.targetClosingReason,
                        "createdAt" to result.info.targetCreatedAt,
                        "updatedAt" to result.info.targetUpdatedAt,
                        "completedAt" to result.info.targetCompletedAt,
                        "lastMessageAt" to result.info.lastMessageAt,
                        "totalInputTokens" to result.info.totalInputTokens,
                        "totalOutputTokens" to result.info.totalOutputTokens,
                        "totalToolCalls" to result.info.totalToolCalls,
                        "childCount" to result.info.childCount,
                        "runningChildCount" to result.info.runningChildCount,
                    ),
                    "directChildren" to result.info.directChildren.map { c ->
                        mapOf(
                            "sessionId" to c.sessionId,
                            "sessionName" to c.sessionName,
                            "runtimeState" to c.runtimeState,
                            "lifecycle" to c.lifecycle,
                            "parentSessionId" to c.parentSessionId,
                            "binaryId" to c.binaryId,
                            "moduleName" to c.moduleName,
                            "modelName" to c.modelName,
                            "createdAt" to c.createdAt,
                            "updatedAt" to c.updatedAt,
                        )
                    },
                    "directChildrenTruncated" to result.info.directChildrenTruncated,
                )
            )
            is AgentDatabaseClient.AgentStatusResult.NotFound -> jacksonObjectMapper().writeValueAsString(
                mapOf(
                    "status" to "not_found",
                    "error" to "Target session '$target' does not exist",
                    "callerSessionId" to caller,
                    "targetSessionId" to target,
                    "hint" to "Verify the sessionId (UUID) is correct and that the target " +
                        "was not deleted. Use `query_session_history` to look up sessionIds " +
                        "for a given binary/module if you are unsure.",
                )
            )
            is AgentDatabaseClient.AgentStatusResult.AccessDenied -> jacksonObjectMapper().writeValueAsString(
                mapOf(
                    "status" to "forbidden",
                    "error" to result.error,
                    "callerSessionId" to caller,
                    "targetSessionId" to target,
                    "relationship" to result.relationship.wire(),
                    "hint" to (result.hint
                        ?: ("Default visibility is 'self' and 'direct_child' only. " +
                            "The relationship between caller and target was auto-detected " +
                            "as '${result.relationship.wire()}' and is currently not admitted. " +
                            "Cross-relationship reads will be enabled after a future " +
                            "permission model is added.")),
                )
            )
            is AgentDatabaseClient.AgentStatusResult.BadRequest -> jacksonObjectMapper().writeValueAsString(
                mapOf(
                    "status" to "bad_request",
                    "error" to result.error,
                    "callerSessionId" to caller,
                    "targetSessionId" to target,
                )
            )
        }
    } catch (e: Exception) {
        "Error: get_agent_status failed: ${e.message ?: e.javaClass.simpleName}"
    }
}
