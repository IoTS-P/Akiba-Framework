package org.iotsplab.akiba.llm.agent.runtime

import java.util.concurrent.atomic.AtomicBoolean
import org.apache.logging.log4j.LogManager
import org.iotsplab.akiba.data.database.AgentDatabaseClient

// ============================================================
//  AgentSessionReconciler — clean up stale session state on startup
// ============================================================
//
//  [AgentModule]'s terminationHook normally drives `runtime_state` /
//  `status` to "closed", but it only runs on a graceful JVM exit — after
//  SIGKILL, an OOM kill, a container restart or a power failure the rows
//  stay on running / cancelling / standby / msghandle forever, freezing
//  the frontend status pill and keeping [AgentMailboxDispatcher] waking a
//  dead child.  This startup-time safety net closes only provably
//  orphaned rows: active states whose `updated_at` has been frozen longer
//  than the staleness window (live processes bump it on every appended
//  message), never `standby`, because a parked live session is
//  indistinguishable from a dead one without process ownership.  It runs
//  at server startup, from a JVM shutdown hook and at module startup, is
//  idempotent, never throws (DB errors log at WARN), and writes `closed`
//  directly instead of consulting [RuntimeState.canTransition] — the rows
//  belong to a previous process — with `closing_reason` recording why, so
//  an unclean exit is not misreported as `error`.

/**
 * One-shot startup-time reconciler that marks provably orphaned
 * non-terminal session rows as closed.
 *
 * [reasonTag] is embedded in `closing_reason` so an operator can tell
 * whether a row was caught on the way up ("startup", "module_startup")
 * or on the way down ("shutdown_hook"). Not a singleton — every call
 * site constructs its own instance; [inProcessGuard] only stops the same
 * JVM paying the cost twice, it does not block other processes.
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
     * Minimum idle age (minutes since `updated_at`) before an ACTIVE
     * non-terminal session is considered orphaned.
     *
     * A live session's `updated_at` is bumped on every appended message,
     * so only rows frozen longer than this can belong to a dead process
     * (SIGKILL / OOM / container restart) — that is what keeps the
     * reconciler safe for multi-process deployments where several
     * workflows run in parallel against the same daemon.  Defaults to
     * [DEFAULT_STALE_MINUTES]; override via the
     * `AKIBA_AGENT_RECONCILE_STALE_MINUTES` env var, which must exceed
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
