package org.iotsplab.akiba.llm.agent

import org.apache.logging.log4j.LogManager

// ============================================================
//  RuntimeStateHook — gatekeeper for RuntimeState transitions
// ============================================================
//
// AgentRuntime is the single writer of [RuntimeState]; before it
// flips a JobHandle's state (and mirrors the new value to the DB)
// it funnels the request through every registered
// [RuntimeStateHook].  Any hook that returns [StateTransitionDecision.DENY]
// vetoes the transition: the in-memory and DB state stay on `prev`,
// and the caller (the tool / runtime path that initiated the
// transition) receives a structured "denied" outcome.
//
// Hooks are the gate, not a notification channel.  If the
// orchestrator wants to react to a denied transition, it should
// check the structured outcome of the originating tool call
// (e.g. `await_agent` returning `lastError="denied: ..."`) — the
// hook deliberately does NOT push messages or signals to the
// affected agent.
//
// Multiple hooks are composed in registration order; the first
// DENY short-circuits.  A hook that throws is treated as DENY (and
// the exception is logged) so a buggy hook cannot crash the
// runtime.

/**
 * Snapshot of a [RuntimeState] transition request, presented to
 * each registered [RuntimeStateHook] before the runtime applies
 * it.
 *
 * @property sessionId     target session (the one whose state is
 *                         about to change)
 * @property templateId    target session's template id, or null for
 *                         freeform / top-level sessions
 * @property parentSessionId target session's parent (null for root)
 * @property depth         target's depth in the agent tree
 * @property prev          current state
 * @property next          proposed new state
 * @property reason        free-form reason (passed to setRuntimeState
 *                         and surfaced as `closing_reason`)
 * @property requesterSessionId session that initiated the transition
 *                         (null = system-level: CLI, orphan reaper,
 *                         cascade-parent path that wants to act as
 *                         the runtime itself)
 * @property transitionId  monotonic id assigned by the runtime, for
 *                         audit / log correlation
 * @property timestampMs   wall-clock at hook entry
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
