package org.iotsplab.akiba.llm.agent

import org.apache.logging.log4j.LogManager
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import java.util.concurrent.atomic.AtomicBoolean

// ============================================================
//  AgentSessionReconciler — clean up stale session state on startup
// ============================================================
//
//  ## Why this exists
//
//  An agent session's `runtime_state` and `status` columns are
//  normally driven to "closed" by [AgentModule]'s
//  [AkibaAgent.runWithTermination] `terminationHook` — see the
//  long comment on [AkibaAgent.terminationHook] for the full
//  two-step ("cancelling" → "closed") flow.  That hook is the
//  single chokepoint every agent — root or child — runs on a
//  truly-terminating run.
//
//  But the hook only runs when the JVM exits gracefully.  When
//  the JVM is taken down by SIGKILL, an OOM kill, a container
//  restart, a power failure, or `kill -9`, there is no time to
//  run the hook.  The DB rows for the in-flight sessions stay
//  on `runtime_state='running' / 'cancelling' / 'standby' /
//  'msghandle'` forever — the frontend's status pill is frozen
//  on "Running" / "Cancelling", the
//  [AgentMailboxDispatcher] keeps trying to wake the (dead)
//  child, and the [AgentWatchdog]'s 10-minute stale threshold
//  is too long for an interactive user.
//
//  This reconciler is the startup-time safety net.  It scans
//  the DB for non-terminal session rows and closes ONLY those
//  that are provably orphaned: active-state rows (`running` /
//  `msghandle` / `cancelling`) whose `updated_at` has been frozen
//  longer than the staleness window (live processes bump
//  `updated_at` on every appended message).  `standby` rows are
//  never touched — a parked live session is indistinguishable
//  from a dead one without process ownership.  It runs on:
//
//   1. **Server startup** (`AkibaServer.start`).  Covers all
//      ungraceful exits from a previous process.
//   2. **JVM shutdown hook** (also installed from
//      `AkibaServer.start`).  Covers SIGTERM from
//      `entrypoint.sh` so a normally-stopped container still
//      leaves clean rows in the DB.  (Without the hook, a
//      normally-killed server would also leave stale rows
//      because Ktor's Netty engine doesn't run user-supplied
//      cleanup on SIGTERM by default — it just stops accepting
//      connections and exits.)
//   3. **Module startup** ([AgentModule.startProcess]).  Covers
//      CLI-launched workflows that don't go through the HTTP
//      server.
//
//  ## Idempotency
//
//  The reconciler is safe to call multiple times.  Sessions
//  that are already terminal are skipped.  A second call right
//  after a successful first call is a cheap no-op.
//
//  ## Failure modes
//
//  The reconciler never throws.  DB errors are caught and
//  logged at WARN; the worst case is that one row stays stale
//  until the next startup (or the [AgentWatchdog] picks it up).
//
//  ## Concurrency
//
//  The reconciler is called from startup, before any
//  in-process [AgentRuntime] exists.  It writes directly to
//  the DB; it does not consult [RuntimeState.canTransition]
//  because the rows in question are owned by a *previous*
//  process and the transition matrix is enforced for the
//  current process only.  The daemon's
//  `/agent/session/set_runtime_state` route accepts the
//  `closed` value unconditionally; if the daemon refuses the
//  write (e.g. it has its own stricter gate that we are
//  unaware of), the row is logged and skipped.
//
//  ## Why `closed` and not `error`
//
//  "closed" is the natural terminal state of any session that
//  was alive when its hosting process disappeared.  The
//  difference between a clean exit and an unclean one is
//  captured in the `closing_reason` column (which the
//  frontend's error banner can surface verbatim).  Marking
//  every unclean exit as "error" would over-report failures:
//  a graceful SIGTERM that the JVM caught but our hook did
//  not see would be misclassified as a crash.
//

