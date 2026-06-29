package org.iotsplab.akiba.llm.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.apache.logging.log4j.LogManager
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// ============================================================
//  AgentRuntime — per-binary orchestrator
// ============================================================

/**
 * Per-binary singleton that owns the [JobScheduler] and the
 * "child agent Job" lifecycle. The runtime is the only
 * permitted writer of [RuntimeState] (the daemon route mirrors
 * the value to the DB).
 *
 * Acquire an instance with [AgentRuntime.forBinary]; the
 * registry keeps one per binary id for the lifetime of the JVM.
 *
 * Surface:
 *  - [spawn] — schedule a child Job, returns a [JobHandle]
 *    immediately. The actual agent.run() happens in a coroutine
 *    on the scheduler's scope, after the caps admit it.
 *  - [cancel] — request a single session's transition to
 *    CANCELLING.  Routed through every registered
 *    [RuntimeStateHook]; may be denied.
 *  - [cancelSubtree] — cascade cancel; waits up to [graceMs]
 *    per child for the child to reach a terminal state, then
 *    hard-cancels anything still running.
 *  - [listLiveChildren] — direct children of a session that the
 *    runtime currently tracks (used by cascade cancel walkers
 *    that want the in-process view, not the DB view).
 *
 * State-machine writes go through [transition], which is the
 * single point that consults the hook chain and mirrors the
 * result to the DB.
 */
