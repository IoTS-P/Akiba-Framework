package org.iotsplab.akiba.llm.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.agent.AgentMailboxService
import org.iotsplab.akiba.llm.agent.MailboxAccessException

// ============================================================
//  AgentMailboxTools — inter-agent mailbox / artifact tools
// ============================================================

private val toolMapper = jacksonObjectMapper()

/** Build the four mailbox tools. */
fun AgentMailboxTools(
    mailboxService: AgentMailboxService,
    callerSessionId: String?,
): List<Tool> = listOf(
    SendAgentMessageTool(mailboxService, callerSessionId),
    ReadAgentMessagesTool(mailboxService, callerSessionId),
    PublishAgentArtifactTool(mailboxService, callerSessionId),
    ReadAgentArtifactTool(mailboxService, callerSessionId),
)

/** Build only the message-sending tools (no artifact publishing). */
fun AgentMessageTools(
    mailboxService: AgentMailboxService,
    callerSessionId: String?,
): List<Tool> = listOf(
    SendAgentMessageTool(mailboxService, callerSessionId),
    ReadAgentMessagesTool(mailboxService, callerSessionId),
)

// ============================================================
//  send_agent_message
// ============================================================

private fun SendAgentMessageTool(
    service: AgentMailboxService,
    callerSessionId: String?,
): Tool = Tool(
    name = "send_agent_message",
    description = buildString {
        appendLine("Send an asynchronous message to another agent session's mailbox.")
        appendLine()
        appendLine("Mailbox access policy (enforced both here and at the DB layer):")
        appendLine("  - The caller MUST be the sender session (sessionId is auto-filled).")
        appendLine("  - The recipient must exist.")
        appendLine("  - The recipient must NOT be a one-shot session in a terminal state " +
            "(`completed` or `error`): such sessions are read-only via " +
            "`query_session_history` / `read_history_tool_call` and CANNOT receive new work. " +
            "Standby sessions remain reachable until the dispatcher transitions them to " +
            "`completed`.")
        appendLine("  - Active (running) sessions cannot be messaged either: they are " +
            "still running their own loop; use `await_agent` (or `spawn_sub_agent` + " +
            "`await_agent`) to wait. Mailbox is for *follow-up* work after the recipient " +
            "has parked itself.")
        appendLine()
        appendLine("Replies use `kind=reply` plus `in_reply_to_message_id=<messageId>` so the " +
            "threading stays routable. Use `kind=cancel` to ask the recipient to drop a " +
            "prior request.")
        appendLine()
        appendLine("Returns `{messageId, status: \"sent\"}` on success; structured JSON " +
            "error on policy violation (so the LLM can react rather than retry blindly).")
    },
    parameters = listOf(
        ToolParameter(
            "recipientSessionId", "string",
            "UUID of the target session. Must be a non-terminal session. Required.",
            required = true,
        ),
        ToolParameter(
            "kind", "string",
            "One of: note | request | reply | cancel | heartbeat. Default 'note'.",
            required = false,
            enum = listOf("note", "request", "reply", "cancel", "heartbeat"),
        ),
        ToolParameter(
            "subject", "string",
            "Optional short subject line.",
            required = false,
        ),
        ToolParameter(
            "body", "string",
            "Message body. Required. Keep it focused — large payloads belong in " +
                "`publish_agent_artifact` and should be referenced by `relatedArtifactId`.",
            required = true,
        ),
        ToolParameter(
            "relatedArtifactId", "string",
            "Optional artifactId (as string) this message attaches to.",
            required = false,
        ),
        ToolParameter(
            "inReplyToMessageId", "string",
            "Optional messageId (as string) this message replies to. Use for `kind=reply` " +
                "and `kind=cancel` to keep threads routable.",
            required = false,
        ),
        ToolParameter(
            "priority", "integer",
            "Higher numbers are delivered first. Default 0.",
            required = false,
        ),
    ),
) { args ->
    val sender = callerSessionId
        ?: return@Tool "Error: send_agent_message has no caller sessionId; the owning " +
            "AgentModule must be initialised before this tool is invoked."
    val recipient = (args["recipientSessionId"] as? String)?.trim()
        ?: return@Tool "Error: 'recipientSessionId' is required"
    if (recipient == sender)
        return@Tool "Error: 'recipientSessionId' must differ from the sender's sessionId"
    val kind = (args["kind"] as? String)?.lowercase() ?: "note"
    val subject = (args["subject"] as? String)?.takeIf { it.isNotBlank() }
    val body = (args["body"] as? String)
        ?: return@Tool "Error: 'body' is required"
    if (body.isBlank()) return@Tool "Error: 'body' must not be blank"
    val relatedArtifactId = (args["relatedArtifactId"] as? String)?.toLongOrNull()
    val inReplyToMessageId = (args["inReplyToMessageId"] as? String)?.toLongOrNull()
    val priority = (args["priority"] as? Number)?.toInt() ?: 0

    try {
        val messageId = service.send(
            senderSessionId = sender,
            recipientSessionId = recipient,
            kind = kind,
            subject = subject,
            body = body,
            relatedArtifactId = relatedArtifactId,
            inReplyToMessageId = inReplyToMessageId,
            priority = priority,
        )
        toolMapper.writeValueAsString(
            mapOf(
                "messageId" to messageId,
                "status" to "sent",
                "senderSessionId" to sender,
                "recipientSessionId" to recipient,
                "kind" to kind,
            )
        )
    } catch (e: MailboxAccessException) {
        toolMapper.writeValueAsString(
            mapOf(
                "status" to "error",
                "error" to (e.message ?: "send failed"),
                "recipientSessionId" to recipient,
                "hint" to (
                    if ((e.message ?: "").contains("one-shot", ignoreCase = true))
                        "Recipient is one-shot and terminal; its history is readable " +
                            "via query_session_history / read_history_tool_call but it " +
                            "cannot be re-targeted. Spawn a fresh sub-agent instead."
                    else
                        "Verify recipientSessionId is correct and that the recipient is " +
                            "in standby (or active under a parent_session_id you control)."
                    ),
            )
        )
    }
}

