package org.iotsplab.akiba.llm.agent

import org.apache.logging.log4j.LogManager
import org.iotsplab.akiba.data.database.AgentDatabaseClient

// ============================================================
//  AgentMailboxService — inter-agent mailbox + artifact façade
// ============================================================

/**
 * Façade over [AgentDatabaseClient] that enforces the mailbox
 * access policy before issuing SQL.
 *
 * Construct one per [AgentModule] (or share a singleton when the
 * orchestrator wants a single global view of all mailboxes).
 */
class AgentMailboxService(
    private val agentDbClient: AgentDatabaseClient,
    /**
     * Hard cap on the number of unread messages returned by a
     * single [drain] call. Default 50 matches the DB route's default.
     */
    val defaultDrainLimit: Int = 50,
) {
    private val logger = LogManager.getLogger(AgentMailboxService::class.java)

    // ---- Mailbox: send -------------------------------------------------

    /**
     * Send a mailbox message. Returns the new message id, or throws
     * [MailboxAccessException] when the access policy forbids the
     * send. The exception message is LLM-friendly.
     */
    @Throws(MailboxAccessException::class)
    fun send(
        senderSessionId: String,
        recipientSessionId: String,
        kind: String = "note",
        subject: String? = null,
        body: String,
        relatedArtifactId: Long? = null,
        inReplyToMessageId: Long? = null,
        priority: Int = 0,
    ): Long {
        try {
            return agentDbClient.sendMailboxMessage(
                senderSessionId = senderSessionId,
                recipientSessionId = recipientSessionId,
                kind = kind,
                subject = subject,
                body = body,
                relatedArtifactId = relatedArtifactId,
                inReplyToMessageId = inReplyToMessageId,
                priority = priority,
            )
        } catch (e: Exception) {
            // DB route re-checks the lifecycle invariant and refuses
            // sends to one-shot terminal sessions with HTTP 403; surface
            // as a structured exception so the calling tool can render a
            // clean LLM-facing message.
            throw MailboxAccessException(
                "send failed: ${e.message ?: e.javaClass.simpleName}",
                cause = e,
            )
        }
    }

    // ---- Mailbox: drain / peek / ack ----------------------------------

    /** Drain the recipient's unread inbox atomically (read + mark). */
    fun drain(sessionId: String, limit: Int = defaultDrainLimit): List<AgentDatabaseClient.MailboxMessageInfo> =
        try {
            agentDbClient.drainMailboxMessages(sessionId, limit)
        } catch (e: Exception) {
            logger.warn("drain($sessionId, limit=$limit) failed: ${e.message}")
            emptyList()
        }

    /** Peek the inbox without marking messages read. */
    fun peek(sessionId: String, limit: Int = defaultDrainLimit, includeRead: Boolean = false):
        List<AgentDatabaseClient.MailboxMessageInfo> =
        try {
            agentDbClient.listMailboxMessages(sessionId, limit, includeRead)
        } catch (e: Exception) {
            logger.warn("peek($sessionId) failed: ${e.message}")
            emptyList()
        }

    fun ack(sessionId: String, messageId: Long): Boolean = try {
        agentDbClient.ackMailboxMessage(sessionId, messageId); true
    } catch (e: Exception) {
        logger.warn("ack($sessionId, $messageId) failed: ${e.message}")
        false
    }

    /**
     * Single-message lookup. The session must be sender or recipient
     * of the row (enforced at the DB layer). Returns null when the
     * row does not exist or the caller is not party to it.
     */
    fun getMessage(sessionId: String, messageId: Long): AgentDatabaseClient.MailboxMessageInfo? =
        try {
            agentDbClient.getMailboxMessage(sessionId, messageId)
        } catch (e: Exception) {
            logger.warn("getMessage($sessionId, $messageId) failed: ${e.message}")
            null
        }

    /** Cheap unread-count check (zero unread short-circuits the drain). */
    fun countUnread(sessionId: String): Int = try {
        agentDbClient.countUnreadMailbox(sessionId)
    } catch (e: Exception) {
        logger.warn("countUnread($sessionId) failed: ${e.message}")
        0
    }

    // ---- Artifacts -----------------------------------------------------

    /**
     * Publish a named artifact. Returns the artifact id. Default upsert
     * semantics: a row with the same `(owner, name, version)` has its
     * content replaced. Pass `version` explicitly to keep old versions
     * for audit / rollback.
     */
    fun publish(
        ownerSessionId: String,
        name: String,
        kind: String = "data",
        content: String,
        summary: String? = null,
        metadata: String? = null,
        isPublic: Boolean = false,
        version: Int = 1,
    ): Long = try {
        agentDbClient.publishArtifact(
            ownerSessionId = ownerSessionId,
            name = name,
            kind = kind,
            content = content,
            summary = summary,
            metadata = metadata,
            isPublic = isPublic,
            version = version,
        )
    } catch (e: Exception) {
        throw MailboxAccessException(
            "publish failed: ${e.message ?: e.javaClass.simpleName}",
            cause = e,
        )
    }

    fun get(
        callerSessionId: String?,
        artifactId: Long? = null,
        ownerSessionId: String? = null,
        name: String? = null,
        version: Int? = null,
    ): AgentDatabaseClient.ArtifactInfo? = try {
        agentDbClient.getArtifact(callerSessionId, artifactId, ownerSessionId, name, version)
    } catch (e: Exception) {
        logger.warn("get(artifact) failed: ${e.message}")
        null
    }

    fun list(
        callerSessionId: String,
        ownerSessionId: String? = null,
        name: String? = null,
        includePublic: Boolean = true,
        limit: Int = 50,
    ): List<AgentDatabaseClient.ArtifactInfo> = try {
        agentDbClient.listArtifacts(callerSessionId, ownerSessionId, name, includePublic, limit)
    } catch (e: Exception) {
        logger.warn("list(artifacts) failed: ${e.message}")
        emptyList()
    }

    fun delete(callerSessionId: String, artifactId: Long): Boolean = try {
        agentDbClient.deleteArtifact(callerSessionId, artifactId); true
    } catch (e: Exception) {
        logger.warn("delete($callerSessionId, artifactId=$artifactId) failed: ${e.message}")
        false
    }
}

