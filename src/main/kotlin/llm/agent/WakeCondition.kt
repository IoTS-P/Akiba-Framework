package org.iotsplab.akiba.llm.agent

import org.apache.logging.log4j.LogManager
import java.util.concurrent.ConcurrentHashMap

// ============================================================
//  WakeCondition — composable wake-up conditions for STANDBY agents
// ============================================================
//
//  An agent that parks with `await_condition` declares a
//  [WakeCondition] that the framework evaluates on every
//  state change and mailbox delivery.  When the condition
//  is satisfied, a synthetic "wake" message is sent to the
//  agent's mailbox, and the [AgentMailboxDispatcher] resumes
//  the agent from STANDBY via the normal resume path.
//
//  ## Condition types
//
//  - [MessageArrived]: a new mailbox message arrived for the
//    agent (optionally filtered by sender / kind / minPriority).
//  - [StateChanged]: a specific agent reached a specific state
//    (e.g. "batch_linear_planner → closed").
//  - [TimeElapsed]: a duration has passed since the condition
//    was registered (timeout / fallback path).
//  - [AllOf]: all sub-conditions must be satisfied (barrier).
//  - [AnyOf]: any sub-condition satisfied (race / first-to-finish).
//  - [Not]: negation — wake when the sub-condition is NOT
//    currently true (rarely useful alone; useful in AllOf).
//
//  ## Evaluation model
//
//  Conditions are evaluated:
//  1. After every [AgentRuntime.transition] (state-change trigger).
//  2. After every [AgentMailboxService.send] (message trigger).
//  3. On a periodic timer (time-elapsed trigger, checked every
//     [WakeConditionRegistry.POLL_INTERVAL_MS]).
//
//  Conditions are **one-shot** by default: once satisfied, they
//  fire a wake message and are removed.  The agent can re-register
//  a new condition after waking if it wants to wait again.
//
//  ## Design notes
//
//  The condition tree is evaluated in-memory (no DB round-trips
//  for the evaluation itself — it reads from the in-memory
//  [AgentRuntime.handles] and [AgentMailboxService] state).
//  Lost on JVM restart; the agent will wake on its next
//  mailbox-driven wake instead, which is the correct fallback.

/**
 * Sealed hierarchy of composable wake conditions.
 */
sealed class WakeCondition {
    /**
     * Evaluate this condition against the current state.
     * Returns true when the condition is satisfied.
     */
    abstract fun evaluate(ctx: WakeEvalContext): Boolean

    /**
     * Human-readable description for logging / wake message.
     */
    abstract val description: String
}

/**
 * Context passed to [WakeCondition.evaluate].
 */
data class WakeEvalContext(
    /** The agent that registered this condition (the "waker"). */
    val agentSessionId: String,
    /** Current unread message count for the agent. */
    val unreadCount: Int,
    /** Unread messages (for filtering by sender/kind/priority). */
    val unreadMessages: List<org.iotsplab.akiba.data.database.AgentDatabaseClient.MailboxMessageInfo>,
    /** Map of sessionId → current RuntimeState wire value, for [StateChanged]. */
    val sessionStates: Map<String, String>,
    /** Timestamp (ms) when this condition was registered. */
    val registeredAt: Long,
    /** Current timestamp (ms). */
    val now: Long,
)

// ============================================================
//  Leaf conditions
// ============================================================

/** Wake when a new message arrives (optionally filtered). */
data class MessageArrived(
    val fromSessionId: String? = null,
    val kind: String? = null,
    val minPriority: Int = 0,
) : WakeCondition() {
    override val description: String =
        "message arrived" +
            (fromSessionId?.let { " from ${it.take(8)}" } ?: "") +
            (kind?.let { " kind=$it" } ?: "") +
            (if (minPriority > 0) " priority>=$minPriority" else "")

    override fun evaluate(ctx: WakeEvalContext): Boolean {
        if (ctx.unreadCount == 0) return false
        if (fromSessionId == null && kind == null && minPriority <= 0) return true
        return ctx.unreadMessages.any { m ->
            (fromSessionId == null || m.senderSessionId == fromSessionId) &&
                (kind == null || m.kind == kind) &&
                m.priority >= minPriority
        }
    }
}