// ============================================================
//  read_agent_messages
// ============================================================

private fun ReadAgentMessagesTool(
    service: AgentMailboxService,
    callerSessionId: String?,
): Tool = Tool(
    name = "read_agent_messages",
    description = buildString {
        appendLine("Read incoming mailbox messages for the current session.")
        appendLine()
        appendLine("Three modes:")
        appendLine("  - `action=peek`     (default): list unread messages WITHOUT marking read.")
        appendLine("  - `action=drain`   : list AND mark as read. The harness's " +
            "`beforeIteration` already drains periodically, so explicit drains are usually " +
            "redundant. Use when you need to force-read on demand.")
        appendLine("  - `action=count`   : return the unread count only (cheap).")
        appendLine()
        appendLine("You can also fetch a single message with `messageId`.")
    },
    parameters = listOf(
        ToolParameter(
            "action", "string",
            "One of: peek | drain | count | get. Default 'peek'.",
            required = false,
            enum = listOf("peek", "drain", "count", "get"),
        ),
        ToolParameter(
            "limit", "integer",
            "Maximum messages to return. Default 50. Cap at 500.",
            required = false,
        ),
        ToolParameter(
            "messageId", "string",
            "Required when action=get. messageId of the row to fetch (the session must be " +
                "sender OR recipient).",
            required = false,
        ),
        ToolParameter(
            "includeRead", "boolean",
            "For action=peek only: include messages already marked read. Default false.",
            required = false,
        ),
    ),
) { args ->
    val sessionId = callerSessionId
        ?: return@Tool "Error: read_agent_messages has no caller sessionId; the owning " +
            "AgentModule must be initialised before this tool is invoked."
    val action = (args["action"] as? String)?.lowercase() ?: "peek"
    val limit = (args["limit"] as? Number)?.toInt()?.coerceIn(1, 500) ?: 50
    val messageId = (args["messageId"] as? String)?.toLongOrNull()
    val includeRead = args["includeRead"] as? Boolean ?: false

    try {
        when (action) {
            "peek" -> {
                val msgs = service.peek(sessionId, limit, includeRead)
                toolMapper.writeValueAsString(
                    mapOf(
                        "sessionId" to sessionId,
                        "action" to "peek",
                        "count" to msgs.size,
                        "messages" to msgs.map(::mailboxMessageToJson),
                    )
                )
            }
            "drain" -> {
                val msgs = service.drain(sessionId, limit)
                toolMapper.writeValueAsString(
                    mapOf(
                        "sessionId" to sessionId,
                        "action" to "drain",
                        "count" to msgs.size,
                        "messages" to msgs.map(::mailboxMessageToJson),
                    )
                )
            }
            "count" -> {
                val unread = service.countUnread(sessionId)
                toolMapper.writeValueAsString(
                    mapOf("sessionId" to sessionId, "action" to "count", "unread" to unread)
                )
            }
            "get" -> {
                val mid = messageId
                    ?: return@Tool "Error: action=get requires 'messageId'"
                val msg = service.getMessage(sessionId, mid)
                    ?: return@Tool "Error: message $mid not found or not visible to $sessionId"
                toolMapper.writeValueAsString(mailboxMessageToJson(msg))
            }
            else -> "Error: invalid action '$action'. Use peek | drain | count | get."
        }
    } catch (e: Exception) {
        "Error: read_agent_messages failed: ${e.message ?: e.javaClass.simpleName}"
    }
}

