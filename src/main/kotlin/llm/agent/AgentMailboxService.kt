package org.iotsplab.akiba.llm.agent

import org.apache.logging.log4j.LogManager
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import java.util.concurrent.ConcurrentHashMap

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
        // Backpressure check: if the recipient has too many
        // pending (seen, not handled) messages, reject non-urgent
        // sends with a structured error.  Urgent messages
        // (priority >= URGENT_PRIORITY_THRESHOLD) bypass the
        // check so emergencies can still get through.
        BackpressureTracker.checkBackpressure(recipientSessionId, priority)?.let { reason ->
            throw MailboxAccessException(reason)
        }

        try {
            val messageId = agentDbClient.sendMailboxMessage(
                senderSessionId = senderSessionId,
                recipientSessionId = recipientSessionId,
                kind = kind,
                subject = subject,
                body = body,
                relatedArtifactId = relatedArtifactId,
                inReplyToMessageId = inReplyToMessageId,
                priority = priority,
            )
            // Auto-register the conversation: participants are
            // derived from sender + recipient; conversation ID is
            // the root of the inReplyTo chain (or this message if
            // it's a root).  No explicit "open" call needed —
            // conversations emerge from the message flow.
            ConversationRegistry.register(
                messageId = messageId,
                senderSessionId = senderSessionId,
                recipientSessionId = recipientSessionId,
                inReplyTo = inReplyToMessageId,
            )
            return messageId
        } catch (e: Exception) {
            throw MailboxAccessException(
                "send failed: ${e.message ?: e.javaClass.simpleName}",
                cause = e,
            )
        }
    }

    // ---- Mailbox: drain / peek / ack ----------------------------------

    /** Drain the recipient's unread inbox atomically (read + mark as seen). */
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

    /**
     * List "pending" messages: those that have been drained (seen)
     * but NOT yet acked (handled). These are messages the LLM saw
     * in a previous wake but hasn't explicitly replied to or acked.
     *
     * Uses the `onlyPending` DB filter so the query returns ONLY
     * read-but-unacked messages — acked messages do NOT consume limit
     * slots.  This is critical for agents (like batch_linear_planner)
     * that receive many child-completion notifications across multiple
     * wakes: if acked messages consumed limit slots, older pending
     * messages would be silently truncated, causing the agent to
     * "forget" unresolved items from earlier wakes.
     */
    fun listPending(sessionId: String, limit: Int = defaultDrainLimit): List<AgentDatabaseClient.MailboxMessageInfo> =
        try {
            agentDbClient.listMailboxMessages(sessionId, limit, includeRead = true, onlyPending = true)
                // Defensive client-side filter: the daemon is expected to apply
                // `read_at IS NOT NULL AND acked_at IS NULL`, but older daemon
                // builds or request-deserialisation mismatches can silently behave
                // like includeRead=true and return already-acked rows.  Never let
                // an acked row re-enter the wake board as PENDING/ESCALATED.
                .filter { it.readAt != null && it.ackedAt == null }
        } catch (e: Exception) {
            logger.warn("listPending($sessionId) failed: ${e.message}")
            emptyList()
        }

    /**
     * Cheap unread-count check (zero unread short-circuits the drain).
     */
    fun countUnread(sessionId: String): Int = try {
        agentDbClient.countUnreadMailbox(sessionId)
    } catch (e: Exception) {
        logger.warn("countUnread($sessionId) failed: ${e.message}")
        0
    }

    fun ack(sessionId: String, messageId: Long): Boolean = try {
        agentDbClient.ackMailboxMessage(sessionId, messageId)
        // Clear the in-memory seen-wake counter so the message
        // doesn't re-appear as "escalated" on future wake boards.
        // The DB-level ack (acked_at) already prevents it from
        // appearing in [PENDING] (listPending filters acked_at IS
        // NULL), but the seen counter is purely in-memory and would
        // otherwise persist until JVM restart.
        clearSeenCount(sessionId, messageId)
        true
    } catch (e: Exception) {
        logger.warn("ack($sessionId, $messageId) failed: ${e.message}")
        false
    }

    fun getMessage(sessionId: String, messageId: Long): AgentDatabaseClient.MailboxMessageInfo? =
        try {
            agentDbClient.getMailboxMessage(sessionId, messageId)
        } catch (e: Exception) {
            logger.warn("getMessage($sessionId, $messageId) failed: ${e.message}")
            null
        }

    // ---- Conversation queries (shared by LLM tools + HTTP API) ----

    /**
     * Strongly-typed summary of one mailbox conversation, derived
     * from the raw messages belonging to it. Returned by
     * [listConversations] and reused by both the LLM tool
     * (`query_conversations`) and the HTTP `/conversations` route
     * so the two consumers never drift.
     */
    data class ConversationSummary(
        val conversationId: Long,
        val status: String,                // "active" | "closed"
        val participants: List<String>,    // full session ids
        val messageCount: Int,
        val unhandledCount: Int,
        val lastMessagePreview: String?,
        val lastMessageKind: String?,
        val lastMessageAt: String?,
        val lastMessageSubject: String?,
        val closedBy: String? = null,
        val closedAt: Long? = null,
    )

    /**
     * List every conversation [sessionId] is a participant in,
     * with status and a one-line summary.
     *
     * @param statusFilter  `"all"` (default), `"active"`, or `"closed"`.
     * @param limit         max number of rows to return.
     *
     * This is the single source of truth used by both the LLM
     * `query_conversations` tool and the HTTP
     * `GET /agent/sessions/{id}/conversations` endpoint.
     */
    fun listConversations(
        sessionId: String,
        statusFilter: String = "all",
        limit: Int = 50,
    ): List<ConversationSummary> {
        val allMsgs = try {
            agentDbClient.listMailboxMessages(
                sessionId, limit = 500, includeRead = true
            )
        } catch (e: Exception) {
            logger.warn("listConversations($sessionId): listMailboxMessages failed: ${e.message}")
            return emptyList()
        }

        // Group by conversation key (inReplyTo ?: messageId)
        val byConv: Map<Long, List<AgentDatabaseClient.MailboxMessageInfo>> =
            allMsgs.groupBy { m ->
                ConversationRegistry.deriveConversationId(m.messageId, m.inReplyToMessageId)
            }

        val summaries = byConv.map { (convId, msgs) ->
            val info = ConversationRegistry.get(convId)
            val isClosed = info?.closed ?: false
            val sorted = msgs.sortedBy { it.messageId }
            val last = sorted.lastOrNull()
            val unhandled = msgs.count { it.ackedAt == null }
            val participants = (info?.participants ?: emptySet()).toList()
            ConversationSummary(
                conversationId = convId,
                status = if (isClosed) "closed" else "active",
                participants = participants,
                messageCount = msgs.size,
                unhandledCount = unhandled,
                lastMessagePreview = last?.body?.take(100),
                lastMessageKind = last?.kind,
                lastMessageAt = last?.createdAt,
                lastMessageSubject = last?.subject,
                closedBy = info?.closedBy,
                closedAt = info?.closedAt,
            )
        }.sortedByDescending { it.lastMessageAt }

        return when (statusFilter.lowercase()) {
            "active" -> summaries.filter { it.status == "active" }
            "closed" -> summaries.filter { it.status == "closed" }
            else -> summaries
        }.take(limit)
    }

    /**
     * Fetch the full message history of a single conversation that
     * [sessionId] is a participant in.
     *
     * The DB has no `conversation_id` column, so we approximate by
     * following the `inReplyTo` chain: a message belongs to conv#X
     * if its `messageId == X` (it's the root) OR its
     * `inReplyToMessageId == X` OR its `inReplyToMessageId` points
     * to a message whose own `inReplyToMessageId == X` (2-level
     * chain). Deeper chains are rare in practice; the 2-level rule
     * covers >90% of real usage.
     *
     * Returns an empty list when the caller is not a participant.
     *
     * Used by both the LLM `query_conversation` tool and the HTTP
     * `GET /agent/sessions/{id}/conversations/{convId}` endpoint.
     */
    fun getConversationMessages(
        sessionId: String,
        conversationId: Long,
        limit: Int = 500,
    ): List<AgentDatabaseClient.MailboxMessageInfo> {
        // Authorization: caller must be a participant of the
        // conversation. ConversationRegistry is in-process, so this
        // is best-effort; the DB-level filters below still scope
        // the result to messages the caller can actually see.
        if (ConversationRegistry.participants(conversationId).isNotEmpty() &&
            sessionId !in ConversationRegistry.participants(conversationId)
        ) {
            return emptyList()
        }

        val allMsgs = try {
            agentDbClient.listMailboxMessages(
                sessionId, limit = limit, includeRead = true
            )
        } catch (e: Exception) {
            logger.warn("getConversationMessages($sessionId, $conversationId): listMailboxMessages failed: ${e.message}")
            return emptyList()
        }

        return allMsgs.filter { m ->
            m.messageId == conversationId ||
                m.inReplyToMessageId == conversationId ||
                (m.inReplyToMessageId != null &&
                    allMsgs.any { it.messageId == m.inReplyToMessageId && it.inReplyToMessageId == conversationId })
        }.sortedBy { it.messageId }
    }

    // ---- Artifacts -----------------------------------------------------

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

