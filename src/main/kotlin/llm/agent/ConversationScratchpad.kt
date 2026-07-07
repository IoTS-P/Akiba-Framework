package org.iotsplab.akiba.llm.agent

import org.iotsplab.akiba.data.database.AgentDatabaseClient
import java.util.concurrent.ConcurrentHashMap

// ============================================================
//  ConversationScratchpad — per-conversation isolated context
// ============================================================
//
//  Each conversation gets its own scratchpad: a lightweight
//  message buffer that holds only the messages belonging to that
//  conversation thread.  When the LLM is processing a specific
//  conversation, the strategy injects the scratchpad's messages
//  as the context window instead of (or in addition to) the
//  main memory.
//
//  This achieves **context isolation**: conversations don't
//  pollute each other, and the LLM stays focused on one thread
//  at a time.  Cross-conversation queries are still possible via
//  `read_agent_messages action=get` for specific message IDs.
//
//  ## Scratchpad lifecycle
//
//  Auto-created on first message belonging to a conversation
//  (by [applyMailboxDrain] when it routes a drained message to
//  its conversation key).  No explicit "open" call.
//
//  Closed conversations' scratchpads are kept until the agent
//  terminates (so the LLM can still query history via
//  `read_agent_messages`), but they are no longer injected into
//  the LLM context.
//
//  ## Scheduling: serial + preemptive
//
//  When multiple conversations have actionable items, the
//  [ScratchpadScheduler] picks ONE conversation to process
//  (the "active" one) per LLM turn.  This prevents context
//  pollution from interleaving multiple conversations.
//
//  Preemption: if a new message arrives with `priority >=
//  PREEMPTION_THRESHOLD` while another conversation is active,
//  the scheduler preempts: the active conversation is suspended
//  (its partial state saved), and the urgent conversation
//  becomes the new active one.  After the urgent conversation
//  is handled (replied or acked), the scheduler restores the
//  previous active conversation and injects a "resume context"
//  hint so the LLM knows what it was doing before the
//  interruption.

/**
 * A single conversation's isolated message buffer + state.
 *
 * Messages are stored in arrival order (newest last).  The
 * scratchpad does NOT call the LLM — it's pure storage.
 * The strategy reads from it and writes to it via
 * [ScratchpadRegistry].
 */
data class ConversationScratchpad(
    val conversationId: Long,
    val messages: MutableList<AgentDatabaseClient.MailboxMessageInfo> = mutableListOf(),
    /** Set when this conversation is the "active" one for the current LLM turn. */
    @Volatile var isActive: Boolean = false,
    /** How many times this scratchpad has been surfaced to the LLM. */
    @Volatile var surfaceCount: Int = 0,
    /** Set when the conversation was preempted; used for resume hints. */
    @Volatile var preempted: Boolean = false,
    /** The last assistant response that was working on this conversation. */
    @Volatile var lastAssistantTurn: String? = null,
)

/**
 * Per-agent registry of conversation scratchpads + the serial
 * scheduler that decides which conversation is "active".
 *
 * One instance per [AkibaAgent] (created in the agent's
 * constructor `init` block when [mailboxService] is non-null).
 */
class ScratchpadRegistry {

    // conversationId → scratchpad
    private val scratchpads = ConcurrentHashMap<Long, ConversationScratchpad>()

    // The conversation currently being processed by the LLM.
    // null when no conversation is active (e.g. the agent is
    // doing its own task, not responding to mail).
    @Volatile
    private var activeConversationId: Long? = null

    // Stack of previously-active conversations (for preemption restore).
    // When a high-priority conversation preempts, the current one is
    // pushed here so it can be restored after the urgent one is handled.
    private val preemptStack = mutableListOf<Long>()

    companion object {
        /** Messages with priority >= this value trigger preemption. */
        const val PREEMPTION_THRESHOLD = 5
    }

    /**
     * Get or create a scratchpad for [conversationId].
     */
    fun getOrCreate(conversationId: Long): ConversationScratchpad =
        scratchpads.computeIfAbsent(conversationId) {
            ConversationScratchpad(conversationId = conversationId)
        }

    /**
     * Get a scratchpad without creating one.  Returns null if
     * the conversation was never seen.
     */
    fun get(conversationId: Long): ConversationScratchpad? = scratchpads[conversationId]

    /**
     * Add a message to its conversation's scratchpad.
     */
    fun addMessage(msg: AgentDatabaseClient.MailboxMessageInfo) {
        val convId = ConversationRegistry.deriveConversationId(
            msg.messageId, msg.inReplyToMessageId
        )
        getOrCreate(convId).messages.add(msg)
    }

    /**
     * The currently-active conversation ID, or null.
     */
    fun activeConversation(): Long? = activeConversationId

