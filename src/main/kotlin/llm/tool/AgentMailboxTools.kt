package org.iotsplab.akiba.llm.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.agent.AgentMailboxService
import org.iotsplab.akiba.llm.agent.AllOf
import org.iotsplab.akiba.llm.agent.AnyOf
import org.iotsplab.akiba.llm.agent.ConversationRegistry
import org.iotsplab.akiba.llm.agent.MailboxAccessException
import org.iotsplab.akiba.llm.agent.MessageArrived
import org.iotsplab.akiba.llm.agent.StateChanged
import org.iotsplab.akiba.llm.agent.SYSTEM_SESSION_UUID
import org.iotsplab.akiba.llm.agent.TimeElapsed
import org.iotsplab.akiba.llm.agent.WakeCondition
import org.iotsplab.akiba.llm.agent.WakeConditionRegistry

// ============================================================
//  AgentMailboxTools — inter-agent mailbox / artifact tools
// ============================================================

private val toolMapper = jacksonObjectMapper()

/**
 * Parse a tool argument as [Long], accepting BOTH JSON strings and JSON
 * numbers.
 *
 * LLMs routinely emit numeric parameters as bare JSON numbers
 * (`{"messageId": 5}`) even when the tool schema declares the type as
 * `"string"`.  Jackson deserialises a JSON number into [Int] / [Long],
 * NOT [String], so `args[key] as? String` returns `null` and the value
 * is silently dropped.  This was the root cause of the
 * "ack fails but message stays on wake board" bug: the LLM passed
 * `messageId` as a number, the ack tool couldn't parse it, and the
 * message was never acked.
 *
 * This helper accepts:
 *  - [String] — parsed via [toLongOrNull]
 *  - [Number] (Int, Long, Double, …) — [Number.toLong]
 *  - `null` / missing — returns `null`
 */
private fun Map<String, Any?>.parseLong(key: String): Long? = when (val v = this[key]) {
    is String -> v.trim().toLongOrNull()
    is Number -> v.toLong()
    else -> null
}

/** Parse IDs that appear on the wake board as `msg#N`. */
private fun parseMessageIdValue(v: Any?): Long? = when (v) {
    is Number -> v.toLong()
    is String -> {
        val s = v.trim()
        when {
            s.toLongOrNull() != null -> s.toLongOrNull()
            s.startsWith("msg#", ignoreCase = true) -> s.substringAfter('#').trim().toLongOrNull()
            s.startsWith("messageId=", ignoreCase = true) -> s.substringAfter('=').trim().toLongOrNull()
            s.startsWith("messageId:", ignoreCase = true) -> s.substringAfter(':').trim().toLongOrNull()
            else -> null
        }
    }
    else -> null
}

private fun Map<String, Any?>.parseMessageId(key: String): Long? = parseMessageIdValue(this[key])

/** Parse conversation IDs, accepting the wake-board header form `conv#N`. */
private fun Map<String, Any?>.parseConversationId(key: String): Long? = when (val v = this[key]) {
    is Number -> v.toLong()
    is String -> {
        val s = v.trim()
        when {
            s.toLongOrNull() != null -> s.toLongOrNull()
            s.startsWith("conv#", ignoreCase = true) -> s.substringAfter('#').trim().toLongOrNull()
            s.startsWith("conversationId=", ignoreCase = true) -> s.substringAfter('=').trim().toLongOrNull()
            s.startsWith("conversationId:", ignoreCase = true) -> s.substringAfter(':').trim().toLongOrNull()
            else -> null
        }
    }
    else -> null
}

/** Build the four mailbox tools. */
fun AgentMailboxTools(
    mailboxService: AgentMailboxService,
    callerSessionId: String?,
): List<Tool> = listOf(
    SendAgentMessageTool(mailboxService, callerSessionId),
    ReadAgentMessagesTool(mailboxService, callerSessionId),
    AckAgentMessageTool(mailboxService, callerSessionId),
    CloseConversationTool(mailboxService, callerSessionId),
    QueryConversationTool(mailboxService, callerSessionId),
    QueryConversationsTool(mailboxService, callerSessionId),
    AwaitConditionTool(mailboxService, callerSessionId),
    PublishAgentArtifactTool(mailboxService, callerSessionId),
    ReadAgentArtifactTool(mailboxService, callerSessionId),
)

