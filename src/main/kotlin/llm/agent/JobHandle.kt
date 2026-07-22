@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.iotsplab.akiba.llm.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

// ============================================================
//  AwaitResult / AwaitPredicate — multi-mode child wait
// ============================================================

/**
 * Outcome of an [JobHandle.await] call. [finalState] is the
 * runtime state observed at return time; [timedOut] distinguishes
 * a predicate match from a timeout.
 */
data class AwaitResult(
    val finalState: RuntimeState,
    val timedOut: Boolean,
    val elapsedMs: Long,
    /** Last error captured by the Job, if any. */
    val lastError: String? = null,
)

/**
 * Built-in predicates for the most common await use-cases. The
 * matcher is called once per [JobHandle.await] poll; cheap
 * functions only.
 */
object AwaitPredicate {
    /** Match when the session has reached any terminal state. */
    val TERMINAL: (RuntimeState) -> Boolean = {
        it == RuntimeState.CLOSED || it == RuntimeState.ERROR
    }

    /** Match when the session is idle (standby) or terminal. */
    val IDLE_OR_TERMINAL: (RuntimeState) -> Boolean = {
        it == RuntimeState.STANDBY || it == RuntimeState.CLOSED || it == RuntimeState.ERROR
    }

    /**
     * Match when the current msghandle round has returned to standby
     * (or terminal). Useful for "ping a standby child, wait for its
     * reply, then send the next message" flows.
     */
    val MSG_HANDLED: (RuntimeState) -> Boolean = {
        it == RuntimeState.STANDBY || it == RuntimeState.CLOSED || it == RuntimeState.ERROR
    }

    /** Match a single specific state. */
    fun ofState(state: RuntimeState): (RuntimeState) -> Boolean = { it == state }

    /** Compose: match when any of [predicates] is true. */
    fun any(vararg predicates: (RuntimeState) -> Boolean): (RuntimeState) -> Boolean = { s ->
        predicates.any { it(s) }
    }
}

// ============================================================
//  JobHandle — a single in-flight or parked sub-agent job
// ============================================================

/**
 * Handle to a child agent's coroutine Job. The handle is the
 * "ticket" returned by [AgentRuntime.spawnSubAgentFromTemplate];
 * the parent agent can poll state, await specific states, or
 * cancel.
 *
 * The [state] is a [StateFlow] so callers can `collect` it to
 * drive UI / logging. The underlying Job may belong to a different
 * coroutine scope — cancellation here is cooperative and
 * best-effort.
 */