    /**
     * Pick the next conversation to process.  Called by
     * [applyMailboxDrain] after draining messages.
     *
     * Scheduling policy (serial + preemptive):
     *  1. If there's already an active conversation AND it still
     *     has pending (unhandled) messages, keep it active.
     *  2. Otherwise, pick the conversation with the highest
     *     priority message.
     *  3. If the picked conversation's top message has priority
     *     >= [PREEMPTION_THRESHOLD] AND it's not the current
     *     active conversation, preempt: push the current active
     *     onto the stack, mark it preempted, and activate the
     *     new one.
     *
     * Returns the conversation ID to activate, or null if no
     * conversation has actionable items.
     */
    fun scheduleNext(): Long? {
        // Find conversations with unhandled messages
        val actionable = scratchpads.values
            .filter { it.messages.any { m -> m.ackedAt == null } }
            .sortedByDescending { scratchpad ->
                scratchpad.messages
                    .filter { it.ackedAt == null }
                    .maxOfOrNull { it.priority } ?: 0
            }

        if (actionable.isEmpty()) {
            // No actionable conversations.  If we were preempted,
            // restore the previous active one (it may still have
            // context the LLM needs to finish).
            if (activeConversationId == null && preemptStack.isNotEmpty()) {
                val restored = preemptStack.removeLast()
                scratchpads[restored]?.let { sp ->
                    sp.preempted = false
                    sp.isActive = true
                    activeConversationId = restored
                    return restored
                }
            }
            activeConversationId = null
            return null
        }

        val picked = actionable.first()
        val pickedId = picked.conversationId

        // If already active, no change.
        if (pickedId == activeConversationId) return pickedId

        // Check for preemption
        val topPriority = picked.messages
            .filter { it.ackedAt == null }
            .maxOfOrNull { it.priority } ?: 0

        if (topPriority >= PREEMPTION_THRESHOLD && activeConversationId != null) {
            // Preempt: save current active, switch to the urgent one.
            scratchpads[activeConversationId]?.let { old ->
                old.isActive = false
                old.preempted = true
                preemptStack.add(activeConversationId!!)
            }
        } else if (activeConversationId != null) {
            // Non-preemptive switch: just deactivate the old one
            // (it's done or has no actionable items).
            scratchpads[activeConversationId]?.isActive = false
        }

        // Activate the picked conversation
        scratchpads.values.forEach { it.isActive = (it.conversationId == pickedId) }
        activeConversationId = pickedId
        picked.surfaceCount++
        return pickedId
    }

    /**
     * Mark a conversation as "handled" (all messages acked or
     * replied).  Deactivates it and tries to restore the next
     * one from the preempt stack.
     */
    fun markHandled(conversationId: Long) {
        scratchpads[conversationId]?.isActive = false
        if (activeConversationId == conversationId) {
            activeConversationId = null
            // Try to restore a preempted conversation
            if (preemptStack.isNotEmpty()) {
                val restored = preemptStack.removeLast()
                scratchpads[restored]?.let { sp ->
                    sp.preempted = false
                    sp.isActive = true
                    activeConversationId = restored
                }
            }
        }
    }

    /**
     * Build a resume-context hint for the LLM when a preempted
     * conversation is restored.  Returns null if no conversation
     * was preempted.
     */
    fun buildResumeHint(): String? {
        val activeId = activeConversationId ?: return null
        val sp = scratchpads[activeId] ?: return null
        if (!sp.preempted && sp.surfaceCount > 0) return null

        val lastTurn = sp.lastAssistantTurn
        val lastMsg = sp.messages.lastOrNull { it.ackedAt == null }

        return buildString {
            appendLine("[resume context] You were previously processing conversation #$activeId")
            if (sp.preempted) {
                appendLine("but were interrupted by a higher-priority message. The interruption")
                appendLine("has been handled. Please continue where you left off.")
            }
            if (lastTurn != null) {
                appendLine("Your last action in this conversation:")
                appendLine(lastTurn.take(300))
            }
            if (lastMsg != null) {
                appendLine("The last unhandled message in this conversation:")
                appendLine("#${lastMsg.messageId} from ${lastMsg.senderSessionId.take(8)}: ${lastMsg.body.take(200)}")
            }
            appendLine()
            appendLine("Resume processing this conversation now.")
        }
    }

    /**
     * Record the LLM's latest assistant turn for the active
     * conversation.  Called by the strategy after each LLM
     * response so [buildResumeHint] has context to work with.
     */
    fun recordAssistantTurn(text: String) {
        val activeId = activeConversationId ?: return
        scratchpads[activeId]?.lastAssistantTurn = text
    }

    /**
     * Get the messages for the active conversation's scratchpad.
     * Used by the strategy to inject conversation-scoped context
     * into the LLM call.
     */
    fun activeConversationMessages(): List<AgentDatabaseClient.MailboxMessageInfo> {
        val activeId = activeConversationId ?: return emptyList()
        return scratchpads[activeId]?.messages?.toList() ?: emptyList()
    }
}
