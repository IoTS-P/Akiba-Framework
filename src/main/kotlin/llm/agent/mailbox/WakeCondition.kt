package org.iotsplab.akiba.llm.agent.mailbox

import java.util.concurrent.ConcurrentHashMap
import org.apache.logging.log4j.LogManager
import org.iotsplab.akiba.llm.agent.core.BACKPRESSURE_THRESHOLD
import org.iotsplab.akiba.llm.agent.core.URGENT_PRIORITY_THRESHOLD

// ============================================================
//  WakeCondition — composable wake-up conditions for STANDBY agents
// ============================================================
//
//  An agent that parks with `await_condition` declares a [WakeCondition]
//  that the framework evaluates on every state change and mailbox
//  delivery; when it is satisfied, a synthetic "wake" message is sent to
//  the agent's mailbox and [AgentMailboxDispatcher] resumes it from STANDBY.
//  The tree is composed of [MessageArrived], [StateChanged], [TimeElapsed],
//  [AllOf], [AnyOf] and [Not], and conditions are either one-shot (removed
//  after firing) or permanent ([reset] is called so they can fire again,
//  used by internal conditions that must survive several wake cycles).
//  Evaluation is in-memory only — no DB round-trips — and is lost on JVM
//  restart, where the next mailbox-driven wake is the correct fallback.

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

    /**
     * Whether this condition is one-shot (removed after firing)
     * or permanent (stays registered, [reset] is called after
     * firing so it can fire again).
     *
     * Defaults to true — conditions registered by the LLM via
     * `await_condition` are always one-shot.  Internal/framework
     * conditions can override this to false for recurring wakes.
     */
    open val oneShot: Boolean = true

    /**
     * Reset internal state after the condition has fired.
     * Called by the registry for permanent conditions (oneShot=false)
     * after a successful wake.  One-shot conditions never call this
     * because they are removed immediately.
     *
     * Default implementation is a no-op; conditions that carry
     * mutable state (e.g. [TimeElapsed] with its start clock)
     * override this to re-arm themselves.
     */
    open fun reset() {}
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

/** Wake when an unread message is available (optionally filtered). */
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

/** Wake when [durationMs] has elapsed since the condition was
 *  created (or last reset).
 *
 *  Carries its own [startTime] so that it is self-contained:
 *  the registry does not need to track registration timestamps
 *  for TimeElapsed.  After firing, [reset] moves [startTime]
 *  to the current time so the condition can fire again after
 *  the next [durationMs] interval (useful for permanent
 *  recurring timers). */
data class TimeElapsed(
    val durationMs: Long,
    /** Mutable start clock; initialised at construction and
     *  updated by [reset].  Using a var here is safe because
     *  evaluation and reset happen on the dispatcher tick
     *  thread, not concurrently. */
    var startTime: Long = System.currentTimeMillis(),
) : WakeCondition() {
    override val description: String =
        "${durationMs}ms elapsed"

    override fun evaluate(ctx: WakeEvalContext): Boolean {
        if ((ctx.now - startTime) < durationMs) return false
        // Re-arm immediately when the timeout is observed.  This is
        // required by permanent conditions and harmless for one-shot
        // conditions, which are removed by the registry after firing.
        startTime = ctx.now
        return true
    }

    override fun reset() {
        startTime = System.currentTimeMillis()
    }
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

    override fun reset() {
        conditions.forEach { it.reset() }
    }
}

/** Wake when ANY sub-condition is satisfied (race). */
data class AnyOf(val conditions: List<WakeCondition>) : WakeCondition() {
    override val description: String =
        "ANY(${conditions.joinToString(", ") { it.description }})"

    override fun evaluate(ctx: WakeEvalContext): Boolean =
        conditions.any { it.evaluate(ctx) }

    override fun reset() {
        conditions.forEach { it.reset() }
    }
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
 * sent to the target agent.  One-shot conditions are then removed;
 * permanent conditions have [WakeCondition.reset] called and stay
 * registered for future fires.
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
     * Evaluate conditions and **atomically remove/reset** satisfied ones in
     * the same call, returning the satisfied entries (already removed for
     * one-shot, already reset for permanent).
     *
     * This replaces the old peek+removeEntries split, whose retry-on-send
     * intent left a race window where a satisfied condition stayed registered
     * between peek and remove, re-firing it on every tick.  If the wake send
     * fails now the agent simply stays parked until the watchdog or another
     * wake path picks it up — strictly better than repeated spurious wakes.
     */
    fun evaluateAndRemove(targetSessionId: String, ctx: WakeEvalContext): List<Entry> {
        var satisfied: List<Entry> = emptyList()
        var remaining = 0
        entries.computeIfPresent(targetSessionId) { _, list ->
            satisfied = list.filter { entry ->
                entry.condition.evaluate(ctx.copy(registeredAt = entry.registeredAt))
            }
            if (satisfied.isNotEmpty()) {
                // Remove only one-shot entries. Permanent entries stay
                // registered; their condition state is re-armed after
                // firing. Match by reference because conditions may
                // contain mutable state used by data-class equality.
                list.removeAll { current ->
                    current.condition.oneShot && satisfied.any { it === current }
                }
                satisfied.asSequence()
                    .filter { !it.condition.oneShot }
                    .forEach { it.condition.reset() }
            }
            remaining = list.size
            list.takeIf { it.isNotEmpty() }
        }
        if (satisfied.isNotEmpty()) {
            logger.debug(
                "evaluateAndRemove(${targetSessionId.take(8)}): " +
                    "fired=${satisfied.size}, remaining=$remaining"
            )
        }
        return satisfied
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