/**
 * Thrown by [AgentMailboxService.send] / [publish] when the DB route
 * rejects the request because the sender / recipient / owner failed
 * the access policy.
 */
class MailboxAccessException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

// ============================================================
//  applyMailboxDrain — default beforeIteration drain helper
// ============================================================

/**
 * Drain the current session's mailbox and convert the unread
 * messages into an [AgentHarnessDirective] that the strategy loop
 * surfaces as a user turn.
 *
 * Bounded by [maxMessages] (default 8) and [maxChars] (default 2000);
 * when more remain unread a pointer line instructs the LLM to call
 * `read_agent_messages action=drain limit=N` for the full list.
 *
 * Returns [AgentHarnessDirective.None] when the context has no
 * sessionId, the service is null, or there are zero unread messages.
 * The drain is best-effort: SQL failures are logged and surface as an
 * empty drain so the loop continues. `drainMailboxMessages` marks
 * returned rows as read, so the next drain won't re-deliver them.
 */
fun applyMailboxDrain(
    ctx: StrategyContext,
    mailboxService: AgentMailboxService?,
    maxMessages: Int = 8,
    maxChars: Int = 2_000,
): AgentHarnessDirective {
    if (mailboxService == null) return AgentHarnessDirective.None
    val sessionId = ctx.sessionId ?: return AgentHarnessDirective.None

    val unread = mailboxService.countUnread(sessionId)
    if (unread <= 0) return AgentHarnessDirective.None

    // DB limit is 500; snapshot limit is much smaller (8 by default).
    val drained = mailboxService.drain(sessionId, limit = maxMessages.coerceAtLeast(1))
    if (drained.isEmpty()) return AgentHarnessDirective.None

    val header = buildString {
        appendLine("[mailbox drain] ${drained.size} new message(s) for session $sessionId" +
            if (unread > drained.size) " (+${unread - drained.size} more unread — call " +
                "`read_agent_messages action=drain` with a larger limit for the full list)" else "")
        appendLine("Process each one and reply via `send_agent_message kind=reply in_reply_to=<messageId>` " +
            "or via `ack_agent_message messageId=<messageId>` once handled. Sender / recipient ids are " +
            "preserved on every reply so the thread stays routable.")
        appendLine()
    }

    val body = buildString {
        for (m in drained) {
            val senderShort = m.senderSessionId.take(8)
            appendLine("--- messageId=${m.messageId} from $senderShort kind=${m.kind}" +
                (m.subject?.takeIf { it.isNotBlank() }?.let { " subject=\"$it\"" } ?: "") +
                (m.priority.takeIf { it != 0 }?.let { " priority=$it" } ?: "") + " ---")
            m.relatedArtifactId?.let { appendLine("(attached artifactId=$it)") }
            m.inReplyToMessageId?.let { appendLine("(in reply to messageId=$it)") }
            appendLine(m.body.trim())
            appendLine()
        }
    }

    val full = header + body
    val snapshot = if (full.length > maxChars) {
        full.substring(0, maxChars) +
            "\n... (truncated; ${drained.size - 1} more message(s) readable via read_agent_messages)"
    } else full

    return AgentHarnessDirective.userMessage(snapshot.trimEnd())
}