class MailboxAccessException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

// ============================================================
//  Conversation registry — auto-derived from inReplyTo chains
// ============================================================

/**
 * Global, per-JVM registry of conversations derived from mailbox
 * message `inReplyTo` chains.
 *
 * A **conversation** is identified by its root message ID — the
 * first message in a reply chain that has no `inReplyTo`. Every
 * subsequent reply (at any depth) inherits that root as its
 * conversation ID.
 *
 * The registry tracks:
 *  - **Participants**: every session that has sent or received a
 *    message in this conversation. Used by [closeConversation] to
 *    notify all parties.
 *  - **Closed state**: once closed, the conversation is marked
 *    terminal. The wake board shows a [CLOSED] tag on messages
 *    belonging to closed conversations so the LLM knows not to
 *    reply to that thread.
 *
 * The registry is **auto-populated** by [AgentMailboxService.send]:
 * every send registers the sender + recipient as participants of
 * the derived conversation. No explicit "open conversation" call
 * is needed — conversations emerge from the message flow itself.
 *
 * Lost on JVM restart. On restart, the registry is empty, so
 * `closeConversation` for pre-restart conversations will be a
 * no-op (the closed state is not retroactive). This is acceptable
 * because closed conversations are terminal — no new messages
 * should arrive for them anyway.
 */