// ============================================================
//  publish_agent_artifact
// ============================================================

private fun PublishAgentArtifactTool(
    service: AgentMailboxService,
    callerSessionId: String?,
): Tool = Tool(
    name = "publish_agent_artifact",
    description = buildString {
        appendLine("Publish a named, versioned artifact under the caller's session.")
        appendLine()
        appendLine("Artifact publishing semantics:")
        appendLine("  - The caller is the owner. There is no impersonation.")
        appendLine("  - Uniqueness key is `(ownerSessionId, name, version)`. To " +
            "increment a version explicitly, query the latest version with " +
            "`read_agent_artifact` (no `version` argument) and pass `version + 1`. " +
            "Publishing the same version overwrites the existing row.")
        appendLine("  - `isPublic=true` makes the artifact readable by other sessions " +
            "on the same binary. Public reads enforce same-binary visibility at the DB layer.")
        appendLine("  - Heavy payloads (>~10KB) should travel through this tool rather " +
            "than as `body` in `send_agent_message`: the message can then reference the " +
            "artifact by id and stay small.")
    },
    parameters = listOf(
        ToolParameter(
            "name", "string",
            "Artifact name. Required. Lower-case + dashes recommended for shareability.",
            required = true,
        ),
        ToolParameter(
            "kind", "string",
            "One of: data | finding | plan | note | code | reference. Default 'data'.",
            required = false,
            enum = listOf("data", "finding", "plan", "note", "code", "reference"),
        ),
        ToolParameter(
            "content", "string",
            "Artifact body. Required.",
            required = true,
        ),
        ToolParameter(
            "summary", "string",
            "Optional short description for listing UIs.",
            required = false,
        ),
        ToolParameter(
            "metadata", "string",
            "Optional JSON string with structured metadata (kept in jsonb column).",
            required = false,
        ),
        ToolParameter(
            "isPublic", "boolean",
            "When true, other sessions on the same binary can read this artifact. Default false.",
            required = false,
        ),
        ToolParameter(
            "version", "integer",
            "Version number (>=1). Default 1. Bump explicitly to keep history.",
            required = false,
        ),
    ),
) { args ->
    val owner = callerSessionId
        ?: return@Tool "Error: publish_agent_artifact has no caller sessionId"
    val name = (args["name"] as? String)?.trim()
        ?: return@Tool "Error: 'name' is required"
    if (name.isBlank()) return@Tool "Error: 'name' must not be blank"
    val kind = (args["kind"] as? String)?.lowercase() ?: "data"
    val content = (args["content"] as? String)
        ?: return@Tool "Error: 'content' is required"
    if (content.isBlank()) return@Tool "Error: 'content' must not be blank"
    val summary = (args["summary"] as? String)?.takeIf { it.isNotBlank() }
    val metadata = (args["metadata"] as? String)?.takeIf { it.isNotBlank() }
    val isPublic = args["isPublic"] as? Boolean ?: false
    val version = (args["version"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 1

    try {
        val artifactId = service.publish(
            ownerSessionId = owner,
            name = name,
            kind = kind,
            content = content,
            summary = summary,
            metadata = metadata,
            isPublic = isPublic,
            version = version,
        )
        toolMapper.writeValueAsString(
            mapOf(
                "status" to "published",
                "artifactId" to artifactId,
                "ownerSessionId" to owner,
                "name" to name,
                "version" to version,
                "kind" to kind,
                "isPublic" to isPublic,
            )
        )
    } catch (e: MailboxAccessException) {
        toolMapper.writeValueAsString(
            mapOf(
                "status" to "error",
                "error" to (e.message ?: "publish failed"),
                "hint" to "Verify name / kind are non-blank and well-formed; the DB " +
                    "validates kind against the enum and rejects blank content.",
            )
        )
    }
}

// ============================================================
//  read_agent_artifact
// ============================================================

private fun ReadAgentArtifactTool(
    service: AgentMailboxService,
    callerSessionId: String?,
): Tool = Tool(
    name = "read_agent_artifact",
    description = buildString {
        appendLine("Read a single artifact. Either:")
        appendLine("  - `artifactId=<id>`            (the canonical handle returned by " +
            "`publish_agent_artifact`)")
        appendLine("  - `ownerSessionId=<uuid>` + `name=<str>` + optional `version=<int>` " +
            "(omit `version` for the latest).")
        appendLine()
        appendLine("Access policy:")
        appendLine("  - The owner can always read.")
        appendLine("  - Other sessions can read only when the artifact's `isPublic=true` " +
            "AND the caller shares the owner's `binary_id`. Cross-binary reads are rejected.")
        appendLine()
        appendLine("For listing, use `read_agent_artifacts_list` (if exposed) or call " +
            "`read_agent_artifact` repeatedly with successive versions.")
    },
    parameters = listOf(
        ToolParameter(
            "artifactId", "string",
            "artifactId returned by publish_agent_artifact.",
            required = false,
        ),
        ToolParameter(
            "ownerSessionId", "string",
            "Owner session UUID. Required when artifactId is omitted.",
            required = false,
        ),
        ToolParameter(
            "name", "string",
            "Artifact name. Required when artifactId is omitted.",
            required = false,
        ),
        ToolParameter(
            "version", "integer",
            "Optional version (default: latest).",
            required = false,
        ),
    ),
) { args ->
    val caller = callerSessionId
    val artifactId = (args["artifactId"] as? String)?.toLongOrNull()
    val ownerSessionId = (args["ownerSessionId"] as? String)
    val name = (args["name"] as? String)
    val version = (args["version"] as? Number)?.toInt()

    if (artifactId == null && (ownerSessionId == null || name == null))
        return@Tool "Error: either 'artifactId' or both 'ownerSessionId' and 'name' are required"

    try {
        val artifact = service.get(caller, artifactId, ownerSessionId, name, version)
        if (artifact == null) return@Tool "Error: artifact not found or not visible to $caller"
        toolMapper.writeValueAsString(
            mapOf(
                "artifactId" to artifact.artifactId,
                "ownerSessionId" to artifact.ownerSessionId,
                "name" to artifact.name,
                "version" to artifact.version,
                "kind" to artifact.kind,
                "summary" to artifact.summary,
                "metadata" to artifact.metadata,
                "isPublic" to artifact.isPublic,
                "content" to artifact.content,
                "createdAt" to artifact.createdAt,
            )
        )
    } catch (e: Exception) {
        "Error: read_agent_artifact failed: ${e.message ?: e.javaClass.simpleName}"
    }
}

// ============================================================
//  Row → JSON helpers
// ============================================================

private fun mailboxMessageToJson(m: AgentDatabaseClient.MailboxMessageInfo): Map<String, Any?> = mapOf(
    "messageId" to m.messageId,
    "senderSessionId" to m.senderSessionId,
    "recipientSessionId" to m.recipientSessionId,
    "kind" to m.kind,
    "subject" to m.subject,
    "body" to m.body,
    "relatedArtifactId" to m.relatedArtifactId,
    "inReplyToMessageId" to m.inReplyToMessageId,
    "priority" to m.priority,
    "readAt" to m.readAt,
    "ackedAt" to m.ackedAt,
    "createdAt" to m.createdAt,
)