class JobHandle internal constructor(
    /** Child session id (UUID). */
    val sessionId: String,
    /** Template id this child was spawned from, or null when built ad-hoc. */
    val templateId: String?,
    /** Parent session id at spawn time. */
    val parentSessionId: String,
    /**
     * Root session id of the tree this child belongs to. Equal to
     * [sessionId] when this is a top-level root agent spawn, equal
     * to the parent's [parentSessionId] (or this node's parent's
     * own [rootSessionId] for deeper trees) for descendant spawns.
     * Used by [JobScheduler] to enforce per-root concurrency caps;
     * the per-root counter MUST key on this field, not on
     * [parentSessionId], otherwise every node under a root shares
     * a single "per-root" budget and the cap degenerates into
     * "per-parent".
     */
    val rootSessionId: String,
    /** Depth in the agent tree (1 = child of root). */
    val depth: Int,
    initialState: RuntimeState,
    /**
     * Underlying coroutine Job.  Replaced on STANDBY resume via
     * [swapJob] so the same handle survives across multiple
     * run() invocations.  Marked `@Volatile` so concurrent
     * readers (e.g. `cancel` checking `isActive`) see the swap
     * without further synchronisation.
     */
    @Volatile private var job: Job,
    /**
     * Optional, lazily-resolved error sink. Set by the runtime
     * before the Job body runs so await() can surface partial
     * failures without unwinding the coroutine.
     */
    private val errorSink: CompletableDeferred<String?> = CompletableDeferred(),
) {
    private val _state = MutableStateFlow(initialState)
    /** Observable runtime state. Updated by the runtime. */
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    @Volatile private var cancelRequestedAtMs: Long? = null
    @Volatile private var cancelReason: String? = null

    /** True once the runtime has requested cooperative cancellation. */
    val cancelRequested: Boolean get() = cancelRequestedAtMs != null
    val requestedCancelReason: String? get() = cancelReason

    @Volatile private var _pauseRequested: Boolean = false

    /** True when the user has requested this session to be paused. */
    val pauseRequested: Boolean get() = _pauseRequested

    /**
     * Set when the user requests an immediate LLM retry (skipping the
     * remaining backoff delay).  The [callLLM] retry loop checks this
     * flag and breaks out of [delay] early when set.
     */
    @Volatile private var _retryNowRequested: Boolean = false
    val retryNowRequested: Boolean get() = _retryNowRequested
    internal fun markRetryNowRequested() { _retryNowRequested = true }
    internal fun clearRetryNowRequested() { _retryNowRequested = false }

    /** Mark this handle as paused. The strategy loop will block at the
     *  next iteration boundary until [clearPauseRequested] is called. */
    internal fun markPauseRequested() {
        _pauseRequested = true
    }

    /** Clear the pause flag so the strategy loop can resume. */
    internal fun clearPauseRequested() {
        _pauseRequested = false
    }

    /** Underlying coroutine Job. */
    val coroutineJob: Job get() = job

    /**
     * Replace the underlying coroutine Job.  Used by
     * [AgentRuntime.resumeStandby] to give a STANDBY-parked
     * handle a fresh Job without changing the handle's identity
     * (so callers that captured the handle keep working).
     *
     * Marked `internal` — only the runtime should swap Jobs.
     */
    internal fun swapJob(newJob: Job) {
        cancelRequestedAtMs = null
        cancelReason = null
        _pauseRequested = false
        _retryNowRequested = false
        job = newJob
    }

    internal fun markCancelRequested(reason: String = "explicit_cancel") {
        cancelRequestedAtMs = System.currentTimeMillis()
        cancelReason = reason
    }

    private fun refreshStateFromCompletedJob() {
        val current = _state.value
        if (current != RuntimeState.CLOSED && current != RuntimeState.ERROR && job.isCompleted) {
            updateState(RuntimeState.CLOSED)
        }
    }

    /**
     * Update the visible state. Illegal transitions are dropped
     * silently because the DB column is the source of truth and a
     * race between two writers should not crash either side. The
     * caller (the runtime) is expected to also write the DB.
     */
    internal fun updateState(next: RuntimeState) {
        val prev = _state.value
        if (next != prev && (RuntimeState.canTransition(prev, next) || next == RuntimeState.CLOSED)) {
            _state.value = next
        }
    }

    /**
     * Set the visible state unconditionally (bypassing the
     * canTransition check).  Reserved for the runtime's
     * hook-aware [transition] entry point — every other code
     * path MUST go through [AgentRuntime.cancel] /
     * `transition` so the hook chain is consulted.
     */
    internal fun setState(next: RuntimeState) {
        if (next != _state.value) _state.value = next
    }

    /**
     * Suspend until [predicate] matches the current runtime state,
     * or until [timeoutMs] elapses. Returns [AwaitResult] in both
     * cases; callers should branch on [AwaitResult.timedOut].
     *
     * Polling interval is [pollIntervalMs] (default 250ms). The
     * runtime pushes state updates via [updateState] so the wait
     * can resolve faster than the poll for deterministic
     * transitions.
     */
    suspend fun await(
        predicate: (RuntimeState) -> Boolean,
        timeoutMs: Long? = null,
        pollIntervalMs: Long = 250L,
    ): AwaitResult {
        val start = System.currentTimeMillis()
        refreshStateFromCompletedJob()
        // Fast path: predicate already holds.
        if (predicate(_state.value)) {
            return AwaitResult(_state.value, timedOut = false, elapsedMs = 0L)
        }
        val resolved = withTimeoutOrNull(timeoutMs ?: Long.MAX_VALUE) {
            _state.first { predicate(it) }
            refreshStateFromCompletedJob()
            _state.value
        } ?: run {
            refreshStateFromCompletedJob()
            _state.value
        }
        val elapsed = System.currentTimeMillis() - start
        val matched = predicate(resolved)
        return AwaitResult(
            finalState = resolved,
            timedOut = !matched,
            elapsedMs = elapsed,
            lastError = if (errorSink.isCompleted) errorSink.getCompleted() else null,
        )
    }

    /**
     * Request cooperative cancellation of the underlying Job.
     * Marked `internal` because the only safe caller is
     * [AgentRuntime.cancel] / [AgentRuntime.cancelSubtree],
     * which is the entry point that consults the
     * [RuntimeStateHook] chain.  External code that wants to
     * cancel a session MUST go through [AgentRuntime.cancel] so
     * the ancestor-only rule is enforced and the deny outcome
     * is recorded.
     */
    internal fun cancel(
        @Suppress("UNUSED_PARAMETER") callerSessionId: String? = null,
        reason: String = "explicit_cancel",
    ): Boolean {
        markCancelRequested(reason)
        if (!job.isActive) return false
        updateState(RuntimeState.CANCELLING)
        job.cancel()
        return true
    }

    /** Set the error sink so a future [await] call can return it. */
    internal fun reportError(message: String?) {
        if (!errorSink.isCompleted) errorSink.complete(message)
    }
}