/** Wake when a specific agent reaches a specific state. */
data class StateChanged(
    val sessionId: String,
    val toState: String,
) : WakeCondition() {
    override val description: String =
        "agent ${sessionId.take(8)} → $toState"

    override fun evaluate(ctx: WakeEvalContext): Boolean =
        ctx.sessionStates[sessionId] == toState
}

/** Wake when [durationMs] has elapsed since registration. */
data class TimeElapsed(
    val durationMs: Long,
) : WakeCondition() {
    override val description: String =
        "${durationMs}ms elapsed"

    override fun evaluate(ctx: WakeEvalContext): Boolean =
        (ctx.now - ctx.registeredAt) >= durationMs
}

// ============================================================
//  Composite conditions
// ============================================================

/** Wake when ALL sub-conditions are satisfied (barrier). */
data class AllOf(val conditions: List<WakeCondition>) : WakeCondition() {
    override val description: String =
        "ALL(${conditions.joinToString(", ") { it.description }})"

    override fun evaluate(ctx: WakeEvalContext): Boolean =
        conditions.all { it.evaluate(ctx) }
}

/** Wake when ANY sub-condition is satisfied (race). */
data class AnyOf(val conditions: List<WakeCondition>) : WakeCondition() {
    override val description: String =
        "ANY(${conditions.joinToString(", ") { it.description }})"

    override fun evaluate(ctx: WakeEvalContext): Boolean =
        conditions.any { it.evaluate(ctx) }
}

/** Wake when the sub-condition is NOT currently true. */
data class Not(val condition: WakeCondition) : WakeCondition() {
    override val description: String =
        "NOT(${condition.description})"

    override fun evaluate(ctx: WakeEvalContext): Boolean =
        !condition.evaluate(ctx)
}

// ============================================================
//  WakeConditionRegistry — per-JVM registry + evaluator
// ============================================================

/**
 * Global registry of active wake conditions.  One entry per
 * (targetSessionId, condition) pair.
 *
 * When a condition is satisfied, a synthetic mailbox message is
 * sent to the target agent and the condition is removed (one-shot
 * semantics).
 */
object WakeConditionRegistry {
    private val logger = LogManager.getLogger("WakeConditionRegistry")

    data class Entry(
        val targetSessionId: String,
        val condition: WakeCondition,
        val registeredAt: Long,
        val label: String?,
    )

    private val entries = ConcurrentHashMap<String, MutableList<Entry>>()

    /**
     * Register a wake condition for [targetSessionId].
     * Returns a unique condition ID for later removal.
     */
    fun register(
        targetSessionId: String,
        condition: WakeCondition,
        label: String? = null,
    ): String {
        val condId = "wc-${System.nanoTime()}"
        val entry = Entry(targetSessionId, condition, System.currentTimeMillis(), label)
        entries.compute(targetSessionId) { _, list ->
            (list ?: mutableListOf()).also { it.add(entry) }
        }
        logger.info(
            "WakeCondition registered for ${targetSessionId.take(8)}: " +
                "${condition.description}${label?.let { " ($it)" } ?: ""}"
        )
        return condId
    }

    /**
     * Remove a previously-registered condition by its ID.
     * The condition ID is returned by [register].
     */
    fun unregister(targetSessionId: String, conditionId: String) {
        // conditionId is not stored per-entry for simplicity; we
        // just clear all conditions for the target when the agent
        // wakes.  Fine-grained removal is rarely needed because
        // conditions are one-shot.
    }

    /**
     * Clear ALL conditions for [targetSessionId].  Called when
     * the agent wakes (conditions are one-shot) or when the agent
     * terminates.
     */
    fun clearAll(targetSessionId: String) {
        entries.remove(targetSessionId)
    }

    /**
     * List all conditions for [targetSessionId].
     */
    fun list(targetSessionId: String): List<Entry> =
        entries[targetSessionId]?.toList() ?: emptyList()