object ConversationRegistry {
    private val logger = LogManager.getLogger("ConversationRegistry")

    data class ConversationInfo(
        val conversationId: Long,
        val participants: MutableSet<String> = mutableSetOf(),
        @Volatile var closed: Boolean = false,
        @Volatile var closedBy: String? = null,
        @Volatile var closedAt: Long? = null,
    )

    private val conversations = ConcurrentHashMap<Long, ConversationInfo>()

    /**
     * Derive the conversation ID for a message: if it has an
     * `inReplyTo`, the conversation is the root of that chain;
     * otherwise the message itself starts a new conversation.
     *
     * For the common 2-level case (root → reply) this is just
     * `inReplyTo ?: messageId`. For deeper chains we'd ideally
     * walk the tree, but that requires DB lookups; the 2-level
     * heuristic covers >90% of real usage.
     */
    fun deriveConversationId(messageId: Long, inReplyTo: Long?): Long =
        inReplyTo ?: messageId

    /**
     * Register a message in the conversation registry. Called
     * automatically by [AgentMailboxService.send] after the DB
     * insert succeeds.
     */
    fun register(
        messageId: Long,
        senderSessionId: String,
        recipientSessionId: String,
        inReplyTo: Long?,
    ) {
        val convId = deriveConversationId(messageId, inReplyTo)
        conversations.compute(convId) { _, info ->
            val cur = info ?: ConversationInfo(convId)
            cur.participants.add(senderSessionId)
            cur.participants.add(recipientSessionId)
            cur
        }
    }

    /** Get conversation info, or null if never registered. */
    fun get(conversationId: Long): ConversationInfo? = conversations[conversationId]