/** Build only the message tools (no artifact publishing). */
fun AgentMessageTools(
    mailboxService: AgentMailboxService,
    callerSessionId: String?,
): List<Tool> = listOf(
    SendAgentMessageTool(mailboxService, callerSessionId),
    ReadAgentMessagesTool(mailboxService, callerSessionId),
    AckAgentMessageTool(mailboxService, callerSessionId),
    CloseConversationTool(mailboxService, callerSessionId),
    QueryConversationTool(mailboxService, callerSessionId),
    QueryConversationsTool(mailboxService, callerSessionId),
    AwaitConditionTool(mailboxService, callerSessionId),
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
        appendLine("REQUIRED parameter: 'recipientSessionId' — the FULL UUID of the target session.")
        appendLine("  Do NOT use truncated ids. Copy the complete sessionId from the wake board")
        appendLine("  (the 'from=' value on each message line is the full sender sessionId).")
        appendLine()
        appendLine("For replies, set 'inReplyToMessageId' to the numeric messageId (NOT prefixed with #).")
        appendLine("  Example: if the wake board shows 'messageId=42', pass inReplyToMessageId=\"42\".")
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
        appendLine()
        appendLine("## Batch mode")
        appendLine("Pass `operations` as a JSON array string to send multiple messages in one call.")
        appendLine("Each element has the same shape as a single send (recipientSessionId, body, kind, ...).")
        appendLine("Returns `{total, succeeded, failed, results: [...]}` with per-item status.")
        appendLine("Example: operations=\"[{\\\"recipientSessionId\\\":\\\"<A>\\\",\\\"body\\\":\\\"hi\\\"}," +
            "{\\\"recipientSessionId\\\":\\\"<B>\\\",\\\"body\\\":\\\"hello\\\"}]\"")
    },
    parameters = listOf(
        ToolParameter(
            "recipientSessionId", "string",
            "UUID of the target session. Must be a non-terminal session. Required for single send.",
            required = false,
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
            "Message body. Required for single send. Keep it focused — large payloads belong in " +
                "`publish_agent_artifact` and should be referenced by `relatedArtifactId`.",
            required = false,
        ),
        ToolParameter(
            "relatedArtifactId", "string",
            "Optional artifactId (as string) this message attaches to.",
            required = false,
        ),
        ToolParameter(
            "inReplyToMessageId", "string",
            "Optional messageId (numeric string or number) this message replies to. " +
                "Use the msg#N number from the wake board (NOT conv#N). " +
                "Replies auto-ack the original message.",
            required = false,
        ),
        ToolParameter(
            "priority", "integer",
            "Higher numbers are delivered first. Default 0.",
            required = false,
        ),
        ToolParameter(
            "operations", "string",
            "JSON array string for batch send. Each element is an object with the same keys " +
                "as a single send (recipientSessionId, body, kind, subject, etc.). When this " +
                "parameter is provided, all single-send parameters are ignored.",
            required = false,
        ),
    ),
    dedupStrategy = org.iotsplab.akiba.llm.tool.ToolDedupStrategy.RESULT_HASH,
) { args ->
    val sender = callerSessionId
        ?: return@Tool "Error: send_agent_message has no caller sessionId; the owning " +
            "AgentModule must be initialised before this tool is invoked."

    // Messages sent TO the system session are always useless — it is a
    // synthetic sender for wake notifications, not a real agent that
    // processes mail.  Reject early so the LLM doesn't waste tokens
    // composing messages nobody will read.

    // ── Batch mode ──────────────────────────────────────────────
    val operationsRaw = (args["operations"] as? String)?.takeIf { it.isNotBlank() }
    if (operationsRaw != null) {
        return@Tool sendBatchMessages(service, sender, operationsRaw)
    }

    // ── Single mode (original logic) ────────────────────────────
    val recipient = (args["recipientSessionId"] as? String)?.trim()
        ?: return@Tool "Error: 'recipientSessionId' is required"
    if (recipient == sender)
        return@Tool "Error: 'recipientSessionId' must differ from the sender's sessionId"
    if (recipient == SYSTEM_SESSION_UUID)
        return@Tool "Error: cannot send messages to the system session (00000000-...). " +
            "The system session is a synthetic sender for wake notifications, not a real " +
            "agent. If you are trying to acknowledge a system notification, use " +
            "`ack_agent_message messageId=<id>` instead."
    val kind = (args["kind"] as? String)?.lowercase() ?: "note"
    val subject = (args["subject"] as? String)?.takeIf { it.isNotBlank() }
    val body = (args["body"] as? String)
        ?: return@Tool "Error: 'body' is required"
    if (body.isBlank()) return@Tool "Error: 'body' must not be blank"
    val relatedArtifactId = args.parseLong("relatedArtifactId")
    // Accept both "inReplyToMessageId" (schema name) and "inReplyTo"
    // (the short form used in wake-board instructions).
    // Also accept both String and Number types — LLMs often pass
    // numeric IDs as bare JSON numbers, which Jackson deserialises
    // as Int/Long, not String.
    val inReplyToMessageId = args.parseMessageId("inReplyToMessageId")
        ?: args.parseMessageId("inReplyTo")
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
        // Auto-ack the original message when replying: a reply IS
        // the handler, so the original transitions seen → handled
        // and won't re-surface in the [PENDING] section of future
        // wake boards.  This is the single most important ack path
        // — without it the LLM would have to manually ack every
        // message it replies to, which it routinely forgets.
        //
        // CRITICAL: check the return value!  If the auto-ack fails
        // (e.g. the inReplyTo ID doesn't match a message in the
        // caller's mailbox — common when the LLM confuses conv#N
        // with messageId=N), the original message stays pending and
        // re-surfaces on every future wake board.  Report the failure
        // so the LLM can explicitly ack the correct messageId.
        var autoAckOk = false
        var autoAckError: String? = null
        if (inReplyToMessageId != null) {
            autoAckOk = service.ack(sender, inReplyToMessageId)
            if (!autoAckOk) {
                autoAckError = "Auto-ack of messageId=$inReplyToMessageId FAILED. " +
                    "The original message is still pending. Use ack_agent_message " +
                    "with the correct messageId (shown on the wake board as " +
                    "'messageId=N') to acknowledge it."
            }
        }
        val result = mutableMapOf<String, Any?>(
            "messageId" to messageId,
            "status" to "sent",
            "senderSessionId" to sender,
            "recipientSessionId" to recipient,
            "kind" to kind,
            "autoAckedOriginal" to autoAckOk,
        )
        if (autoAckError != null) {
            result["autoAckError"] = autoAckError
        }
        toolMapper.writeValueAsString(result)
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

/**
 * Process a batch of message sends. Each element in the JSON array
 * has the same keys as a single `send_agent_message` call.
 * Returns a JSON object with per-item results and aggregate counts.
 */
@Suppress("UNCHECKED_CAST")
private fun sendBatchMessages(
    service: AgentMailboxService,
    sender: String,
    operationsRaw: String,
): String {
    val ops: List<Map<String, Any?>> = try {
        toolMapper.readTree(operationsRaw)
        val tree = toolMapper.readTree(operationsRaw)
        if (!tree.isArray) return toolMapper.writeValueAsString(mapOf(
            "status" to "error", "error" to "'operations' must be a JSON array"
        ))
        tree.map { node ->
            toolMapper.convertValue(node, Map::class.java) as Map<String, Any?>
        }
    } catch (e: Exception) {
        return toolMapper.writeValueAsString(mapOf(
            "status" to "error", "error" to "Invalid JSON in 'operations': ${e.message}"
        ))
    }

    val results = mutableListOf<Map<String, Any?>>()
    var succeeded = 0
    var failed = 0

    for ((index, op) in ops.withIndex()) {
        val recipient = (op["recipientSessionId"] as? String)?.trim()
        if (recipient == null || recipient.isBlank()) {
            results += mapOf("index" to index, "ok" to false,
                "error" to "missing 'recipientSessionId'")
            failed++
            continue
        }
        if (recipient == sender) {
            results += mapOf("index" to index, "ok" to false,
                "error" to "recipientSessionId must differ from sender")
            failed++
            continue
        }
        if (recipient == SYSTEM_SESSION_UUID) {
            results += mapOf("index" to index, "ok" to false,
                "error" to "cannot send to system session; use ack_agent_message for system notifications")
            failed++
            continue
        }
        val body = op["body"] as? String
        if (body.isNullOrBlank()) {
            results += mapOf("index" to index, "ok" to false,
                "error" to "missing or blank 'body'")
            failed++
            continue
        }

        try {
            val kind = (op["kind"] as? String)?.lowercase() ?: "note"
            val subject = (op["subject"] as? String)?.takeIf { it.isNotBlank() }
            val relatedArtifactId = op.parseLong("relatedArtifactId")
            val inReplyToMessageId = op.parseMessageId("inReplyToMessageId")
                ?: op.parseMessageId("inReplyTo")
            val priority = (op["priority"] as? Number)?.toInt() ?: 0

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
            if (inReplyToMessageId != null) {
                val ackOk = service.ack(sender, inReplyToMessageId)
                results += mapOf(
                    "index" to index, "ok" to true, "messageId" to messageId,
                    "recipientSessionId" to recipient, "status" to "sent",
                    "autoAckedOriginal" to ackOk,
                    "inReplyToMessageId" to inReplyToMessageId,
                )
            } else {
                results += mapOf(
                    "index" to index, "ok" to true, "messageId" to messageId,
                    "recipientSessionId" to recipient, "status" to "sent"
                )
            }
            succeeded++
        } catch (e: MailboxAccessException) {
            results += mapOf(
                "index" to index, "ok" to false,
                "error" to (e.message ?: "send failed"),
                "recipientSessionId" to recipient
            )
            failed++
        }
    }

    return toolMapper.writeValueAsString(mapOf(
        "total" to ops.size,
        "succeeded" to succeeded,
        "failed" to failed,
        "results" to results,
    ))
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
        appendLine("Read mailbox messages for the current session.")
        appendLine()
        appendLine("IMPORTANT: The wake board (injected automatically at the start of each turn) " +
            "already shows ALL your actionable messages — new, pending, and escalated — as " +
            "one-line summaries. You do NOT need to call this tool to see what's in your inbox.")
        appendLine()
        appendLine("Use this tool ONLY when you need:")
        appendLine("  - action=get     : the FULL body of a specific message (the wake board " +
            "shows only a truncated preview). Pass messageId=<N>.")
        appendLine("  - action=count   : a quick unread count (cheap, no data returned).")
        appendLine("  - action=peek    : raw list WITHOUT the wake-board formatting. Rarely needed.")
        appendLine("  - action=drain   : force-drain unread messages. Almost never needed — the " +
            "wake board already drains. Use only when you suspect the board missed something.")
        appendLine()
        appendLine("The recommended workflow is: read the wake board → use action=get for any " +
            "message whose full content you need → reply via send_agent_message or ack via " +
            "ack_agent_message.")
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
    val messageId = args.parseMessageId("messageId")
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
                    ?: return@Tool "Error: action=get requires 'messageId'. Use the msg#N number from the wake board, e.g. messageId=2 or messageId=\"msg#2\". Do NOT use conv#N here."
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
//  ack_agent_message
// ============================================================

private fun AckAgentMessageTool(
    service: AgentMailboxService,
    callerSessionId: String?,
): Tool = Tool(
    name = "ack_agent_message",
    description = buildString {
        appendLine("Mark a mailbox message as HANDLED — no reply needed.")
        appendLine()
        appendLine("Use this when you've read a message on the wake board and decided it " +
            "doesn't require a response (e.g. an informational note, a progress update " +
            "you've already noted, or a message you've already acted on by other means).")
        appendLine()
        appendLine("This operation is IDEMPOTENT: acking an already-acked message returns " +
            "success, not an error. If you see an ack error, the message genuinely does " +
            "not exist in your mailbox — do NOT retry blindly.")
        appendLine()
        appendLine("Acknowledged messages disappear from the [PENDING] section of future " +
            "wake boards, keeping the board clean and preventing escalation.")
        appendLine()
        appendLine("Note: replying via send_agent_message with in_reply_to=<messageId> " +
            "AUTO-acks the original — you don't need to call this tool separately when " +
            "you reply. Use this tool ONLY for messages that need no reply.")
        appendLine()
        appendLine("## Batch mode")
        appendLine("Pass `messageIds` as a comma-separated string (e.g. \"1,2,3\") to ack " +
            "multiple messages in one call. Returns `{total, succeeded, failed, results}`.")
    },
    parameters = listOf(
        ToolParameter(
            "messageId", "string",
            "The messageId to acknowledge (numeric string or number). The message must be in the " +
                "caller's mailbox (recipient). Required for single ack.",
            required = false,
        ),
        ToolParameter(
            "messageIds", "string",
            "Comma-separated list of messageIds for batch ack (e.g. \"1,2,3\"), or a JSON array " +
                "of numbers. When provided, 'messageId' is ignored.",
            required = false,
        ),
    ),
) { args ->
    val sessionId = callerSessionId
        ?: return@Tool "Error: ack_agent_message has no caller sessionId"

    // ── Batch mode ──────────────────────────────────────────────
    // Accept messageIds as either:
    //  - a comma/space-separated string ("1,2,3")
    //  - a JSON array of numbers ([1, 2, 3])
    //  - a JSON array of strings (["1", "2", "3"])
    val batchIds: List<Long>? = when (val v = args["messageIds"]) {
        is String -> v.takeIf { it.isNotBlank() }
            ?.split(",", ";", " ")?.mapNotNull { it.trim().toLongOrNull() }
        is List<*> -> v.mapNotNull { item ->
            when (item) {
                is Number -> item.toLong()
                is String -> item.trim().toLongOrNull()
                else -> null
            }
        }
        is Number -> listOf(v.toLong())
        else -> null
    }
    if (batchIds != null) {
        val ids = batchIds
        if (ids.isEmpty()) return@Tool toolMapper.writeValueAsString(mapOf(
            "status" to "error", "error" to "No valid messageIds found in '${args["messageIds"]}'"
        ))
        val results = mutableListOf<Map<String, Any?>>()
        var succeeded = 0
        var failed = 0
        for (mid in ids) {
            val ok = try { service.ack(sessionId, mid) } catch (_: Exception) { false }
            results += mapOf("messageId" to mid, "ok" to ok,
                "status" to if (ok) "acked" else "failed")
            if (ok) succeeded++ else failed++
        }
        return@Tool toolMapper.writeValueAsString(mapOf(
            "total" to ids.size, "succeeded" to succeeded, "failed" to failed,
            "results" to results,
        ))
    }

    // ── Single mode ─────────────────────────────────────────────
    val mid = args.parseMessageId("messageId")
        ?: return@Tool "Error: 'messageId' is required. Use the msg#N number from the wake board, e.g. messageId=2 or messageId=\"msg#2\". Do NOT use conv#N here."

    try {
        val ok = service.ack(sessionId, mid)
        if (ok) {
            toolMapper.writeValueAsString(
                mapOf(
                    "messageId" to mid,
                    "status" to "acked",
                    "sessionId" to sessionId,
                )
            )
        } else {
            toolMapper.writeValueAsString(
                mapOf(
                    "messageId" to mid,
                    "status" to "error",
                    "error" to "ack failed: messageId=$mid not found in your mailbox. " +
                        "Make sure you are using the msg#N number from the wake board " +
                        "(NOT the conv#N number). Check the wake board and retry with " +
                        "the correct messageId.",
                )
            )
        }
    } catch (e: Exception) {
        "Error: ack_agent_message failed: ${e.message ?: e.javaClass.simpleName}"
    }
}

// ============================================================
//  close_conversation
// ============================================================

private fun CloseConversationTool(
    service: AgentMailboxService,
    callerSessionId: String?,
): Tool = Tool(
    name = "close_conversation",
    description = buildString {
        appendLine("Close a conversation thread and notify all participants.")
        appendLine()
        appendLine("Use this when a conversation has reached its natural conclusion — " +
            "the task is done, the question is answered, or you've decided to end the " +
            "exchange. All other participants receive a notification message saying the " +
            "conversation is closed; they should NOT send further messages in this thread.")
        appendLine()
        appendLine("After closing:")
        appendLine("  • Messages in this conversation show [CLOSED] on the wake board.")
        appendLine("  • Participants know the thread is terminal — no ghost waiting.")
        appendLine("  • The conversation ID is the root messageId shown as conv#<N> on the wake board.")
        appendLine()
        appendLine("Only a participant of the conversation can close it. Closing an already-closed " +
            "conversation is a no-op.")
        appendLine()
        appendLine("## Batch mode")
        appendLine("Pass `conversationIds` as a comma-separated string (e.g. \"42,56,78\") to " +
            "close multiple conversations in one call. The optional `reason` applies to all. " +
            "Returns `{total, succeeded, failed, results}`.")
    },
    parameters = listOf(
        ToolParameter(
            "conversationId", "string",
            "The conversation ID to close. This is the root messageId of the thread, " +
                "shown as conv#<N> on the wake board. Must be a numeric string. " +
                "Required for single close.",
            required = false,
        ),
        ToolParameter(
            "conversationIds", "string",
            "Comma-separated list of conversation IDs for batch close (e.g. \"42,56,78\"). " +
                "When provided, 'conversationId' is ignored.",
            required = false,
        ),
        ToolParameter(
            "reason", "string",
            "Optional reason for closing (included in the notification to participants). " +
                "In batch mode, the same reason is used for all conversations.",
            required = false,
        ),
    ),
) { args ->
    val sessionId = callerSessionId
        ?: return@Tool "Error: close_conversation has no caller sessionId"
    val reason = (args["reason"] as? String)?.trim()?.takeIf { it.isNotEmpty() }

    // ── Batch mode ──────────────────────────────────────────────
    val batchIds = (args["conversationIds"] as? String)?.takeIf { it.isNotBlank() }
    if (batchIds != null) {
        val ids = batchIds.split(",", ";", " ").mapNotNull { token ->
            mapOf("conversationId" to token).parseConversationId("conversationId")
        }
        if (ids.isEmpty()) return@Tool toolMapper.writeValueAsString(mapOf(
            "status" to "error", "error" to "No valid conversationIds found in '$batchIds'"
        ))
        val results = mutableListOf<Map<String, Any?>>()
        var succeeded = 0
        var failed = 0
        for (convId in ids) {
            val r = closeSingleConversation(service, sessionId, convId, reason)
            val ok = r["status"] == "closed"
            results += r + ("conversationId" to convId)
            if (ok) succeeded++ else failed++
        }
        return@Tool toolMapper.writeValueAsString(mapOf(
            "total" to ids.size, "succeeded" to succeeded, "failed" to failed,
            "results" to results,
        ))
    }

    // ── Single mode ─────────────────────────────────────────────
    val convId = args.parseConversationId("conversationId")
        ?: return@Tool "Error: 'conversationId' is required. Use the conv#N number from the wake board, e.g. conversationId=2 or conversationId=\"conv#2\"."
    toolMapper.writeValueAsString(closeSingleConversation(service, sessionId, convId, reason))
}

/**
 * Close a single conversation and notify participants.
 * Extracted so both single and batch paths share the same logic.
 */
private fun closeSingleConversation(
    service: AgentMailboxService,
    sessionId: String,
    convId: Long,
    reason: String?,
): Map<String, Any?> {
    // Verify the caller is a participant
    val participants = ConversationRegistry.participants(convId)
    if (sessionId !in participants) {
        return mapOf(
            "status" to "error",
            "error" to "caller is not a participant of this conversation",
            "participants" to participants.map { it.take(8) },
        )
    }

    val toNotify = ConversationRegistry.close(convId, sessionId)
    if (toNotify.isEmpty()) {
        val info = ConversationRegistry.get(convId)
        return mapOf(
            "status" to if (info?.closed == true) "already_closed" else "not_found",
            "notifiedCount" to 0,
        )
    }

    val senderShort = sessionId.take(8)
    val notifyBody = buildString {
        appendLine("[conversation closed] Conv #$convId has been closed by $senderShort.")
        if (reason != null) appendLine("Reason: $reason")
        appendLine("Do not send further messages in this thread. If you need to continue " +
            "the discussion, start a new conversation by sending a message WITHOUT " +
            "in_reply_to=<messageId>.")
    }

    var notified = 0
    for (recipient in toNotify) {
        try {
            service.send(
                senderSessionId = sessionId,
                recipientSessionId = recipient,
                kind = "note",
                subject = "conversation #$convId closed",
                body = notifyBody,
                inReplyToMessageId = convId,
            )
            notified++
        } catch (_: Exception) { }
    }

    return mapOf(
        "status" to "closed",
        "closedBy" to sessionId,
        "notifiedCount" to notified,
        "notified" to toNotify.map { it.take(8) },
    )
}

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
    val artifactId = args.parseLong("artifactId")
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
//  query_conversation — cross-conversation history query
// ============================================================

private fun QueryConversationTool(
    service: AgentMailboxService,
    callerSessionId: String?,
): Tool = Tool(
    name = "query_conversation",
    description = buildString {
        appendLine("Retrieve the full message history of a specific conversation.")
        appendLine()
        appendLine("Use this when you need to reference a message from a DIFFERENT conversation")
        appendLine("than the one you're currently processing.  The wake board only shows the active")
        appendLine("conversation's messages inline; other conversations' histories must be fetched")
        appendLine("explicitly via this tool.")
        appendLine()
        appendLine("The conversation ID is the root messageId shown as conv#<N> on the wake board.")
        appendLine()
        appendLine("Returns a JSON array of messages in chronological order, each with full body,")
        appendLine("sender/recipient, kind, priority, timestamps, and ack status.")
        appendLine()
        appendLine("Note: only messages where the caller is sender or recipient are visible.")
        appendLine("Two-party conversations are the current model; multi-party is not yet supported.")
    },
    parameters = listOf(
        ToolParameter(
            "conversationId", "string",
            "The conversation ID (root messageId) to query. Shown as conv#<N> on the wake board.",
            required = true,
        ),
        ToolParameter(
            "limit", "integer",
            "Maximum messages to return (most recent first). Default 50. Cap at 500.",
            required = false,
        ),
    ),
) { args ->
    val sessionId = callerSessionId
        ?: return@Tool "Error: query_conversation has no caller sessionId"
    val convId = args.parseConversationId("conversationId")
        ?: return@Tool "Error: 'conversationId' is required. Use the conv#N number from the wake board, e.g. conversationId=2 or conversationId=\"conv#2\"."
    val limit = (args["limit"] as? Number)?.toInt()?.coerceIn(1, 500) ?: 50

    // Verify the caller is a participant of this conversation.
    // The same check lives inside [getConversationMessages] but we
    // need a clear "you are not a participant" error message here,
    // so we run the auth check up-front and only delegate the
    // actual fetch on success.
    val participants = ConversationRegistry.participants(convId)
    if (participants.isNotEmpty() && sessionId !in participants) {
        return@Tool toolMapper.writeValueAsString(
            mapOf(
                "conversationId" to convId,
                "status" to "error",
                "error" to "caller is not a participant of this conversation",
                "participants" to participants.map { it.take(8) },
            )
        )
    }

    try {
        val convMsgs = service.getConversationMessages(
            sessionId = sessionId,
            conversationId = convId,
            limit = limit,
        )
        val info = ConversationRegistry.get(convId)
        toolMapper.writeValueAsString(
            mapOf(
                "conversationId" to convId,
                "status" to if (info?.closed == true) "closed" else "active",
                "closedBy" to info?.closedBy?.take(8),
                "closedAt" to info?.closedAt,
                "participants" to participants.map { it.take(8) },
                "messageCount" to convMsgs.size,
                "messages" to convMsgs.map { m ->
                    mapOf(
                        "messageId" to m.messageId,
                        "senderSessionId" to m.senderSessionId.take(8),
                        "recipientSessionId" to m.recipientSessionId.take(8),
                        "kind" to m.kind,
                        "subject" to m.subject,
                        "body" to m.body,
                        "priority" to m.priority,
                        "acked" to (m.ackedAt != null),
                        "createdAt" to m.createdAt,
                    )
                },
            )
        )
    } catch (e: Exception) {
        "Error: query_conversation failed: ${e.message ?: e.javaClass.simpleName}"
    }
}

// ============================================================
//  query_conversations — list all conversations for this agent
// ============================================================

private fun QueryConversationsTool(
    service: AgentMailboxService,
    callerSessionId: String?,
): Tool = Tool(
    name = "query_conversations",
    description = buildString {
        appendLine("List all conversations the caller is a participant in, with status and summary.")
        appendLine()
        appendLine("Returns a JSON array of conversations, each with:")
        appendLine("  - conversationId (the root messageId, shown as conv#<N> on the wake board)")
        appendLine("  - status: active | closed")
        appendLine("  - participants: session id prefixes")
        appendLine("  - unhandledCount: messages still pending (seen but not acked)")
        appendLine("  - lastMessagePreview: one-line preview of the most recent message")
        appendLine("  - lastMessageAt: timestamp of the most recent message")
        appendLine()
        appendLine("Use this to get an overview of all ongoing conversations, e.g. when deciding")
        appendLine("which conversation to process next or whether to close any stale threads.")
        appendLine()
        appendLine("Optional filter: status=active|closed to limit results.")
    },
    parameters = listOf(
        ToolParameter(
            "status", "string",
            "Filter by status. 'active' (default) shows only open conversations; " +
                "'closed' shows only closed ones; 'all' shows both.",
            required = false,
            enum = listOf("active", "closed", "all"),
        ),
        ToolParameter(
            "limit", "integer",
            "Maximum conversations to return. Default 20.",
            required = false,
        ),
    ),
) { args ->
    val sessionId = callerSessionId
        ?: return@Tool "Error: query_conversations has no caller sessionId"
    val statusFilter = (args["status"] as? String)?.lowercase() ?: "active"
    val limit = (args["limit"] as? Number)?.toInt()?.coerceIn(1, 100) ?: 20

    try {
        // Delegate to the shared service so the LLM tool and the
        // HTTP /conversations route use the same derivation logic.
        val summaries = service.listConversations(
            sessionId = sessionId,
            statusFilter = statusFilter,
            limit = limit,
        )
        toolMapper.writeValueAsString(
            mapOf(
                "sessionId" to sessionId.take(8),
                "filter" to statusFilter,
                "totalConversations" to summaries.size,
                "returned" to summaries.size,
                "conversations" to summaries.map { s ->
                    mapOf(
                        "conversationId" to s.conversationId,
                        "status" to s.status,
                        "closedBy" to s.closedBy?.take(8),
                        "participants" to s.participants.map { it.take(8) },
                        "messageCount" to s.messageCount,
                        "unhandledCount" to s.unhandledCount,
                        "lastMessagePreview" to s.lastMessagePreview,
                        "lastMessageKind" to s.lastMessageKind,
                        "lastMessageAt" to s.lastMessageAt,
                    )
                },
            )
        )
    } catch (e: Exception) {
        "Error: query_conversations failed: ${e.message ?: e.javaClass.simpleName}"
    }
}

// ============================================================
//  await_condition — declare a wake condition and park
// ============================================================

private fun AwaitConditionTool(
    service: AgentMailboxService,
    callerSessionId: String?,
): Tool = Tool(
    name = "await_condition",
    description = buildString {
        appendLine("Declare a composable wake condition and register it with the framework.")
        appendLine()
        appendLine("After calling this tool, produce a Final Answer to park. The framework will")
        appendLine("wake you when the condition is satisfied (a synthetic message will be sent")
        appendLine("to your mailbox, and the dispatcher will resume you from standby).")
        appendLine()
        appendLine("Condition types (pass exactly one of the type parameters):")
        appendLine("  - message_arrived: wake when a new message arrives (optionally from a")
        appendLine("      specific sender, of a specific kind, or with minPriority).")
        appendLine("  - state_changed: wake when a specific agent reaches a specific state")
        appendLine("      (e.g. 'closed' or 'error').")
        appendLine("  - time_elapsed: wake after `timeoutMs` milliseconds (timeout/fallback).")
        appendLine()
        appendLine("Combinators (for complex conditions, use `allOf` / `anyOf` JSON arrays):")
        appendLine("  - allOf: wake when ALL listed conditions are satisfied (barrier/join).")
        appendLine("  - anyOf: wake when ANY listed condition is satisfied (race/first-to-finish).")
        appendLine()
        appendLine("Examples:")
        appendLine("  # Wait for agent X to finish:")
        appendLine("  {\"condition\": {\"state_changed\": {\"sessionId\": \"<X>\", \"toState\": \"closed\"}}}")
        appendLine("  # Shorthand (condition wrapper omitted — also accepted):")
        appendLine("  {\"state_changed\": {\"sessionId\": \"<X>\", \"toState\": \"closed\"}}")
        appendLine()
        appendLine("  # Wait for a message from root OR a 5-minute timeout:")
        appendLine("  {\"anyOf\": [{\"message_arrived\": {\"fromSessionId\": \"<root>\"}}, " +
            "{\"time_elapsed\": {\"timeoutMs\": 300000}}]}")
        appendLine()
        appendLine("  # Wait for BOTH agent X AND agent Y to finish:")
        appendLine("  {\"allOf\": [{\"state_changed\": {\"sessionId\": \"<X>\", \"toState\": \"closed\"}}, " +
            "{\"state_changed\": {\"sessionId\": \"<Y>\", \"toState\": \"closed\"}}]}")
        appendLine()
        appendLine("Conditions are ONE-SHOT: once satisfied, they fire and are removed.")
        appendLine("If you need to wait again after waking, register a new condition.")
    },
    parameters = listOf(
        ToolParameter(
            "condition", "object",
            "A JSON object describing the wake condition. Exactly one top-level key " +
                "is expected: message_arrived | state_changed | time_elapsed | allOf | anyOf. " +
                "See the tool description for the shape of each type. " +
                "The outer 'condition' wrapper is optional — you may pass the condition " +
                "object directly as the tool arguments (e.g. {\"state_changed\": {...}}).",
            required = false,
        ),
    ),
) { args ->
    val sessionId = callerSessionId
        ?: return@Tool "Error: await_condition has no caller sessionId"

    // The LLM sometimes omits the outer "condition" wrapper and passes
    // the condition object directly as the tool arguments, e.g.
    //   {"state_changed": {"sessionId": "...", "toState": "closed"}}
    // instead of
    //   {"condition": {"state_changed": {...}}}
    //
    // When this happens, args["condition"] is null but args itself
    // contains a known condition-type key.  Detect this and treat the
    // entire args map as the condition.
    val CONDITION_KEYS = setOf("message_arrived", "state_changed", "time_elapsed", "allOf", "anyOf")

    @Suppress("UNCHECKED_CAST")
    val condRaw = args["condition"] as? Map<String, Any?>
        ?: args.keys.find { it in CONDITION_KEYS }?.let { args as Map<String, Any?> }
        ?: return@Tool "Error: 'condition' is required and must be a JSON object. " +
            "Pass either {\"condition\": {\"state_changed\": {...}}} or the shorthand " +
            "{\"state_changed\": {...}}."

    val condition = try {
        parseWakeCondition(condRaw)
            ?: return@Tool "Error: could not parse condition. Expected one of: " +
                "message_arrived, state_changed, time_elapsed, allOf, anyOf"
    } catch (e: IllegalArgumentException) {
        return@Tool "Error: ${e.message}"
    }

    val condId = WakeConditionRegistry.register(
        targetSessionId = sessionId,
        condition = condition,
        label = "await_condition_tool",
    )

    toolMapper.writeValueAsString(
        mapOf(
            "conditionId" to condId,
            "status" to "registered",
            "condition" to condition.description
        )
    )
}

/**
 * Parse a JSON-like map into a [WakeCondition] tree.
 *
 * Accepts the shape described in [AwaitConditionTool]'s description.
 */
@Suppress("UNCHECKED_CAST")
private fun parseWakeCondition(raw: Map<String, Any?>): WakeCondition? {
    if (raw.isEmpty()) return null
    if (raw.size != 1) {
        throw IllegalArgumentException(
            "condition must contain exactly one top-level key: " +
                "message_arrived | state_changed | time_elapsed | allOf | anyOf"
        )
    }
    val (key, value) = raw.entries.first()
    return when (key) {
        "message_arrived" -> {
            val m = value as? Map<String, Any?> ?: emptyMap()
            MessageArrived(
                fromSessionId = m["fromSessionId"] as? String,
                kind = m["kind"] as? String,
                minPriority = (m["minPriority"] as? Number)?.toInt() ?: 0,
            )
        }
        "state_changed" -> {
            val m = value as? Map<String, Any?> ?: emptyMap()
            val sid = m["sessionId"] as? String
                ?: throw IllegalArgumentException("state_changed requires 'sessionId'")
            val state = m["toState"] as? String
                ?: throw IllegalArgumentException("state_changed requires 'toState'")
            StateChanged(sessionId = sid, toState = state)
        }
        "time_elapsed" -> {
            val m = value as? Map<String, Any?> ?: emptyMap()
            val ms = (m["timeoutMs"] as? Number)?.toLong()
                ?: throw IllegalArgumentException("time_elapsed requires 'timeoutMs'")
            TimeElapsed(durationMs = ms)
        }
        "allOf" -> {
            val list = value as? List<Map<String, Any?>>
                ?: throw IllegalArgumentException("allOf requires a JSON array of conditions")
            AllOf(list.mapNotNull { parseWakeCondition(it) })
        }
        "anyOf" -> {
            val list = value as? List<Map<String, Any?>>
                ?: throw IllegalArgumentException("anyOf requires a JSON array of conditions")
            AnyOf(list.mapNotNull { parseWakeCondition(it) })
        }
        else -> null
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