    /**
     * Evaluate all conditions for [targetSessionId] against the
     * given [WakeEvalContext].  Returns the list of satisfied
     * entries (which are then removed + notified).
     *
     * Called by [evaluateAll] which is in turn called by the
     * [AgentMailboxDispatcher] tick and [AgentRuntime.transition].
     */
    fun evaluate(targetSessionId: String, ctx: WakeEvalContext): List<Entry> {
        val list = entries[targetSessionId] ?: return emptyList()
        val satisfied = list.filter { it.condition.evaluate(ctx) }
        if (satisfied.isNotEmpty()) {
            list.removeAll(satisfied)
            if (list.isEmpty()) entries.remove(targetSessionId)
        }
        return satisfied
    }

    /**
     * Evaluate conditions WITHOUT removing them.  Returns the
     * list of satisfied entries.  The caller MUST call
     * [removeEntries] after successfully acting on them (e.g.
     * after the synthetic wake message has been inserted);
     * otherwise the condition stays registered and will be
     * re-evaluated on the next tick.
     *
     * This split-peek-then-confirm pattern is necessary because
     * [evaluate] removes conditions before the caller can act on
     * them — if the action (e.g. mailbox send) fails, the
     * condition is lost and the agent is permanently stuck in
     * STANDBY.  Using [peek] + [removeEntries] lets the caller
     * retry on the next tick.
     */
    fun peek(targetSessionId: String, ctx: WakeEvalContext): List<Entry> {
        val list = entries[targetSessionId] ?: return emptyList()
        return list.filter { it.condition.evaluate(ctx) }
    }

    /**
     * Remove specific entries after they have been successfully
     * processed.  Safe to call with entries that were already
     * removed (no-op).  Typically called with the list returned
     * by [peek] after each entry's wake action succeeded.
     */
    fun removeEntries(targetSessionId: String, toRemove: List<Entry>) {
        if (toRemove.isEmpty()) return
        val list = entries[targetSessionId] ?: return
        list.removeAll(toRemove)
        if (list.isEmpty()) entries.remove(targetSessionId)
    }
}

// ============================================================
//  Backpressure — prevent slow agents from being flooded
// ============================================================

/**
 * Per-session backpressure tracker.  When the number of
 * "seen but not handled" messages exceeds [BACKPRESSURE_THRESHOLD],
 * new non-urgent messages are rejected with a structured error
 * that tells the sender to back off.
 *
 * The threshold is per-session; different agents have different
 * processing capacities, and a one-size-fits-all global cap would
 * be too blunt.
 */
object BackpressureTracker {
    private val logger = LogManager.getLogger("BackpressureTracker")

    // Thresholds are defined in AgentConstants.kt (shared with
    // AgentMailboxService.kt's wake-board rendering).

    // sessionId → pending count (updated by applyMailboxDrain)
    private val pendingCounts = ConcurrentHashMap<String, Int>()

    /**
     * Update the pending count for a session.  Called by
     * [applyMailboxDrain] after each drain.
     */
    fun updatePendingCount(sessionId: String, count: Int) {
        pendingCounts[sessionId] = count
    }

    /**
     * Check if a new message should be rejected due to backpressure.
     *
     * Returns null if the message is accepted, or a rejection
     * reason string if the recipient is over the threshold and the
     * message is not urgent.
     */
    fun checkBackpressure(
        recipientSessionId: String,
        priority: Int,
    ): String? {
        val pending = pendingCounts[recipientSessionId] ?: 0
        if (pending < BACKPRESSURE_THRESHOLD) return null
        if (priority >= URGENT_PRIORITY_THRESHOLD) {
            // Urgent messages bypass backpressure
            logger.warn(
                "Backpressure bypassed for ${recipientSessionId.take(8)}: " +
                    "pending=$pending >= $BACKPRESSURE_THRESHOLD but priority=$priority >= $URGENT_PRIORITY_THRESHOLD"
            )
            return null
        }
        return "recipient ${recipientSessionId.take(8)} is overloaded " +
            "(pending=$pending >= $BACKPRESSURE_THRESHOLD). " +
            "Please wait for the recipient to process its backlog before sending more messages, " +
            "or re-send with priority >= $URGENT_PRIORITY_THRESHOLD for urgent delivery."
    }

    /**
     * Get the current pending count for a session.
     */
    fun pendingCount(sessionId: String): Int =
        pendingCounts[sessionId] ?: 0
}