class AgentRuntime(
    val binaryId: Int,
    val agentDbClient: AgentDatabaseClient,
    val config: SchedulerConfig = SchedulerConfig(),
    /**
     * Override the coroutine scope (mostly for tests). Default is
     * a per-runtime SupervisorJob so one misbehaving child does
     * not bring down the whole binary.
     */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * Optional initial hook list.  When null, the runtime
     * registers a single [DefaultStateHook] (which enforces the
     * ancestor-only cancellation rule).  Tests can pass an empty
     * list to disable the gate entirely.
     */
    initialHooks: List<RuntimeStateHook>? = null,
) {
    private val logger = LogManager.getLogger(AgentRuntime::class.java)

    val scheduler = JobScheduler(binaryId, config, scope)

    // sessionId -> JobHandle (admitted + queued; only present while
    // the Job is registered with the scheduler)
    private val handles = ConcurrentHashMap<String, JobHandle>()

    // parentSessionId -> set of direct child handles (for fast
    // cascade cancel without an extra DB round-trip)
    private val childrenByParent = ConcurrentHashMap<String, MutableSet<String>>()

    // sessionId -> CancellationPolicy (snapshot at spawn time,
    // used by transition() so we don't have to walk the template
    // registry on every hook evaluation).
    private val sessionPolicy = ConcurrentHashMap<String, CancellationPolicy>()

    /**
     * Per-session registration kept by [spawn] so that
     * [resumeStandby] can re-invoke the same factory closure on
     * a fresh coroutine when a STANDBY session is woken by
     * incoming mailbox messages.  Cross-process resume is NOT
     * supported: the factory lives in this JVM only, so a
     * different process can only observe "standby + unread" in
     * the DB but cannot reconstruct the agent.
     */
    private data class SpawnEntry(
        val handle: JobHandle,
        val lifecycle: Lifecycle,
        val initialTaskPrompt: String,
        val factory: suspend (JobHandle) -> AkibaAgent,
    )

    private val spawnEntries = ConcurrentHashMap<String, SpawnEntry>()

    // Monotonic counter for StateTransition.transitionId.
    private val transitionSeq = AtomicLong(0)

    // Hook chain.  Built once at construction; callers can
    // introspect but should not mutate.
    private val stateHook: RuntimeStateHook = if (initialHooks == null) {
        DefaultStateHook(agentDbClient)
    } else if (initialHooks.isEmpty()) {
        // No-op allow-all hook so transition() can call a single
        // uniform entry point.
        RuntimeStateHook { StateTransitionDecision.ALLOW }
    } else {
        CompositeStateHook(initialHooks.toList())
    }

    // Last transition outcome per session.  Used by
    // [transition] callers (e.g. cancel) to surface "denied by
    // hook" without throwing.
    private val lastTransitionOutcome =
        ConcurrentHashMap<String, StateTransitionOutcome>()

    /**
     * Spawn a child agent. The factory is suspended and runs on
     * the scheduler's scope after admission. The returned handle's
     * [JobHandle.state] starts at [RuntimeState.RUNNING] and
     * transitions to [RuntimeState.STANDBY] (lifecycle=standby,
     * clean exit) or [RuntimeState.CLOSED] (one-shot, clean exit)
     * when [factory] returns.
     *
     * The factory is responsible for constructing the [AkibaAgent]
     * and returning it; the runtime wraps the resulting agent's
     * `run(taskPrompt)` call so the state machine observes its
     * outcome.
     */
    fun spawn(
        parentSessionId: String,
        childSessionId: String,
        /**
         * Root session id of the tree this child belongs to. The
         * caller MUST supply this — pass the caller's own
         * `rootSessionId` if the caller is not itself a root, or
         * [childSessionId] when the caller is a fresh root agent.
         * The [JobScheduler] uses this to enforce per-root caps.
         */
        rootSessionId: String,
        /**
         * Template id this child was spawned from, or null when the
         * child is built ad-hoc (free-form system prompt + tool
         * subset, no template validation).
         */
        templateId: String?,
        depth: Int,
        initialLifecycle: Lifecycle,
        taskPrompt: String,
        factory: suspend (JobHandle) -> AkibaAgent,
    ): JobHandle {
        require(childSessionId.isNotBlank()) { "childSessionId must not be blank" }
        require(rootSessionId.isNotBlank()) { "rootSessionId must not be blank" }
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            runChildJob(childSessionId, taskPrompt, factory)
        }
        val handle = JobHandle(
            sessionId = childSessionId,
            templateId = templateId,
            parentSessionId = parentSessionId,
            rootSessionId = rootSessionId,
            depth = depth,
            initialState = RuntimeState.RUNNING,
            job = job,
        )
        handles[childSessionId] = handle
        childrenByParent
            .computeIfAbsent(parentSessionId) { ConcurrentHashMap.newKeySet() }
            .add(childSessionId)

        // Snapshot the template's CancellationPolicy so the
        // hook chain has it without an extra registry walk.
        // Freeform children default to ANCESTOR_ONLY, matching
        // the InteractionPolicy data-class default.
        val policy = templateId
            ?.let { AgentTemplateRegistry.get(it)?.interactionPolicy?.canBeCancelledBy }
            ?: CancellationPolicy.ANCESTOR_ONLY
        sessionPolicy[childSessionId] = policy

        // Remember the factory + initial prompt so resumeStandby
        // can re-run a fresh agent.run() on the same memory once
        // the session is woken from STANDBY.
        spawnEntries[childSessionId] = SpawnEntry(
            handle = handle,
            lifecycle = initialLifecycle,
            initialTaskPrompt = taskPrompt,
            factory = factory,
        )

        scheduler.register(handle) { admitted ->
            // First admission callback: start the Job.
            admitted.coroutineJob.start()
        }
        // Persist runtime_state=running so the DB matches the
        // in-process state. The session was already created by
        // the caller; the runtime is just bookkeeping.  Use the
        // direct path: this transition is the spawn itself, not
        // a lifecycle change, and it must always succeed.
        try {
            agentDbClient.setRuntimeState(childSessionId, RuntimeState.RUNNING.wire())
        } catch (e: Exception) {
            logger.warn("Failed to set runtime_state=running for $childSessionId: ${e.message}")
        }
        return handle
    }

    /**
     * Body of the child Job. Runs the factory, then `agent.run(taskPrompt)`.
     * Lifecycle-to-state mapping:
     *  - lifecycle=one_shot + COMPLETED  → `closed`
     *  - lifecycle=standby  + COMPLETED  → `standby` (awaiting mailbox)
     *  - anything else                   → `error`
     */
    private suspend fun runChildJob(
        sessionId: String,
        taskPrompt: String,
        factory: suspend (JobHandle) -> AkibaAgent,
    ) {
        val handle = handles[sessionId] ?: run {
            logger.error("runChildJob($sessionId) called without a handle")
            return
        }
        try {
            val agent = factory(handle)
            val lifecycle = try {
                agent.lifecycle
            } catch (e: Exception) {
                Lifecycle.ONE_SHOT
            }

            val result = agent.run(taskPrompt)

            val nextState = when {
                result.stopReason == StopReason.STANDBY && lifecycle == Lifecycle.STANDBY -> RuntimeState.STANDBY
                result.stopReason == StopReason.STANDBY -> RuntimeState.CLOSED
                result.stopReason == StopReason.COMPLETED && lifecycle == Lifecycle.STANDBY ->
                    RuntimeState.STANDBY
                result.stopReason == StopReason.COMPLETED -> RuntimeState.CLOSED
                else -> RuntimeState.ERROR
            }
            val finalReason = mapStopReason(result.stopReason)
            transition(
                handle = handle,
                next = nextState,
                reason = finalReason,
                requesterSessionId = null,  // internal lifecycle, no agent initiates
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cooperative cancel (system / ancestor via
            // AgentRuntime.cancel).  A cancelled coroutine is terminal
            // for the current run, so close it after the CANCELLING
            // transition that cancel() already wrote.
            logger.info("Child job for $sessionId was cancelled")
            handle.reportError("cancelled")
            transition(
                handle = handle,
                next = RuntimeState.CLOSED,
                reason = "cancelled",
                requesterSessionId = null,
            )
            throw e
        } catch (e: Throwable) {
            logger.error("Child job for $sessionId crashed: ${e.message}", e)
            val finalReason = "error: ${e.javaClass.simpleName}: ${e.message?.take(200)}"
            handle.reportError(finalReason)
            transition(
                handle = handle,
                next = RuntimeState.ERROR,
                reason = finalReason,
                requesterSessionId = null,
            )
        } finally {
            scheduler.release(sessionId)
            childrenByParent[handle.parentSessionId]?.remove(sessionId)
            // We deliberately keep the policy snapshot until
            // process death so late hook evaluations (e.g. an
            // audit tool reading after the agent closed) still
            // see the right value.
        }
    }

    private fun mapStopReason(sr: StopReason): String = when (sr) {
        StopReason.COMPLETED -> "completed"
        StopReason.STANDBY -> "standby"
        StopReason.MAX_ITERATIONS -> "max_iterations"
        StopReason.ERROR -> "error"
    }

    /**
     * Request a single session transition to CANCELLING.  Routed
     * through the registered [RuntimeStateHook] chain; if denied,
     * the session stays on its current state, the DB column is not
     * touched, and the call returns false with [lastOutcome]
     * carrying the reason.
     *
     * @param sessionId       target session
     * @param callerSessionId who is asking.  `null` means a
     *                         system-level caller (the orphan
     *                         reaper, the cascade-parent cleanup,
     *                         or a CLI).  Any non-null value is
     *                         subject to [DefaultStateHook]'s
     *                         ancestor-only rule.
     * @param reason          free-form reason (logged + persisted
     *                         as `closing_reason` on success).
     * @param graceMs         how long to wait for the child to
     *                         reach a terminal state on its own
     *                         before the runtime hard-cancels the
     *                         underlying Job.
     * @return true when the cancel was applied (or already in
     *         flight).  false when the hook denied it, the
     *         handle is gone, or the underlying Job threw.
     */
    suspend fun cancel(
        sessionId: String,
        callerSessionId: String? = null,
        reason: String = "explicit_cancel",
        graceMs: Long = 30_000L,
    ): Boolean {
        val handle = handles[sessionId] ?: return false
        val outcome = transition(
            handle = handle,
            next = RuntimeState.CANCELLING,
            reason = reason,
            requesterSessionId = callerSessionId,
        )
        if (!outcome.allowed) {
            logger.info(
                "AgentRuntime.cancel denied for $sessionId " +
                    "by requester=${callerSessionId ?: "system"}: ${outcome.denyReason}"
            )
            return false
        }
        handle.cancel(callerSessionId, reason)
        return try {
            val result = handle.await(
                predicate = AwaitPredicate.TERMINAL,
                timeoutMs = graceMs,
            )
            if (result.timedOut) {
                handle.coroutineJob.cancel()
                transition(
                    handle = handle,
                    next = RuntimeState.CLOSED,
                    reason = "$reason:hard_cancel",
                    requesterSessionId = callerSessionId,
                )
            }
            true
        } catch (e: Exception) {
            logger.warn("cancel($sessionId) failed: ${e.message}", e)
            false
        }
    }

    /**
     * Cancel every non-closed descendant of [rootSessionId]. Walks
     * the live subtree once (DB) and cancels each child with
     * [reason] and [graceMs] per child. Returns the number of
     * children that were actually cancelled (denials are not
     * counted; use [lastOutcome] to inspect per-session results).
     *
     * The cascade itself runs under [callerSessionId] so each
     * child-level cancel inherits the caller's authority.
     */
    suspend fun cancelSubtree(
        rootSessionId: String,
        callerSessionId: String? = null,
        reason: String = "parent_closing",
        graceMs: Long = 30_000L,
    ): Int {
        val live = try {
            agentDbClient.listLiveSubtree(rootSessionId, includeClosed = false)
        } catch (e: Exception) {
            logger.warn("listLiveSubtree($rootSessionId) failed: ${e.message}")
            return 0
        }
        var count = 0
        for (row in live) {
            if (row.sessionId == rootSessionId) continue
            val ok = cancel(row.sessionId, callerSessionId, reason = reason, graceMs = graceMs)
            if (ok) count++
        }
        return count
    }

    /**
     * Direct children of [parentSessionId] currently registered.
     */
    fun listLiveChildren(parentSessionId: String): List<JobHandle> {
        val ids = childrenByParent[parentSessionId] ?: return emptyList()
        return ids.mapNotNull { handles[it] }
    }

    /**
     * Whether [sessionId] was spawned in this JVM.  False for
     * sessions spawned in another process (the factory closure
     * lives there, not here).  The mailbox dispatcher uses this
     * to skip cross-process standby sessions — those can only
     * be observed in the DB, not resumed locally.
     */
    fun canResume(sessionId: String): Boolean = spawnEntries.containsKey(sessionId)

    /**
     * Resume a STANDBY-parked session by re-running
     * `agent.run(newTaskPrompt)` on a fresh coroutine Job that
     * replaces the handle's underlying (now-completed) Job.  The
     * session's memory + transcript are carried over because
     * `AkibaAgent.run` reads from the same persistent memory
     * instance the prior run wrote to.
     *
     * State path: STANDBY → MSGHANDLE → RUNNING (during the new
     * run) → STANDBY on clean exit / CLOSED on terminal.
     *
     * Pre-conditions:
     *  - [sessionId] is a session this runtime spawned (factory
     *    closure is available).  Callers that may be handling
     *    cross-process sessions MUST check [canResume] first.
     *  - The session is currently at `runtime_state='standby'`.
     *  - The session's lifecycle is `STANDBY` (ONE_SHOT sessions
     *    are terminal and cannot be resumed).
     *
     * Cancellation: a concurrent `cancel(sessionId, ...)` cancels
     * the new Job; the resumed `runChildJob` translates the
     * resulting [kotlinx.coroutines.CancellationException] into
     * a CANCELLING state transition.
     *
     * @return the new coroutine [Job].
     */
    fun resumeStandby(
        sessionId: String,
        newTaskPrompt: String = newStandbyResumePrompt(),
    ): Job {
        val entry = spawnEntries[sessionId]
            ?: error("AgentRuntime.resumeStandby: $sessionId is not registered in this runtime")
        // Allow either:
        //   (a) lifecycle=STANDBY — the normal case, OR
        //   (b) lifecycle=ONE_SHOT BUT the handle is currently parked at
        //       STANDBY — a mismatched template declaration.  This avoids
        //       a hard throw when a template was authored as ONE_SHOT but
        //       later paired with a mailbox tool that caused it to park.
        // The state must be STANDBY in either case (enforced by the
        // second require below).
        require(
            entry.lifecycle == Lifecycle.STANDBY ||
                (entry.lifecycle == Lifecycle.ONE_SHOT && entry.handle.state.value == RuntimeState.STANDBY)
        ) {
            "AgentRuntime.resumeStandby: $sessionId lifecycle=${entry.lifecycle} " +
                "(state=${entry.handle.state.value}) is not eligible for resume; " +
                "expected lifecycle=STANDBY, or lifecycle=ONE_SHOT with state=STANDBY"
        }
        val handle = entry.handle
        require(handle.state.value == RuntimeState.STANDBY) {
            "AgentRuntime.resumeStandby: $sessionId is not parked at STANDBY (current=${handle.state.value})"
        }

        // Move STANDBY → MSGHANDLE → RUNNING through the hook
        // chain.  Both transitions are system-level (no agent
        // initiated) so DefaultStateHook's ancestor-only check
        // does not apply.
        transition(
            handle = handle,
            next = RuntimeState.MSGHANDLE,
            reason = "standby_resume",
            requesterSessionId = null,
        )
        // The MSGHANDLE → RUNNING transition is implicit in
        // the spawn-time transition; here we just confirm it
        // is the right next state.
        transition(
            handle = handle,
            next = RuntimeState.RUNNING,
            reason = "standby_resume",
            requesterSessionId = null,
        )

        // Create a fresh coroutine Job, swap into the existing
        // handle, and re-register with the scheduler so the
        // cap counters re-admit the session.
        val newJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            runChildJob(sessionId, newTaskPrompt, entry.factory)
        }
        handle.swapJob(newJob)
        scheduler.register(handle) { admitted ->
            admitted.coroutineJob.start()
        }
        logger.info(
            "AgentRuntime.resumeStandby: $sessionId resumed on a fresh Job " +
                "(prompt length=${newTaskPrompt.length})"
        )
        return newJob
    }

    /**
     * Return the recorded outcome of the last [transition] call
     * targeting [sessionId], or null if the runtime has not
     * driven a transition for it yet.
     */
    fun lastOutcome(sessionId: String): StateTransitionOutcome? =
        lastTransitionOutcome[sessionId]

    /**
     * The single state-transition entry point.  Builds a
     * [StateTransition] snapshot, consults the registered hook
     * chain, and on ALLOW updates the in-memory JobHandle and
     * mirrors the new value to the DB.  On DENY the state stays
     * put and the DB column is not touched.
     */
    private fun transition(
        handle: JobHandle,
        next: RuntimeState,
        reason: String?,
        requesterSessionId: String?,
    ): StateTransitionOutcome {
        val prev = handle.state.value
        val transitionId = transitionSeq.incrementAndGet()
        val snapshot = StateTransition(
            sessionId = handle.sessionId,
            templateId = handle.templateId,
            parentSessionId = handle.parentSessionId,
            depth = handle.depth,
            prev = prev,
            next = next,
            reason = reason,
            requesterSessionId = requesterSessionId,
            transitionId = transitionId,
            timestampMs = System.currentTimeMillis(),
        )

        // The hook chain decides; its decision is the source
        // of truth for "should we proceed".
        val decision = stateHook.onStateTransition(snapshot)
        if (decision == StateTransitionDecision.DENY) {
            val outcome = StateTransitionOutcome(
                sessionId = handle.sessionId,
                prev = prev,
                nextAttempted = next,
                allowed = false,
                denyReason = "RuntimeStateHook denied transition ${prev.wire()}→${next.wire()}",
                transitionId = transitionId,
            )
            lastTransitionOutcome[handle.sessionId] = outcome
            return outcome
        }

        // Allowed: update in-memory state and mirror to DB.
        handle.updateState(next)
        try {
            agentDbClient.setRuntimeState(handle.sessionId, next.wire(), reason)
        } catch (e: Exception) {
            logger.warn(
                "Failed to set runtime_state=${next.wire()} for ${handle.sessionId}: ${e.message}"
            )
        }
        try {
            agentDbClient.updateSession(handle.sessionId, status = statusFor(next, reason))
        } catch (e: Exception) {
            logger.warn(
                "Failed to set status=${statusFor(next, reason)} for ${handle.sessionId}: ${e.message}"
            )
        }
        val outcome = StateTransitionOutcome(
            sessionId = handle.sessionId,
            prev = prev,
            nextAttempted = next,
            allowed = true,
            denyReason = null,
            transitionId = transitionId,
        )
        lastTransitionOutcome[handle.sessionId] = outcome
        return outcome
    }

    /**
     * Map a runtime_state transition onto the legacy `status` column.
     * The column is kept as a mirror of `runtime_state` for older
     * views / dashboard widgets that still read it; cancellation is
     * distinguished from a normal close by the `closing_reason` column,
     * not by the `status` value.
     */
    private fun statusFor(next: RuntimeState, reason: String?): String = next.wire()


    /** Shutdown the runtime: stop the scheduler's runner. */
    fun shutdown() {
        scheduler.shutdown()
    }

    companion object {
        /**
         * Prefix of the synthetic resume marker.  The marker
         * `[[AKIBA_INTERNAL:STANDBY_RESUME:<uuid>]]` is generated
         * by [newStandbyResumePrompt] for every
         * [resumeStandby] invocation.  The string is deliberately
         * chosen so that a real user input is extremely unlikely to
         * match (square-bracketed all-caps prefix + a random UUID).
         *
         * AkibaAgent detects the marker by prefix+suffix match —
         * NOT by exact equality — so a user that happens to type
         * the old fixed prompt will not be mis-classified as a
         * resume.
         */
        const val STANDBY_RESUME_PROMPT_PREFIX: String =
            "[[AKIBA_INTERNAL:STANDBY_RESUME:"
        const val STANDBY_RESUME_PROMPT_SUFFIX: String = "]]"

        /**
         * Build a fresh synthetic resume marker.  Format:
         * `[[AKIBA_INTERNAL:STANDBY_RESUME:<uuid>]]`.  Each call
         * produces a new UUID-suffixed token so collisions with
         * user input are vanishingly unlikely.  The LLM sees the
         * just-drained mailbox messages as user messages injected
         * by the default
         * [org.iotsplab.akiba.llm.agent.AgentHarness.beforeIteration];
         * this marker just announces "this is a resume, not a
         * cold start" so the LLM does not greet the user again.
         */
        fun newStandbyResumePrompt(): String =
            STANDBY_RESUME_PROMPT_PREFIX + UUID.randomUUID() + STANDBY_RESUME_PROMPT_SUFFIX

        private val runtimes = ConcurrentHashMap<Int, AgentRuntime>()

        fun forBinary(
            binaryId: Int,
            agentDbClient: AgentDatabaseClient,
            config: SchedulerConfig = SchedulerConfig(),
        ): AgentRuntime = runtimes.computeIfAbsent(binaryId) {
            AgentRuntime(binaryId, agentDbClient, config)
        }

        /** Test/CLI hook to wipe the registry (e.g. between test runs). */
        fun resetForTests() {
            runtimes.values.forEach { it.shutdown() }
            runtimes.clear()
        }
    }
}

/**
 * Structured result of a single [AgentRuntime.transition] call.
 * Returned through [AgentRuntime.lastOutcome] so callers
 * (orchestrator tools, the cascade-cancel path) can surface
 * "denied by hook" outcomes without parsing log lines.
 */
data class StateTransitionOutcome(
    val sessionId: String,
    val prev: RuntimeState,
    val nextAttempted: RuntimeState,
    val allowed: Boolean,
    val denyReason: String?,
    val transitionId: Long,
)