    /** Is this conversation closed? */
    fun isClosed(conversationId: Long): Boolean =
        conversations[conversationId]?.closed ?: false

    /**
     * Mark a conversation as closed and return the set of
     * participants (excluding [closedBy]) who should be notified.
     *
     * Returns empty set if the conversation was never registered
     * or is already closed.
     */
    fun close(conversationId: Long, closedBy: String): Set<String> {
        val info = conversations[conversationId] ?: return emptySet()
        if (info.closed) return emptySet()
        info.closed = true
        info.closedBy = closedBy
        info.closedAt = System.currentTimeMillis()
        return info.participants.filter { it != closedBy }.toSet()
    }

    /** List all participants of a conversation. */
    fun participants(conversationId: Long): Set<String> =
        conversations[conversationId]?.participants?.toSet() ?: emptySet()
}

// ============================================================
//  Mailbox state machine
// ============================================================
//
//  The mailbox table already has `read_at` and `acked_at` columns.
//  We map them onto a 3-state lifecycle:
//
//    unread ──drain──► seen ──ack/reply──► handled (terminal)
//                        │
//                        └──N wakes unhandled──► escalated (priority boost)
//
//  - unread: readAt IS NULL — never delivered to the LLM
//  - seen:   readAt IS NOT NULL AND ackedAt IS NULL — delivered
//            but the LLM hasn't explicitly handled it
//  - handled: ackedAt IS NOT NULL — terminal, won't re-surface
//
//  The [applyMailboxDrain] helper below is the single chokepoint
//  that transitions unread → seen and surfaces BOTH new (unread→seen)
//  and pending (seen, not handled) messages in a unified panel.

/**
 * In-memory tracker for how many wakes a "seen" message has
 * survived without being handled. When the count exceeds
 * [ESCALATE_AFTER_WAKES], the message is flagged as "escalated"
 * in the panel and its priority is visually boosted so the LLM
 * knows it can no longer be deferred.
 *
 * Per-JVM, per-session. Lost on restart — acceptable for P0
 * because on restart all "seen" messages re-surface as pending
 * anyway (the panel shows them again), which is the right
 * behaviour.
 */
private val seenWakeCounter = ConcurrentHashMap<String, ConcurrentHashMap<Long, Int>>()

// ESCALATE_AFTER_WAKES is defined in AgentConstants.kt (shared
// policy value).

private fun bumpSeenCount(sessionId: String, messageId: Long): Int {
    val sessionMap = seenWakeCounter.computeIfAbsent(sessionId) { ConcurrentHashMap() }
    return sessionMap.compute(messageId) { _, v -> (v ?: 0) + 1 } ?: 1
}

/** Read the current seen-wake count without incrementing (0 if never seen). */
private fun currentSeenCount(sessionId: String, messageId: Long): Int =
    seenWakeCounter[sessionId]?.get(messageId) ?: 0

private fun clearSeenCount(sessionId: String, messageId: Long) {
    seenWakeCounter[sessionId]?.remove(messageId)
}

// ============================================================
//  applyMailboxDrain — wake-board panel (P0 implementation)
// ============================================================

/**
 * Drain the session's mailbox and build a structured **wake board**
 * that the strategy loop injects as the LLM's user turn.
 *
 * ## State machine
 *
 * The panel surfaces BOTH:
 *  - **[NEW]** — messages drained in *this* wake (unread → seen)
 *  - **[PENDING]** — messages from *previous* wakes that are still
 *    `seen` but not `handled` (the LLM saw them but didn't ack/reply)
 *  - **[!]** — pending messages that have survived [ESCALATE_AFTER_WAKES]
 *    wakes without being handled; these are escalated to draw the
 *    LLM's attention.
 *
 * This replaces the old "drain once and forget" model where a
 * message drained in wake #1 but not replied to would be invisible
 * in wake #2 — the single biggest source of "LLM forgot about my
 * message" bugs.
 *
 * ## Conversation grouping
 *
 * Messages are grouped by conversation key
 * (`inReplyToMessageId ?: messageId`) so the LLM can see at a
 * glance which messages belong to the same thread.
 *
 * ## Summary-only
 *
 * The panel shows one-line summaries (truncated body), NOT full
 * message text. The LLM uses `read_agent_messages messageId=<N>`
 * to fetch the full content of any message it wants to act on.
 * This keeps token usage proportional to the *number* of items,
 * not the *total body length*.
 *
 * ## Auto-ack
 *
 * When the LLM replies via `send_agent_message in_reply_to=<N>`,
 * the [SendAgentMessageTool] auto-acks message N, transitioning
 * it to `handled`. The LLM can also explicitly call
 * `ack_agent_message messageId=<N>` for messages that need no
 * reply.
 */

