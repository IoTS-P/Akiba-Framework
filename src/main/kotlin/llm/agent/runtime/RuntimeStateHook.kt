package org.iotsplab.akiba.llm.agent.runtime

import org.apache.logging.log4j.LogManager

// ============================================================
//  RuntimeStateHook — gatekeeper for RuntimeState transitions
// ============================================================
//
// AgentRuntime is the single writer of [RuntimeState] and funnels every
// change through each registered hook; the first
// [StateTransitionDecision.DENY] vetoes it, leaving memory and DB on
// `prev` while the originating tool call gets a structured "denied"
// outcome.  Hooks are a gate, not a notification channel — they never
// push messages or signals to the affected agent, so callers must read
// the structured outcome of their own call.  A hook that throws is
// treated as DENY and logged.

/**
 * Snapshot of a [RuntimeState] transition request, presented to each
 * registered [RuntimeStateHook] before the runtime applies it.
 *
 * @property reason free-form reason, surfaced as `closing_reason`
 * @property requesterSessionId session that initiated the transition;
 *   null means system-level (CLI, orphan reaper, cascade parent)
 * @property transitionId monotonic id assigned by the runtime, for
 *   audit / log correlation
 */
data class StateTransition(
    val sessionId: String,
    val templateId: String?,
    val parentSessionId: String?,
    val depth: Int,
    val prev: RuntimeState,
    val next: RuntimeState,
    val reason: String?,
    val requesterSessionId: String?,
    val transitionId: Long,
    val timestampMs: Long,
)

/**
 * Result of consulting a [RuntimeStateHook].  Hooks return
 * [ALLOW] by default; they return [DENY] (optionally with a
 * reason) to veto the transition.
 */
enum class StateTransitionDecision {
    /** Hook approves the transition; the runtime proceeds. */
    ALLOW,

    /** Hook vetoes the transition; the runtime keeps `prev`. */
    DENY;

    companion object {
        /**
         * Convenience constructor for a denied decision with a
         * short reason that fits in log lines and the LLM-facing
         * tool result.
         */
        @JvmStatic
        fun deny(reason: String): Pair<StateTransitionDecision, String> = DENY to reason
    }
}

/**
 * Gatekeeper consulted by [AgentRuntime] before applying a
 * [RuntimeState] transition.  Implementations should be cheap
 * (they run on the runtime's dispatch path) and side-effect free
 * beyond structured logging.
 *
 * The default implementation is a no-op: every transition is
 * allowed.  A typical production deployment would register
 * [DefaultStateHook] (or a custom one) to enforce the
 * "no child cancels its parent / siblings" rule and to honour
 * [InteractionPolicy.canBeCancelledBy].
 */
fun interface RuntimeStateHook {
    /**
     * Inspect a proposed transition and return either
     * [StateTransitionDecision.ALLOW] (proceed) or
     * [StateTransitionDecision.DENY] (veto).
     *
     * Implementations MUST be deterministic with respect to the
     * [StateTransition] input.  They SHOULD NOT mutate shared
     * state without synchronisation.
     */
    fun onStateTransition(transition: StateTransition): StateTransitionDecision
}

/**
 * Internal composite that runs every hook in order.  A hook that
 * throws is treated as DENY and the exception is logged — the
 * runtime never crashes because of a misbehaving hook.
 */
internal class CompositeStateHook(
    private val hooks: List<RuntimeStateHook>,
) : RuntimeStateHook {
    private val logger = LogManager.getLogger(CompositeStateHook::class.java)

    override fun onStateTransition(transition: StateTransition): StateTransitionDecision {
        for (hook in hooks) {
            val decision = try {
                hook.onStateTransition(transition)
            } catch (e: Exception) {
                logger.warn(
                    "RuntimeStateHook threw on transition ${transition.transitionId} " +
                        "${transition.prev.wire()}→${transition.next.wire()} " +
                        "for session=${transition.sessionId}: ${e.message}",
                    e,
                )
                StateTransitionDecision.DENY
            }
            if (decision == StateTransitionDecision.DENY) return StateTransitionDecision.DENY
        }
        return StateTransitionDecision.ALLOW
    }
}