/**
 * One-shot startup-time reconciler that marks any
 * non-terminal session row as closed.
 *
 * Construct with the [AgentDatabaseClient] and a human-readable
 * [reasonTag] that explains *why* the reconciler was invoked
 * (e.g. "startup", "shutdown_hook", "module_startup").  The
 * tag is embedded in the `closing_reason` column so an
 * operator looking at a stale session later can tell whether
 * it was caught on the way up or on the way down.
 *
 * The reconciler is **not** a singleton — every call site
 * constructs its own instance.  Internally it uses
 * [inProcessGuard] to ensure the same JVM does not pay the
 * reconcile cost twice in close succession (e.g. when the
 * shutdown hook and the startup of a subsequent module both
 * fire).  The guard is per-process; it does **not** block a
 * different process from running its own startup pass.
 */
class AgentSessionReconciler(
    private val agentDbClient: AgentDatabaseClient,
    /**
     * Short tag merged into the `closing_reason` column so an
     * operator can tell the trigger that flipped the row.
     * Convention: lowercase, no spaces, e.g. "startup",
     * "shutdown_hook", "module_startup".
     */
    private val reasonTag: String = "startup",
    /**
     * Maximum number of session rows to scan per call.
     * Defaults to 1000 — generous enough for any realistic
     * deployment (a single Akiba instance rarely runs more
     * than a few hundred live sessions) but bounded so the
     * SQL does not return the whole table if the daemon has
     * millions of historical rows.
     */
    private val maxRowsPerScan: Int = 1000,
    /**
     * Minimum idle age (minutes since `updated_at`) before an
     * ACTIVE non-terminal session is considered orphaned.
     *
     * Rationale: a live session's `updated_at` is bumped on every
     * appended message (see the daemon's append-message route), so a
     * session owned by a LIVE process is never older than its last
     * tool/LLM step.  Only rows frozen longer than [staleMinutes]
     * can belong to a dead process (SIGKILL / OOM / container
     * restart).  This makes the reconciler safe for multi-process
     * deployments where several workflows run in PARALLEL against
     * the same daemon: a newly started process no longer flips the
     * other processes' live sessions to `closed`.
     *
     * Default: [DEFAULT_STALE_MINUTES]; override via the
     * `AKIBA_AGENT_RECONCILE_STALE_MINUTES` env var.  Must exceed
     * the longest expected single tool call (e.g. angr runs).
     */
    private val staleMinutes: Long = defaultStaleMinutes(),
) {
    private val logger = LogManager.getLogger(AgentSessionReconciler::class.java)

    /**
     * In-process idempotency guard.  A second call within
     * the same JVM is a no-op.  Reset only by JVM exit; this
     * is intentional — a single startup pass is enough.
     */
    private val inProcessGuard = AtomicBoolean(false)

    /**
     * One-line per-row decision: a session is "live" when
     * its `runtime_state` is one of `running / standby /
     * msghandle / cancelling`.  Anything else (closed / error
     * / unknown / null) is already terminal and is left
     * alone.
     *
     * Note that the legacy `status` column carries the same
     * vocabulary but a slightly broader legacy set
     * (`completed` / `cancelled` / `failed` / `active` /
     * `suspended`).  We deliberately only filter on the
     * canonical `runtime_state` here — the [AgentRuntime] is
     * responsible for keeping the two columns in sync for
     * every transition it makes, so a session with a legacy
     * `status` value but a non-terminal `runtime_state` is
     * the same "live" case.
     */
    /**
     * States eligible for reconciliation: ACTIVE non-terminal states
     * only.  `standby` is deliberately EXCLUDED — a parked standby
     * session of a live process (agent parked on user input or on
     * `await_condition`) receives no `updated_at` bumps while parked,
     * so it is indistinguishable from a dead process's leftover row
     * without a process-ownership column.  Closing a live parked
     * session breaks resumption; leaving a dead parked session only
     * costs a stale "Standby" pill until it is cleaned up manually.
     */
    private val reconcilableStates: Set<String> = setOf(
        RuntimeState.RUNNING.wire(),
        RuntimeState.MSGHANDLE.wire(),
        RuntimeState.CANCELLING.wire(),
    )

    /**
     * Run the reconciler.  Returns a [ReconcileReport]
     * summarising what was done, suitable for logging.
     *
     * Safe to call from any thread; the DB I/O is performed
     * by [AgentDatabaseClient] which is already thread-safe
     * (`runBlocking` inside its methods).  Typical call sites
     * are the main thread of `AkibaServer.start` (a few
     * dozen-millisecond blocking call) and the JVM shutdown
     * hook (a few seconds; we tolerate a longer wait here
     * because the JVM is going down anyway).
     *
     * Idempotent within a single process — the second call
     * returns an empty report and does no I/O.
     */
    fun reconcile(): ReconcileReport {
        if (!inProcessGuard.compareAndSet(false, true)) {
            logger.debug(
                "AgentSessionReconciler[$reasonTag]: already ran in this JVM, " +
                    "skipping duplicate pass"
            )
            return ReconcileReport(0, 0, 0, deduped = true)
        }

        val sessions: List<AgentDatabaseClient.SessionInfo> = try {
            agentDbClient.listSessions(
                status = null,
                binaryId = null,
                moduleName = null,
                limit = maxRowsPerScan,
                offset = 0,
                // "ALL" → include sub-agents.  An orphaned
                // child is just as stale as an orphaned root;
                // we must catch both.
                parentSessionId = "ALL",
            )
        } catch (e: Exception) {
            // The DB might be down (very-early startup) or
            // temporarily unreachable.  Log and move on; the
            // next reconciler entry point (or the
            // [AgentWatchdog]) will catch the stale rows.
            logger.warn(
                "AgentSessionReconciler[$reasonTag]: listSessions failed, " +
                    "skipping reconciliation: ${e.message}"
            )
            // Release the guard so a later retry (e.g. the
            // module-level reconcile) gets a chance.
            inProcessGuard.set(false)
            return ReconcileReport(0, 0, 0, deduped = false, dbError = e.message)
        }

        var scanned = 0
        var reconciled = 0
        var failed = 0

        val nowMs = System.currentTimeMillis()
        var skippedFresh = 0

        for (s in sessions) {
            scanned++
            val current = s.runtimeState?.lowercase()
            if (current == null || current !in reconcilableStates) continue
            // Skip the empty-string case too — that's how the
            // daemon encodes "no value" for legacy rows.
            if (current.isBlank()) continue

            // Ownership proxy: only rows frozen for longer than the
            // staleness window can belong to a dead process.  Rows
            // without a parseable timestamp are SKIPPED — never close
            // what we cannot age-verify.
            val updatedMs = parseDbTimestamp(s.updatedAt)
            if (updatedMs == null || nowMs - updatedMs < staleMinutes * 60_000L) {
                skippedFresh++
                continue
            }

            val reason = buildClosingReason(
                tag = reasonTag,
                previousState = current,
                sessionName = s.sessionName,
                binaryId = s.binaryId,
            )
            try {
                agentDbClient.setRuntimeState(
                    sessionId = s.sessionId,
                    runtimeState = RuntimeState.CLOSED.wire(),
                    closingReason = reason,
                )
                try {
                    agentDbClient.updateSession(
                        sessionId = s.sessionId,
                        status = RuntimeState.CLOSED.wire(),
                    )
                } catch (e: Exception) {
                    // The two writes are best-effort: the
                    // canonical `runtime_state` flip is the
                    // one that drives the frontend's status
                    // pill.  If the legacy `status` write
                    // fails, the row is still considered
                    // reconciled (the next poll will pick up
                    // the new value from runtime_state and
                    // surface it correctly).
                    logger.debug(
                        "AgentSessionReconciler[$reasonTag]: status mirror write for " +
                            "${s.sessionId} failed (non-fatal): ${e.message}"
                    )
                }
                reconciled++
                logger.info(
                    "AgentSessionReconciler[$reasonTag]: closed stale session " +
                        "${s.sessionId} (was=$current, name=${s.sessionName ?: "<unnamed>"}, " +
                        "binary=${s.binaryId ?: "<none>"})"
                )
            } catch (e: Exception) {
                failed++
                logger.warn(
                    "AgentSessionReconciler[$reasonTag]: failed to close stale " +
                        "session ${s.sessionId} (was=$current): ${e.message}"
                )
            }
        }

        val report = ReconcileReport(
            scanned = scanned,
            reconciled = reconciled,
            failed = failed,
            deduped = false,
        )
        if (reconciled > 0 || failed > 0) {
            logger.info(
                "AgentSessionReconciler[$reasonTag]: done — scanned=$scanned, " +
                    "reconciled=$reconciled, failed=$failed, " +
                    "skippedFresh=$skippedFresh (live or unverifiable, left alone)"
            )
        } else {
            logger.info(
                "AgentSessionReconciler[$reasonTag]: done — scanned=$scanned, " +
                    "no stale sessions found (skippedFresh=$skippedFresh)"
            )
        }
        return report
    }

    /**
     * Parse the daemon's `timestamptz` text form (e.g.
     * `2026-08-14 19:00:00.123456+08`) to epoch milliseconds.
     * Returns `null` on any parse failure — callers must treat an
     * unparseable timestamp as "not stale" (never close what we
     * cannot age-verify).
     */
    private fun parseDbTimestamp(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        val t = s.trim()
        return runCatching {
            java.time.OffsetDateTime.parse(t.replace(' ', 'T'), DB_TS_FORMAT)
                .toInstant().toEpochMilli()
        }.getOrElse {
            // Fallback: no offset present — take the wall-clock part as UTC.
            runCatching {
                val m = Regex("""^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2}:\d{2})""").find(t)
                    ?: return null
                java.time.LocalDateTime.parse("${m.groupValues[1]}T${m.groupValues[2]}")
                    .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
            }.getOrNull()
        }
    }

    /**
     * Build the `closing_reason` text written to the DB.
     * Format: `reconciled:<tag>:<previousState>:<epochMs>`.
     * The `reconciled:` prefix lets future tools
     * distinguish reconciler-written reasons from
     * orchestrator-written ones (e.g. `cancelled`,
     * `watchdog_stale_llm`) without consulting a separate
     * audit table.  The `previousState` makes the original
     * non-terminal state visible in logs without needing to
     * join against a state-transition log.
     *
     * Bound to 500 chars to match the `closing_reason` column
     * conventions used elsewhere in the framework.
     */
    private fun buildClosingReason(
        tag: String,
        previousState: String,
        sessionName: String?,
        binaryId: Int?,
    ): String {
        val head = "reconciled:$tag:from=$previousState:at=${System.currentTimeMillis()}"
        val tail = buildString {
            if (binaryId != null) append(":bin=$binaryId")
            if (!sessionName.isNullOrBlank()) {
                append(":name=")
                append(sessionName.take(80))
            }
        }
        return (head + tail).take(500)
    }

    /**
     * Outcome of a [reconcile] call.  Counts are zero in the
     * `deduped` case.  [dbError] is non-null when the
     * session list itself could not be fetched; in that
     * case [scanned] / [reconciled] / [failed] are all 0.
     */
    data class ReconcileReport(
        val scanned: Int,
        val reconciled: Int,
        val failed: Int,
        val deduped: Boolean,
        val dbError: String? = null,
    )

    companion object {
        /**
         * Default staleness window (minutes).  Must exceed the longest
         * expected single tool call (angr explorations can run for tens
         * of minutes without appending a session message).
         */
        const val DEFAULT_STALE_MINUTES: Long = 90L

        /** PostgreSQL `timestamptz` text format with optional fraction and offset. */
        private val DB_TS_FORMAT = java.time.format.DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
            .optionalStart()
            .appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND, 0, 9, true)
            .optionalEnd()
            .optionalStart()
            .appendOffset("+HH:MM", "")
            .optionalEnd()
            .toFormatter()

        /** Read the staleness window from `AKIBA_AGENT_RECONCILE_STALE_MINUTES`. */
        private fun defaultStaleMinutes(): Long =
            System.getenv("AKIBA_AGENT_RECONCILE_STALE_MINUTES")
                ?.trim()?.toLongOrNull()?.takeIf { it > 0 }
                ?: DEFAULT_STALE_MINUTES
    }
}