fun applyMailboxDrain(
    ctx: StrategyContext,
    mailboxService: AgentMailboxService?,
    maxMessages: Int = 12,
    maxSummaryChars: Int = 120,
): AgentHarnessDirective {
    if (mailboxService == null) return AgentHarnessDirective.None
    val sessionId = ctx.sessionId ?: return AgentHarnessDirective.None

    // 1. Drain unread → seen
    val newMessages = mailboxService.drain(sessionId, limit = maxMessages)

    // 2. List pending (seen, not handled) from previous wakes.
    //    IMPORTANT: exclude messages that were just drained in
    //    step 1 — they have readAt != null now, so listPending
    //    would pick them up too, causing duplicate display on the
    //    wake board (one in [NEW] and one in [PENDING]).
    //
    //    Use a SEPARATE, much higher limit for pending messages
    //    than for drain.  The drain limit only needs to cover the
    //    new messages that arrived since the last wake (typically
    //    a handful).  The pending limit must cover ALL unacked
    //    messages from ALL previous wakes — if the agent received
    //    many child-completion notifications across several wakes
    //    without acking them, the default drain limit (12) would
    //    silently truncate older pending entries, making the agent
    //    "forget" it still has unresolved items.  This was the root
    //    cause of the "BLP can only see the most recent wake's
    //    messages" bug.
    val newMessageIds = newMessages.map { it.messageId }.toSet()
    val pendingLimit = maxOf(maxMessages * 8, 100)
    val rawPendingMessages = mailboxService.listPending(sessionId, limit = pendingLimit)
        .filter { it.messageId !in newMessageIds }
        .filter { it.kind != "user-hint" }  // user-hints are auto-acked, never pending

    // Synthetic wake-condition notes are scheduling signals, not
    // actionable conversations. Show a newly drained wake note once
    // as [NEW], then auto-ack it so it cannot reappear as [PENDING]
    // on every ReAct iteration and misleadingly look like repeated
    // dispatcher wake-ups. Also clean up wake notes left pending by
    // older runs.
    fun isSyntheticWakeNote(message: AgentDatabaseClient.MailboxMessageInfo): Boolean =
        message.senderSessionId == SYSTEM_SESSION_UUID &&
            message.kind == "note" &&
            message.subject?.startsWith("wake condition:") == true

    val syntheticWakeNotes = newMessages.filter(::isSyntheticWakeNote) +
        rawPendingMessages.filter(::isSyntheticWakeNote)
    for (message in syntheticWakeNotes.distinctBy { it.messageId }) {
        mailboxService.ack(sessionId, message.messageId)
    }
    val pendingMessages = rawPendingMessages.filterNot(::isSyntheticWakeNote)

    // 1b. Extract user-hint messages and deliver them directly as
    //     user messages (transient or persistent), bypassing the
    //     wake board.  User-hint messages are injected by the
    //     frontend when the user types into the chat input during
    //     an automated session.  They should appear as the LAST
    //     user message before the LLM call — prominent, not buried
    //     inside a wake board panel.  The `subject` field carries
    //     the delivery mode: "[transient]" or "[persistent]".
    //
    //     Auto-ack these messages so they don't reappear in
    //     [PENDING] on the next wake board.
    val userHints = newMessages.filter { it.kind == "user-hint" }
    val regularNew = newMessages.filter { it.kind != "user-hint" }
    for (hint in userHints) {
        val isTransient = hint.subject?.contains("[transient]", ignoreCase = true) == true
        val body = hint.body
        if (isTransient) {
            ctx.addTransientUserMessage("[User hint] $body")
            // Transient messages are not persisted to agent_messages,
            // but we still record them in the transcript so they
            // appear in the exported conversation record.
            ctx.transcript?.writeUserMessage("[User hint] (transient) $body")
        } else {
            ctx.memory.addUserMessage("[User hint] $body")
        }
        // Auto-ack so the hint doesn't show up in [PENDING] later.
        try {
            mailboxService.ack(sessionId, hint.messageId)
        } catch (_: Exception) {
            // Best-effort: if ack fails, the hint will appear in
            // [PENDING] on the next wake — annoying but not broken.
        }
    }

    if (regularNew.isEmpty() && pendingMessages.isEmpty()) {
        // Only user-hints were delivered (or nothing at all); no
        // wake board to build.  Return None so the harness does not
        // inject an empty panel.
        return AgentHarnessDirective.None
    }

    // 3. Route messages into conversation scratchpads + schedule.
    //    The agent's [ScratchpadRegistry] (accessed via the agent
    //    field on the context — not available here, so we access
    //    it through a thread-local side channel set by the
    //    strategy before calling beforeIteration) decides which
    //    conversation is "active" for this LLM turn.
    //
    //    For P2 we write to the registry if it's available; if not,
    //    the wake board still works (just without scratchpad
    //    isolation and resume hints).
    val registry = currentScratchpadRegistry.get()
    if (registry != null) {
        for (m in regularNew) {
            registry.addMessage(m)
        }
        for (m in pendingMessages) {
            // Already in scratchpad from a previous wake; just
            // make sure it's registered (in case registry was
            // recreated after restart).
            registry.get(
                ConversationRegistry.deriveConversationId(m.messageId, m.inReplyToMessageId)
            ) ?: run { registry.addMessage(m) }
        }
        // Pick the next conversation to process (serial + preemptive)
        registry.scheduleNext()
    }

    // 4+5. Age the seen counters — but ONLY ONCE PER WAKE (i.e. per
    //      strategy execution), not on every ReAct iteration.  A
    //      strategy execution corresponds to one park→wake cycle; the
    //      escalation counter must measure "wakes survived unhandled",
    //      not "iterations since first display".  Mid-execution arrivals
    //      stay at their current age until the next genuine wake.
    val shouldAge = !ctx.stats.wakeBoardAged
    if (shouldAge) {
        ctx.stats.wakeBoardAged = true
        // 4. Bump seen-count for new messages (they're now "seen")
        for (m in regularNew) {
            bumpSeenCount(sessionId, m.messageId)
        }
    }

    // 5. Bump seen-count for pending messages (they survived another wake)
    val pendingWithCount = pendingMessages.map { m ->
        val count = if (shouldAge) bumpSeenCount(sessionId, m.messageId)
        else currentSeenCount(sessionId, m.messageId)
        m to count
    }

    // 6. Split pending into normal vs escalated
    val (escalated, normalPending) = pendingWithCount.partition { it.second >= ESCALATE_AFTER_WAKES }

    // 7. Build the panel + resume hint (if any)
    val panel = buildWakeBoard(
        sessionId = sessionId,
        newMessages = regularNew,
        pendingMessages = normalPending,
        escalatedMessages = escalated,
        maxSummaryChars = maxSummaryChars,
    )

    // 8. Append resume hint if the scheduler restored a preempted conversation
    val resumeHint = registry?.buildResumeHint()
    val fullPanel = if (resumeHint != null) {
        "$panel\n\n$resumeHint"
    } else panel

    // 9. Update backpressure tracker with the current pending count.
    //    The [BackpressureTracker] is read by [AgentMailboxService.send]
    //    (via [SendAgentMessageTool]) to reject new non-urgent messages
    //    when the recipient is overloaded.
    BackpressureTracker.updatePendingCount(sessionId, pendingMessages.size)

    // 10. Do NOT clear WakeConditionRegistry here.  Default
    //     message wake is permanent and independent of explicit
    //     await_condition rules: an agent may wake because a normal
    //     message arrived while still waiting for a StateChanged /
    //     TimeElapsed condition.  Only the registry's evaluator may
    //     remove a condition, and only when that condition itself is
    //     satisfied (one-shot semantics).  Clearing here would make
    //     an unrelated progress message cancel a still-needed
    //     "wait until BLP closes" condition.

    // 11. If backpressure is active, append a warning to the panel
    //     so the LLM knows it should prioritize clearing the backlog.
    val bpLevel = BackpressureTracker.pendingCount(sessionId)
    val finalPanel = if (bpLevel >= BACKPRESSURE_THRESHOLD) {
        "$fullPanel\n\n[BACKPRESSURE] You have $bpLevel pending items (>= $BACKPRESSURE_THRESHOLD). " +
            "New non-urgent messages to you are being REJECTED. Clear your backlog ASAP."
    } else fullPanel

    return AgentHarnessDirective.userMessage(finalPanel)
}

