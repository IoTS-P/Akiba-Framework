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

    val scheduler = JobScheduler(config, scope)

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
        /**
         * Final-Answer policy declared at spawn time.  Mirrors
         * [AkibaAgent.onFinalAnswer] for the agent built by
         * [factory] so [runChildJob] can decide the post-Final
         * runtime_state without having to construct the agent
         * (the agent doesn't exist yet at spawn time).
         *
         * A STANDBY child in PARK mode stays in STANDBY after
         * Final Answer; a STANDBY child in EXIT mode (or any
         * ONE_SHOT child) goes to CLOSED.
         */
        val onFinalAnswer: FinalAnswerAction,
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
     * @param coldStart  When true and [initialLifecycle] is STANDBY,
     *                   the child skips its initial `agent.run()` and
     *                   parks directly to `runtime_state=standby`.
     *                   Only meaningful for STANDBY lifecycle;
     *                   ignored for ONE_SHOT.  Default true.
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
        /**
         * When true and [initialLifecycle] is STANDBY, skip the
         * child's first LLM call and park directly.  Ignored for
         * ONE_SHOT.  Default true.
         */
        coldStart: Boolean = true,
        /**
         * Final-Answer policy for the child.  See
         * [FinalAnswerAction].  The default (EXIT) matches
         * [AkibaAgent]'s default for `lifecycle=ONE_SHOT`; STANDBY
         * callers should pass `PARK` explicitly so a parked child
         * stays STANDBY after Final Answer instead of going CLOSED.
         */
        onFinalAnswer: FinalAnswerAction = FinalAnswerAction.EXIT,
        taskPrompt: String,
        factory: suspend (JobHandle) -> AkibaAgent,
        forceCompactBeforeRun: Boolean = false,
    ): JobHandle {
        require(childSessionId.isNotBlank()) { "childSessionId must not be blank" }
        require(rootSessionId.isNotBlank()) { "rootSessionId must not be blank" }
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            // First time the Job runs is the spawn-time "cold
            // start": a STANDBY child with `coldStart=true` parks
            // immediately and waits for mailbox messages.  The
            // resumeStandby path creates a fresh Job that calls
            // runChildJob with `coldStart=false` so the child
            // actually runs the LLM loop.
            runChildJob(
                childSessionId,
                taskPrompt,
                factory,
                coldStart = coldStart,
                forceCompactBeforeRun = forceCompactBeforeRun,
            )
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
            onFinalAnswer = onFinalAnswer,
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
        try {
            agentDbClient.updateSession(childSessionId, status = RuntimeState.RUNNING.wire())
        } catch (e: Exception) {
            logger.warn("Failed to set status=running for $childSessionId: ${e.message}")
        }
        // Mirror the requested lifecycle onto the DB row.  Without
        // this the child row keeps its SQL DEFAULT 'one_shot', and
        // downstream consumers that filter by `lifecycle='standby'`
        // (notably [AgentMailboxDispatcher.findStandbyWithUnread]
        // and the per-sender access policy in the mailbox send
        // route) silently skip the child — STANDBY children would
        // never be woken from standby.
        //
        // Idempotent: if the value is already what we want, the
        // UPDATE is a no-op.  Both `one_shot` and `standby` are
        // legal values for the column's CHECK constraint.
        try {
            agentDbClient.setSessionLifecycle(
                childSessionId,
                initialLifecycle.name.lowercase(),
            )
        } catch (e: Exception) {
            logger.warn(
                "Failed to set lifecycle=${initialLifecycle.name.lowercase()} " +
                    "for $childSessionId: ${e.message}"
            )
        }
        return handle
    }

    /**
     * Register a top-level module agent with the runtime so the
     * normal mailbox dispatcher can resume it after it parks in
     * STANDBY. Root agents are initially run by [AgentModule]
     * directly (not through [spawn]), so without this adoption step
     * [AgentMailboxDispatcher] would see `standby + unread` in the
     * DB but [canResume] would return false and skip the wake.
     */
    fun registerRootStandbySession(
        sessionId: String,
        lifecycle: Lifecycle,
        onFinalAnswer: FinalAnswerAction,
        taskPrompt: String,
        factory: suspend (JobHandle) -> AkibaAgent,
    ): JobHandle {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        val existing = spawnEntries[sessionId]
        if (existing != null) return existing.handle

        val placeholderJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { }
        val handle = JobHandle(
            sessionId = sessionId,
            templateId = null,
            parentSessionId = sessionId,
            rootSessionId = sessionId,
            depth = 0,
            initialState = RuntimeState.RUNNING,
            job = placeholderJob,
        )
        handles[sessionId] = handle
        sessionPolicy[sessionId] = CancellationPolicy.ANCESTOR_ONLY
        spawnEntries[sessionId] = SpawnEntry(
            handle = handle,
            lifecycle = lifecycle,
            initialTaskPrompt = taskPrompt,
            factory = factory,
            onFinalAnswer = onFinalAnswer,
        )
        try {
            agentDbClient.setRuntimeState(sessionId, RuntimeState.RUNNING.wire())
        } catch (e: Exception) {
            logger.warn("Failed to set runtime_state=running for root $sessionId: ${e.message}")
        }
        try {
            agentDbClient.setSessionLifecycle(sessionId, lifecycle.name.lowercase())
        } catch (e: Exception) {
            logger.warn("Failed to set lifecycle=${lifecycle.name.lowercase()} for root $sessionId: ${e.message}")
        }
        return handle
    }

    /**
     * Mirror a directly-run root agent's PARK result into the
     * runtime handle. The root's strategy already wrote the DB row;
     * this keeps the in-process handle at STANDBY so the dispatcher
     * can later call [resumeStandby].
     */
    fun markRegisteredSessionStandby(
        sessionId: String,
        reason: String = "root_parked",
    ): Boolean {
        val handle = spawnEntries[sessionId]?.handle ?: return false
        if (handle.state.value == RuntimeState.STANDBY) return true
        if (handle.state.value != RuntimeState.RUNNING && handle.state.value != RuntimeState.MSGHANDLE) {
            logger.warn(
                "markRegisteredSessionStandby($sessionId) ignored: current=${handle.state.value}"
            )
            return false
        }
        transition(
            handle = handle,
            next = RuntimeState.STANDBY,
            reason = reason,
            requesterSessionId = null,
        )
        return handle.state.value == RuntimeState.STANDBY
    }

    /**
     * Body of the child Job. Runs the factory, then `agent.run(taskPrompt)`.
     *
     * @param coldStart when true (spawn path only), STANDBY
     *                  children skip their first `agent.run()` and
     *                  park directly into `runtime_state=standby`.
     *                  The [resumeStandby] path passes `false` so
     *                  the woken child actually runs the LLM loop on
     *                  the freshly-injected mailbox messages.  See
     *                  the cold-start STANDBY comment block below
     *                  for the full rationale.
     *
     * Lifecycle-to-state mapping:
     *  - lifecycle=one_shot + COMPLETED  → `closed`
     *  - lifecycle=standby  + COMPLETED  → `standby` (awaiting mailbox)
     *  - anything else                   → `error`
     */
    private suspend fun runChildJob(
        sessionId: String,
        taskPrompt: String,
        factory: suspend (JobHandle) -> AkibaAgent,
        coldStart: Boolean = false,
        forceCompactBeforeRun: Boolean = false,
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

            // No per-child termination-hook installation needed.
            // [AkibaAgent]'s constructor `init` block installs a
            // default hook of `{ defaultTerminate() }` which runs
            // [AkibaAgent.cascadeCancel] (the same cascade-walk this
            // block used to inline — now delegated to the agent so
            // grandchildren get cancelled by the child's own hook,
            // not just by the root's) and [AkibaAgent.close] (the
            // LLM client + transcript release).  A custom hook from
            // the factory's `akibaAgent { onTermination { ... } }`
            // DSL — when present — wins, since it overwrites the
            // field right after construction.
            //
            // The runtime's own [transition] call below is what
            // flips `runtime_state`; the default hook deliberately
            // does NOT re-write that column to avoid a race with
            // the runtime's hook-aware writer.
            //
            // What the runtime DOES need to install is the
            // [AkibaAgent.cascadeCanceller] callback — that's the
            // piece of glue between the agent's
            // "cancel my own subtree" default step and the
            // per-binary [AgentRuntime] that knows how to walk the
            // DB.  Without it the child agent's `defaultTerminate`
            // would silently no-op the cascade step, leaving
            // grandchildren to the [OrphanReaper] 60s backstop.
            // We capture the canceller as a closure over `this`
            // (the runtime) so the agent has no hard reference
            // back to the runtime — decoupling the two.
            agent.cascadeCanceller = { sid, reason, graceMs ->
                cascadeCancelChildren(sid, reason, graceMs)
            }

            // ── Cold-start STANDBY: park immediately, do not LLM ───────
            //
            // When the lifecycle is STANDBY and `coldStart` is true
            // (the default for ProgrammaticSubAgentSpec), there are no
            // mailbox messages on first spawn and running agent.run()
            // would produce a pointless format reminder.  We skip the
            // LLM entirely and transition directly to STANDBY.
            // The mailbox dispatcher will wake the agent via
            // resumeStandby() once a message arrives.
            //
            // The resume path passes `coldStart=false` so the woken
            // child actually runs the LLM loop on the freshly-injected
            // mailbox messages.  Without this distinction the resume
            // path would re-enter the cold-start branch and immediately
            // re-park, leaving the agent to never see its messages.
            //
            // When `coldStart` is false on initial spawn, the child
            // runs its initial taskPrompt even in STANDBY mode —
            // useful for agents that need to perform one-shot setup
            // (e.g. loading state from the database) before entering
            // standby.
            if (lifecycle == Lifecycle.STANDBY && coldStart) {
                logger.info(
                    "[runChildJob] STANDBY child $sessionId spawned; " +
                        "coldStart=true, parking directly to standby"
                )
                transition(
                    handle = handle,
                    next = RuntimeState.STANDBY,
                    reason = "cold_start_park",
                    requesterSessionId = null,
                )
                return
            }

            if (forceCompactBeforeRun) {
                try {
                    val compacted = agent.compact()
                    logger.info(
                        "[runChildJob] forceCompactBeforeRun for $sessionId completed: compacted=$compacted"
                    )
                } catch (e: Throwable) {
                    logger.warn(
                        "[runChildJob] forceCompactBeforeRun for $sessionId failed: " +
                            "${e.javaClass.simpleName}: ${e.message}",
                        e,
                    )
                }
            }

            val result = agent.runWithTermination(taskPrompt)

            // Map StopReason + lifecycle + onFinalAnswer to the
            // next runtime_state.
            //
            // The "Enter standby mode." marker is unambiguous: it
            // always means "park" for STANDBY children, "close" for
            // ONE_SHOT children (the marker is treated as a no-op
            // final answer when the session is terminal anyway).
            //
            // Final Answer is more nuanced and depends on the
            // declared [FinalAnswerAction]:
            //  - STANDBY lifecycle + FinalAnswerAction.PARK →
            //    STANDBY (park; the session keeps accepting mail).
            //  - STANDBY lifecycle + FinalAnswerAction.EXIT →
            //    CLOSED (the root STANDBY agent explicitly opted
            //    into true exit; its resources will be released
            //    by the parent AgentModule's startProcess() finally
            //    block).
            //  - ONE_SHOT lifecycle (regardless of action) →
            //    CLOSED (the session is terminal; PARK collapses
            //    to the same end state as EXIT).
            val entry = spawnEntries[sessionId]
            val onFinalAnswer = entry?.onFinalAnswer ?: FinalAnswerAction.EXIT
            val nextState = when {
                result.stopReason == StopReason.STANDBY && lifecycle == Lifecycle.STANDBY -> RuntimeState.STANDBY
                result.stopReason == StopReason.STANDBY -> RuntimeState.CLOSED
                result.stopReason == StopReason.COMPLETED && lifecycle == Lifecycle.STANDBY && onFinalAnswer == FinalAnswerAction.PARK ->
                    RuntimeState.STANDBY
                result.stopReason == StopReason.COMPLETED -> RuntimeState.CLOSED
                // A STANDBY agent that hit max iterations should park
                // back to STANDBY so it can be woken again later, rather
                // than being terminally marked ERROR and stuck.
                result.stopReason == StopReason.MAX_ITERATIONS && lifecycle == Lifecycle.STANDBY -> RuntimeState.STANDBY
                else -> RuntimeState.ERROR
            }
            val finalReason = mapStopReason(result.stopReason, result)
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

    private fun mapStopReason(sr: StopReason, result: AgentResult? = null): String = when (sr) {
        StopReason.COMPLETED -> "completed"
        StopReason.STANDBY -> "standby"
        StopReason.MAX_ITERATIONS -> "max_iterations"
        StopReason.ERROR -> buildString {
            append("error")
            val detail = result?.output?.trim()?.takeIf { it.isNotBlank() }?.take(500)
            if (detail != null) append(": ").append(detail)
        }
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
    /**
     * Pause a running agent session.
     *
     * Sets the [JobHandle.pauseRequested] flag and transitions the
     * runtime state to [RuntimeState.PAUSED].  The agent loop checks
     * the flag at the top of each iteration (after the current LLM
     * response is fully processed) and blocks until [resume] is called.
     *
     * Only sessions in RUNNING or MSGHANDLE state can be paused.
     * STANDBY, terminal, and already-paused sessions are rejected.
     *
     * @return `true` if the pause was applied, `false` if the session
     *   was not found or not in a pausable state.
     */
    fun pause(sessionId: String): Boolean {
        val handle = handles[sessionId] ?: return false
        val current = handle.state.value
        if (current != RuntimeState.RUNNING && current != RuntimeState.MSGHANDLE) {
            logger.info("AgentRuntime.pause: $sessionId is in $current, not pausable")
            return false
        }
        handle.markPauseRequested()
        val outcome = transition(
            handle = handle,
            next = RuntimeState.PAUSED,
            reason = "user_pause",
            requesterSessionId = null,
        )
        if (!outcome.allowed) {
            logger.warn("AgentRuntime.pause: transition denied for $sessionId: ${outcome.denyReason}")
            // Roll back the pause flag since the state transition failed
            handle.clearPauseRequested()
            return false
        }
        logger.info("AgentRuntime.pause: $sessionId paused")
        return true
    }

    /**
     * Resume a paused agent session.
     *
     * Clears the [JobHandle.pauseRequested] flag and transitions the
     * runtime state back to [RuntimeState.RUNNING].  The agent loop,
     * which has been blocked in the pause-check wait loop, will detect
     * the cleared flag on its next poll and resume execution.
     *
     * @return `true` if the resume was applied, `false` if the session
     *   was not found or not in PAUSED state.
     */
    fun resume(sessionId: String): Boolean {
        val handle = handles[sessionId] ?: return false
        val current = handle.state.value
        if (current != RuntimeState.PAUSED) {
            logger.info("AgentRuntime.resume: $sessionId is in $current, not paused")
            return false
        }
        handle.clearPauseRequested()
        val outcome = transition(
            handle = handle,
            next = RuntimeState.RUNNING,
            reason = "user_resume",
            requesterSessionId = null,
        )
        if (!outcome.allowed) {
            logger.warn("AgentRuntime.resume: transition denied for $sessionId: ${outcome.denyReason}")
            return false
        }
        logger.info("AgentRuntime.resume: $sessionId resumed")
        return true
    }

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
     * Cascade-cancel every non-STANDBY descendant of
     * [parentSessionId].  STANDBY children are deliberately
     * left in place — they become orphans and the
     * [OrphanReaper] picks them up on its next scan tick.
     *
     * This is the single chokepoint both the root
     * [AgentModule.startProcess] `terminationHook` and the
     * [runChildJob] `terminationHook` use.  Lifting the
     * walk-then-cancel pair into the runtime means the
     * STANDBY-orphan policy and the per-child `graceMs`
     * cap live in one place rather than being duplicated
     * in two `finally` blocks.
     *
     * Cancellation requests are issued **sequentially** so
     * that a slow child (e.g. one stuck in an LLM call)
     * cannot starve the others; each [cancel] is `suspend`
     * and awaits the child's terminal state with the
     * `graceMs` cap before the next one starts.
     *
     * Errors are caught and logged individually — a failure
     * to cancel one descendant must not stop the cascade
     * from continuing onto the next.
     */
    suspend fun cascadeCancelChildren(
        parentSessionId: String,
        reason: String = "parent_terminated",
        graceMs: Long = 30_000L,
    ): Int {
        val live = try {
            agentDbClient.listLiveSubtree(parentSessionId, includeClosed = false)
        } catch (e: Exception) {
            logger.warn("cascadeCancelChildren: listLiveSubtree($parentSessionId) failed: ${e.message}")
            return 0
        }
        val standby = Lifecycle.STANDBY.name.lowercase()
        val cancelable = live.filter { row ->
            row.sessionId != parentSessionId &&
                row.lifecycle?.lowercase() != standby
        }
        val orphanedStandby = live.count { row ->
            row.sessionId != parentSessionId &&
                row.lifecycle?.lowercase() == standby
        }
        for (row in cancelable) {
            try {
                cancel(
                    sessionId = row.sessionId,
                    callerSessionId = null,
                    reason = reason,
                    graceMs = graceMs,
                )
            } catch (e: Exception) {
                logger.warn(
                    "cascadeCancelChildren: cancel of ${row.sessionId} failed: ${e.message}",
                    e
                )
            }
        }
        if (cancelable.isNotEmpty() || orphanedStandby > 0) {
            logger.info(
                "cascadeCancelChildren: parent=$parentSessionId — cancelled " +
                    "${cancelable.size} one_shot child(ren), left $orphanedStandby " +
                    "STANDBY child(ren) as orphans for the OrphanReaper"
            )
        }
        return cancelable.size
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
        // cap counters re-admit the session.  We explicitly pass
        // `coldStart=false` so the woken child actually runs the
        // LLM loop on the freshly-injected mailbox messages —
        // re-using the spawn-time `coldStart` flag would
        // re-enter the "park immediately" branch and the agent
        // would never see its messages.
        val newJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            runChildJob(sessionId, newTaskPrompt, entry.factory, coldStart = false)
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
     * Resume a CLOSED or ERROR session for user-injection.
     *
     * Unlike [resumeStandby] (which only works for STANDBY), this
     * method restarts a session that has already terminated — either
     * cleanly (CLOSED) or due to a failure (ERROR).  The user sends a
     * hint message via the frontend; the runtime transitions the
     * session back to RUNNING and launches a fresh agent.run() so the
     * LLM sees the user's hint and can act on it.
     *
     * The [RuntimeState.canTransition] rule was updated to allow
     * `CLOSED → RUNNING` and `ERROR → RUNNING` specifically for this
     * path.  The factory must still be registered in [spawnEntries]
     * (i.e. the session was originally spawned in this JVM); cross-
     * process resume is not supported.
     *
     * The [userMessage] is NOT passed as the task prompt — instead,
     * it is delivered via the mailbox (`kind="user-hint"`) so
     * [applyMailboxDrain] picks it up in `beforeIteration` and
     * injects it into the LLM context at the right position.  The
     * task prompt is a short resume marker that tells the agent
     * "you were restarted because the user sent a hint; check your
     * mailbox".
     *
     * @return the new coroutine [Job], or `null` if the session
     *   cannot be resumed (not registered, or not in a terminal
     *   state).
     */
    fun resumeForUserInjection(
        sessionId: String,
    ): Job? {
        val entry = spawnEntries[sessionId]
            ?: run {
                logger.warn(
                    "AgentRuntime.resumeForUserInjection: $sessionId is not registered in this runtime " +
                        "(cross-process resume is not supported)"
                )
                return null
            }
        val handle = entry.handle
        val currentState = handle.state.value
        require(currentState == RuntimeState.CLOSED || currentState == RuntimeState.ERROR) {
            "AgentRuntime.resumeForUserInjection: $sessionId is not in a terminal state " +
                "(current=$currentState; expected CLOSED or ERROR)"
        }

        // Transition terminal → RUNNING directly.  The canTransition
        // rule allows this specifically for user-injection resume.
        // Both CLOSED and ERROR skip the MSGHANDLE intermediate
        // state because there is no "mailbox handling" phase — the
        // agent is being fully restarted.
        transition(
            handle = handle,
            next = RuntimeState.RUNNING,
            reason = "user_injection_resume",
            requesterSessionId = null,
        )

        val resumePrompt = "[[AKIBA_INTERNAL:USER_INJECTION_RESUME:${java.util.UUID.randomUUID()}]]"

        val newJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            runChildJob(sessionId, resumePrompt, entry.factory, coldStart = false)
        }
        handle.swapJob(newJob)
        scheduler.register(handle) { admitted ->
            admitted.coroutineJob.start()
        }
        logger.info(
            "AgentRuntime.resumeForUserInjection: $sessionId resumed from $currentState " +
                "on a fresh Job (user-injection)"
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
        //
        // CRITICAL ORDERING: ancestor notifications MUST run BEFORE
        // we write the terminal `status` to the DB.  The daemon's
        // SendMessage route rejects messages from senders whose
        // `status` is terminal ('error' or 'completed').  If we flip
        // the status first, the mailbox notification silently fails
        // with 403 Forbidden and the parent agent is never woken.
        // (CLOSED is not in the daemon's terminal check, but we send
        // before the flip for consistency and forward-safety.)
        if (next == RuntimeState.ERROR) {
            notifyAncestorsOfChildTerminal(handle, reason, isError = true)
        }
        if (next == RuntimeState.CLOSED) {
            notifyAncestorsOfChildTerminal(handle, reason, isError = false)
        }
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
     * Default permanent wake for child terminal transitions: whenever
     * a child transitions to ERROR or CLOSED, notify its direct parent
     * and the root (if different) via mailbox.  This is intentionally
     * NOT an explicit [WakeCondition]: terminal notifications are a
     * safety mechanism and must be always-on so a parked orchestration
     * tree cannot silently deadlock with all agents in STANDBY while
     * one descendant finished or failed.
     *
     * The notification message includes:
     *  - Basic metadata (session id, template id, reason).
     *  - The child's last assistant message (truncated), so the parent
     *    can preview the result without an extra DB round-trip.
     *  - Guidance on how to query the child's full history.
     *
     * @param isError true for ERROR transitions, false for CLOSED.
     */
    private fun notifyAncestorsOfChildTerminal(
        handle: JobHandle,
        reason: String?,
        isError: Boolean,
    ) {
        // Only notify the DIRECT parent, not the root session.
        //
        // Previously, both parentSessionId and rootSessionId were
        // notified.  For grandchildren (e.g. linear_checker whose
        // parent is batch_linear_planner and whose root is the
        // VulnDetector root), this sent a "child complete" notification
        // to the root for every grandchild — the root cannot act on
        // these (it doesn't know about grandchildren) and the messages
        // accumulated in its mailbox, causing the root to get stuck
        // trying to ack/close them.
        //
        // The root will be woken when the direct child (BLP) itself
        // closes, via the state_changed wake condition it registered.
        val recipients = linkedSetOf<String>()
        if (handle.parentSessionId.isNotBlank() && handle.parentSessionId != handle.sessionId) {
            recipients.add(handle.parentSessionId)
        }
        if (recipients.isEmpty()) return

        val detail = reason?.takeIf { it.isNotBlank() }
            ?: if (isError) "child transitioned to error" else "child completed normally"

        // Best-effort fetch of the child's last assistant message.
        val lastMessage = try {
            fetchLastAssistantMessage(handle.sessionId)
        } catch (_: Exception) { null }

        val body = buildString {
            if (isError) {
                appendLine("[child error] Agent ${handle.sessionId} transitioned to ERROR.")
                appendLine("rootSessionId: ${handle.rootSessionId}")
            } else {
                appendLine("[child complete] Agent ${handle.sessionId} finished (CLOSED).")
            }
            appendLine("templateId: ${handle.templateId ?: "<adhoc>"}")
            appendLine("parentSessionId: ${handle.parentSessionId}")
            appendLine("reason: $detail")
            appendLine()
            if (lastMessage != null) {
                appendLine("Last assistant message (truncated to 800 chars):")
                appendLine(lastMessage.take(800))
                if (lastMessage.length > 800) appendLine("…")
            } else {
                appendLine("(Could not retrieve the child's last message.)")
            }
            appendLine()
            appendLine("To inspect the child's full conversation history, use:")
            appendLine("  query_session_history sessionId=${handle.sessionId} limit=20")
            appendLine("  read_history_tool_call sessionId=${handle.sessionId} <tool_call_id>")
            appendLine()
            if (isError) {
                appendLine("This is a default safety wake. Review the failed child's session")
                appendLine("and decide whether to retry, cancel the subtree, or continue with")
                appendLine("degraded results.")
            } else {
                appendLine("This is a default safety wake. The child has finished its work.")
                appendLine("Review its output and decide whether to dispatch more work or")
                appendLine("proceed with aggregation.")
            }
        }

        val kind = if (isError) "error" else "note"
        val subject = if (isError)
            "child agent error: ${handle.sessionId.take(8)}"
        else
            "child agent complete: ${handle.sessionId.take(8)}"
        val priority = if (isError) 10 else 5

        for (recipient in recipients) {
            try {
                // Send from "system" (not from the child's sessionId)
                // because the child may already be terminal in the DB
                // (the strategy's updateSessionStatus runs before
                // transition()).  The daemon rejects messages from
                // terminal senders with 403 Forbidden; using "system"
                // bypasses that check so the notification always
                // reaches the parent.
                val msgId = agentDbClient.sendMailboxMessage(
                    senderSessionId = "system",
                    recipientSessionId = recipient,
                    kind = kind,
                    subject = subject,
                    body = body,
                    priority = priority,
                )
                // Register the conversation so close_conversation
                // works for system-sent messages.  Without this, the
                // ConversationRegistry has no participants for the
                // conversation, and close_conversation fails with
                // "caller is not a participant".
                //
                // Use the well-known system UUID so the participant entry
                // is a valid session id — the string "system" would be
                // rejected by the daemon if close_conversation tried to
                // send a closure notification to it.
                ConversationRegistry.register(
                    messageId = msgId,
                    senderSessionId = SYSTEM_SESSION_UUID,
                    recipientSessionId = recipient,
                    inReplyTo = null,
                )
                logger.info(
                    "Default child-${if (isError) "error" else "closed"} wake sent: " +
                        "child=${handle.sessionId} recipient=$recipient msgId=$msgId reason=$detail"
                )
            } catch (e: Exception) {
                logger.warn(
                    "Failed to notify $recipient about child " +
                        "${if (isError) "error" else "closed"} ${handle.sessionId}: ${e.message}"
                )
            }
        }
    }

    /**
     * Best-effort fetch of the last assistant message for [sessionId].
     * Returns the content truncated to a reasonable length, or null
     * if no assistant message was found or the query failed.
     *
     * Used by [notifyAncestorsOfChildTerminal] to include a preview
     * of the child's final output in the wake notification.
     */
    private fun fetchLastAssistantMessage(sessionId: String): String? {
        val messages = agentDbClient.getMessages(sessionId, fromIndex = 0, limit = 500)
        return messages.lastOrNull { it.role == "assistant" && !it.content.isNullOrBlank() }?.content
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
         *
         * The prefix / suffix constants live in [AgentConstants]
         * (shared with [AkibaAgent] which detects the marker).
         */

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

        /**
         * Acquire the per-binary [AgentRuntime] singleton.  The
         * binary id is used purely as the per-binary key for the
         * registry map; the runtime itself does not store it
         * (the [JobScheduler] and child-handle maps it owns are
         * the actual per-binary state, and they key off session
         * ids and parent ids, not the binary id).
         */
        fun forBinary(
            binaryId: Int,
            agentDbClient: AgentDatabaseClient,
            config: SchedulerConfig = SchedulerConfig(),
        ): AgentRuntime = runtimes.computeIfAbsent(binaryId) {
            AgentRuntime(agentDbClient, config)
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
