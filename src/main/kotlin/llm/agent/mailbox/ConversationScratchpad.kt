package org.iotsplab.akiba.llm.agent.mailbox

import java.util.concurrent.ConcurrentHashMap
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.agent.core.PREEMPTION_THRESHOLD

// ============================================================
//  ConversationScratchpad — per-conversation isolated context
// ============================================================
//
//  Each conversation gets its own scratchpad — a lightweight buffer of
//  only that thread's messages, injected by the strategy as the context
//  window while the LLM processes the conversation.  That isolation keeps
//  threads from polluting each other, though `read_agent_messages` with
//  `action=get` can still reach any message by id; scratchpads are
//  auto-created on a conversation's first message and kept (no longer
//  injected) once it closes.  When a new message arrives at priority >=
//  `PREEMPTION_THRESHOLD`, the active conversation is pushed onto a stack
//  and suspended, the urgent one takes over, and afterwards the previous
//  one is restored with a "resume context" hint so the LLM knows what it
//  was doing.

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

    // PREEMPTION_THRESHOLD is defined in AgentConstants.kt.

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
     * Serial + preemptive: keep the active conversation while it still has
     * unhandled messages, otherwise pick the one with the highest-priority
     * message.  If that pick is not already active and its top message is at
     * `priority >= [PREEMPTION_THRESHOLD]`, push the current one onto the
     * preempt stack (marked preempted) and activate the new one.
     *
     * Returns the conversation ID to activate, or null if no conversation
     * has actionable items.
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