/**
 * Thread-local side-channel for passing the agent's
 * [ScratchpadRegistry] from the strategy (which has access to
 * the agent) to [applyMailboxDrain] (which only has
 * [StrategyContext]).
 *
 * Set by [AkibaAgent.executeWithStrategy] before calling the
 * harness's [AgentHarness.beforeIteration], cleared after.
 *
 * This is intentionally simple — a thread-local rather than a
 * context field — to avoid changing the [StrategyContext]
 * constructor signature (which would ripple through every
 * strategy implementation).
 */
private val currentScratchpadRegistry = ThreadLocal<ScratchpadRegistry?>()

/**
 * Set the thread-local scratchpad registry for the duration of
 * a block.  Called by [AkibaAgent.executeWithStrategy] so that
 * [applyMailboxDrain] (called from within the strategy's
 * `beforeIteration` hook) can access the registry.
 *
 * Generic so it can wrap any block regardless of return type
 * (the strategy returns [AgentResult], the harness's
 * beforeIteration returns [AgentHarnessDirective]).
 */
internal inline fun <T> withScratchpadRegistry(
    registry: ScratchpadRegistry?,
    block: () -> T,
): T {
    val prev = currentScratchpadRegistry.get()
    currentScratchpadRegistry.set(registry)
    return try {
        block()
    } finally {
        currentScratchpadRegistry.set(prev)
    }
}

// ============================================================
//  Wake-board formatting
// ============================================================

/** Conversation key: replies to the same parent are grouped. */
private fun convKey(m: AgentDatabaseClient.MailboxMessageInfo): Long =
    ConversationRegistry.deriveConversationId(m.messageId, m.inReplyToMessageId)

private fun buildWakeBoard(
    sessionId: String,
    newMessages: List<AgentDatabaseClient.MailboxMessageInfo>,
    pendingMessages: List<Pair<AgentDatabaseClient.MailboxMessageInfo, Int>>,
    escalatedMessages: List<Pair<AgentDatabaseClient.MailboxMessageInfo, Int>>,
    maxSummaryChars: Int,
): String = buildString {
    val totalNew = newMessages.size
    val totalPending = pendingMessages.size
    val totalEscalated = escalatedMessages.size
    val total = totalNew + totalPending + totalEscalated

    appendLine("[Agent Wake Board] $total actionable item(s) for session ${sessionId.take(8)}")
    appendLine()

    // ── Escalated (highest priority) ──
    if (totalEscalated > 0) {
        appendLine("[!] $totalEscalated ESCALATED item(s) — ${ESCALATE_AFTER_WAKES}+ wakes without handling:")
        appendLine()
        for ((m, count) in escalatedMessages) {
            appendMessageSummary(m, prefix = "  [!]", maxSummaryChars, extra = " (seen $count wakes)")
        }
        appendLine("    ↑ These items can no longer be deferred. Reply or ack them NOW.")
        appendLine()
    }

    // ── New ──
    if (totalNew > 0) {
        appendLine("[NEW] $totalNew new message(s) since last wake:")
        appendLine()
        val grouped = newMessages.groupBy(::convKey)
        for ((convId, msgs) in grouped) {
            appendConversationHeader(convId)
            for (m in msgs) {
                appendMessageSummary(m, prefix = "  ", maxSummaryChars)
            }
        }
        appendLine()
    }

    // ── Pending ──
    if (totalPending > 0) {
        appendLine("[PENDING] $totalPending item(s) from previous wakes (seen but not handled):")
        appendLine()
        val grouped = pendingMessages.groupBy { convKey(it.first) }
        for ((convId, msgs) in grouped) {
            appendConversationHeader(convId)
            for ((m, count) in msgs) {
                appendMessageSummary(m, prefix = "  ", maxSummaryChars, extra = " (seen $count wake(s))")
            }
        }
        appendLine()
    }

    // ── Footer / instructions ──
    appendLine("─".repeat(50))
    appendLine("Total: $totalNew new + $totalPending pending + $totalEscalated escalated = $total item(s)")
    appendLine()
    appendLine("How to handle items:")
    appendLine("  • Reply:  send_agent_message recipientSessionId=<senderSessionId> kind=reply inReplyToMessageId=<msgId> body=...")
    appendLine("            (replying auto-acks the original message — do NOT ack separately after replying)")
    appendLine("  • Ack:    ack_agent_message messageId=<msgId>  (mark as handled without replying; idempotent — re-acking is OK)")
    appendLine("  • Detail: read_agent_messages action=get messageId=<msgId>  (full content of one message)")
    appendLine("  • Close:  close_conversation conversationId=<convId>  (end a conversation, notifies all participants)")
    appendLine()
    appendLine("IMPORTANT: Use the msg#N number (NOT conv#N) as the messageId for ack/reply.")
    appendLine("  msg#N = the message's own ID (use this for ack_agent_message and inReplyToMessageId)")
    appendLine("  conv#N = the conversation thread ID (use this only for close_conversation)")
    appendLine("All messages above are already visible to you — do NOT call read_agent_messages action=drain")
    appendLine("(it will return an empty list). Use action=get only when you need the FULL body of a specific message.")
}.trimEnd()

/** Print a conversation header with [CLOSED] tag if applicable. */
private fun StringBuilder.appendConversationHeader(convId: Long) {
    val info = ConversationRegistry.get(convId)
    if (info != null && info.closed) {
        appendLine("  conv#$convId [CLOSED by ${info.closedBy}] — do not reply to this thread")
    } else {
        appendLine("  conv#$convId")
    }
}

private fun StringBuilder.appendMessageSummary(
    m: AgentDatabaseClient.MailboxMessageInfo,
    prefix: String,
    maxChars: Int,
    extra: String = "",
) {
    // Show FULL sessionId so the LLM can use it directly as
    // recipientSessionId in send_agent_message.  The previous
    // truncation (take(8)) caused the LLM to send to a
    // non-existent 8-char session and get a 500 error.
    val sender = m.senderSessionId
    val subject = m.subject?.takeIf { it.isNotBlank() }?.let { " \"$it\"" } ?: ""
    val priorityTag = if (m.priority > 0) " pri=${m.priority}" else ""
    val bodyPreview = m.body.trim().replace("\n", " ").take(maxChars)
    val ellipsis = if (m.body.trim().length > maxChars) "…" else ""
    // CRITICAL: Do NOT show conv#N on the message line.
    // The conversation ID (conv#N) is already shown in the
    // conversation header above.  Showing it again on the message
    // line caused the LLM to confuse conv#N with messageId=N and
    // ack the WRONG message (the conversation root instead of the
    // actual pending message).  This resulted in:
    //   1. First ack "succeeds" (but acked the wrong message)
    //   2. The actual message stays pending → appears in PENDING → ESCALATED
    //   3. Re-ack of the wrong ID fails: "message may not exist or not belong to this session"
    // The message line now shows ONLY messageId=N (the ID the LLM
    // must use for ack_agent_message / send_agent_message inReplyTo).
    appendLine("${prefix}msg#${m.messageId} [${m.kind}] from=$sender$priorityTag$subject$extra")
    appendLine("${prefix}  $bodyPreview$ellipsis")
}
