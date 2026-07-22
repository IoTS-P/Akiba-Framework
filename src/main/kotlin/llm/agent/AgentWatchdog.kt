package org.iotsplab.akiba.llm.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.apache.logging.log4j.LogManager
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Safety net for sessions that are still marked as running but have had no
 * visible DB activity for a long time. This catches LLM calls that did not
 * return or did not surface an exception through the normal runtime path.
 *
 * ## Deadlock detection
 *
 * In addition to stale-agent cancellation, the watchdog detects a
 * **system-wide deadlock**: all agents for the binary have finished
 * (no one is in running / msghandle / cancelling), yet at least one
 * root agent is still parked in STANDBY waiting for mailbox messages
 * that will never arrive (because all children have terminated and
 * no external user is injecting hints).
 *
 * When this condition is detected (confirmed by a second check after
 * [deadlockConfirmIntervalMs] to avoid race conditions), the watchdog
 * resumes the root agent via [AgentRuntime.resumeStandby] with a
 * prompt that tells it to check whether all work is complete.
 *
 * After [deadlockMaxWakeups] wake-ups the prompt escalates to strongly
 * suggest the agent close itself, preventing an infinite wake loop.
 */
data class AgentWatchdogConfig(
    val enabled: Boolean = true,
    val scanIntervalMs: Long = 60_000L,
    val staleAfterMs: Long = 10 * 60_000L,
    val cancelGraceMs: Long = 15_000L,

    // ---- Deadlock detection ----
    /** Enable / disable the all-idle deadlock detector. */
    val deadlockCheckEnabled: Boolean = true,
    /**
     * Interval between the first "all idle" detection and the
     * confirmation check.  A few seconds is enough to let a
     * just-finished child transition to terminal and a parent
     * pick up the mailbox wake.
     */
    val deadlockConfirmIntervalMs: Long = 5_000L,
    /**
     * After this many watchdog-initiated wake-ups, the prompt
     * escalates to "you have been woken N times, all sub-agents
     * are done — please verify and close if complete".
     */
    val deadlockMaxWakeups: Int = 3,
)

class AgentWatchdog(
    val binaryId: Int,
    val agentDbClient: AgentDatabaseClient,
    val runtime: AgentRuntime,
    val config: AgentWatchdogConfig = AgentWatchdogConfig(),
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val logger = LogManager.getLogger(AgentWatchdog::class.java)
    @Volatile private var runner: Job? = null

    /**
     * Per-root-session wake-up counter for the deadlock detector.
     *
     * Reset to 0 when the root transitions out of STANDBY on its
     * own (i.e. it was woken by a real mailbox message, not by the
     * watchdog).  The counter only increments on watchdog-initiated
     * resumes.
     */
    private val wakeupCount = mutableMapOf<String, Int>()

    fun start() {
        if (runner != null) return
        runner = scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (e: Exception) {
                    logger.warn("AgentWatchdog tick threw: ${e.message}", e)
                }
                delay(config.scanIntervalMs)
            }
        }
        logger.info(
            "AgentWatchdog started for binary=$binaryId " +
                "(scan=${config.scanIntervalMs}ms, stale=${config.staleAfterMs}ms)"
        )
    }

    fun stop() {
        runner?.cancel()
        runner = null
    }

    private suspend fun tick() {
        if (!config.enabled) return
        val now = System.currentTimeMillis()
        val sessions = try {
            agentDbClient.listSessions(
                status = null,
                binaryId = binaryId,
                moduleName = null,
                limit = 500,
                offset = 0,
                parentSessionId = "ALL",
            )
        } catch (e: Exception) {
            logger.warn("AgentWatchdog listSessions failed: ${e.message}")
            return
        }

        // ---- 1. Stale-agent cancellation (existing) ----
        for (session in sessions) {
            val row = try { agentDbClient.getRuntimeState(session.sessionId) } catch (_: Exception) { null }
                ?: continue
            if (row.runtimeState !in WATCH_STATES) continue
            if (runtime.scheduler.get(session.sessionId) == null) continue

            val updatedAtMs = parseTimestampMs(session.updatedAt ?: session.createdAt) ?: continue
            val idleMs = now - updatedAtMs
            if (idleMs < config.staleAfterMs) continue

            logger.warn(
                "AgentWatchdog: cancelling stale agent ${session.sessionId} " +
                    "state=${row.runtimeState}, idleMs=$idleMs"
            )
            runtime.cancel(
                sessionId = session.sessionId,
                callerSessionId = null,
                reason = "watchdog_stale_llm",
                graceMs = config.cancelGraceMs,
            )
        }

        // ---- 2. Deadlock detection (all-idle check) ----
        if (config.deadlockCheckEnabled) {
            checkDeadlock(sessions)
        }
    }

    // ============================================================
    //  Deadlock detection
    // ============================================================

    /**
     * Detect a system-wide deadlock: no agent is actively running,
     * but at least one root agent is parked in STANDBY.
     *
     * To avoid race conditions (e.g. a child just finished and the
     * parent hasn't picked up the mailbox message yet), the check
     * is performed twice with a short delay between checks. Only
     * if BOTH checks confirm "no running agents" do we act.
     */
    private suspend fun checkDeadlock(sessions: List<AgentDatabaseClient.SessionInfo>) {
        // First check: are there any actively running agents?
        if (hasRunningAgents(sessions)) {
            // System is alive — reset wake-up counters for any root
            // agents that are no longer in standby (they woke up on
            // their own via a real mailbox message).
            resetCountersForNonStandbyRoots(sessions)
            return
        }

        // Wait briefly and re-check to avoid race conditions.
        delay(config.deadlockConfirmIntervalMs)

        // Re-fetch sessions — state may have changed during the delay.
        val refreshedSessions = try {
            agentDbClient.listSessions(
                status = null,
                binaryId = binaryId,
                moduleName = null,
                limit = 500,
                offset = 0,
                parentSessionId = "ALL",
            )
        } catch (e: Exception) {
            logger.warn("AgentWatchdog deadlock re-check listSessions failed: ${e.message}")
            return
        }

        // Second check: still no running agents?
        if (hasRunningAgents(refreshedSessions)) {
            resetCountersForNonStandbyRoots(refreshedSessions)
            return
        }

        // Deadlock confirmed: no agents are running.  Find standby
        // root agents (parentSessionId == null) and wake them.
        val standbyRoots = refreshedSessions.filter { s ->
            s.parentSessionId == null &&
                try {
                    val st = agentDbClient.getRuntimeState(s.sessionId)
                    st?.runtimeState == RuntimeState.STANDBY.wire()
                } catch (_: Exception) { false }
        }

        if (standbyRoots.isEmpty()) {
            // No standby roots — system has fully terminated (all
            // closed / error).  Nothing to do.
            return
        }

        for (root in standbyRoots) {
            wakeRoot(root.sessionId, root.sessionName)
        }
    }

    /**
     * Check whether any session in [sessions] is in an actively-running
     * state (running / msghandle / cancelling).
     */
    private fun hasRunningAgents(sessions: List<AgentDatabaseClient.SessionInfo>): Boolean {
        return sessions.any { s ->
            try {
                val st = agentDbClient.getRuntimeState(s.sessionId)
                st != null && st.runtimeState in WATCH_STATES
            } catch (_: Exception) { false }
        }
    }

    /**
     * Reset wake-up counters for root agents that are no longer in
     * STANDBY.  This means they were woken by a real event (mailbox
     * message, user injection, etc.) and the deadlock detector's
     * count should start fresh next time they park.
     */
    private fun resetCountersForNonStandbyRoots(sessions: List<AgentDatabaseClient.SessionInfo>) {
        for (s in sessions) {
            if (s.parentSessionId != null) continue
            try {
                val st = agentDbClient.getRuntimeState(s.sessionId)
                if (st != null && st.runtimeState != RuntimeState.STANDBY.wire()) {
                    wakeupCount.remove(s.sessionId)
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Wake a standby root agent via [AgentRuntime.resumeStandby] with
     * a prompt appropriate to the current wake-up count.
     *
     * - First few wake-ups: gentle "all sub-agents finished, check
     *   your mailbox and decide whether to continue or close".
     * - After [AgentWatchdogConfig.deadlockMaxWakeups]: escalated
     *   "you have been woken N times, all sub-agents are done —
     *   verify and close if complete".
     */
    private fun wakeRoot(sessionId: String, sessionName: String?) {
        // Only wake sessions registered in the local runtime.
        if (!runtime.canResume(sessionId)) {
            logger.debug(
                "AgentWatchdog: root $sessionId ('$sessionName') is standby but " +
                    "not registered in this runtime (cross-process); skipping wake"
            )
            return
        }

        // Verify the session is still standby right before waking
        // (it may have been resumed by the dispatcher between our
        // check and now).
        val current = try { agentDbClient.getRuntimeState(sessionId) } catch (_: Exception) { null }
        if (current?.runtimeState != RuntimeState.STANDBY.wire()) {
            wakeupCount.remove(sessionId)
            return
        }

        val count = (wakeupCount[sessionId] ?: 0) + 1
        wakeupCount[sessionId] = count

        val prompt = if (count > config.deadlockMaxWakeups) {
            buildString {
                appendLine("[WATCHDOG] You have been woken $count times because all of your " +
                    "sub-agents have finished and no new work is arriving.")
                appendLine()
                appendLine("This is likely a deadlock — you keep parking in standby but no one " +
                    "is sending you messages. Please verify:")
                appendLine("  1. Have all sub-agents completed their tasks?")
                appendLine("  2. Have you collected and reviewed all results?")
                appendLine("  3. Is there any remaining work that requires action?")
                appendLine()
                appendLine("If all work is complete, emit a Final Answer with your summary and close. " +
                    "If you need to spawn more sub-agents, do so now — otherwise the watchdog " +
                    "will keep waking you.")
            }
        } else {
            buildString {
                appendLine("[WATCHDOG] All sub-agents have finished (none are in running/msghandle " +
                    "state). No new mailbox messages are pending.")
                appendLine()
                appendLine("Wake-up #$count. Please check whether all work is complete:")
                appendLine("  - Review sub-agent results if you haven't already.")
                appendLine("  - If more work is needed, spawn sub-agents or send messages.")
                appendLine("  - If everything is done, emit a Final Answer with your summary and close.")
            }
        }

        try {
            runtime.resumeStandby(sessionId = sessionId, newTaskPrompt = prompt)
            logger.info(
                "AgentWatchdog: woke standby root $sessionId ('$sessionName') " +
                    "wake-up #$count (max=${config.deadlockMaxWakeups})"
            )
        } catch (e: Exception) {
            logger.warn(
                "AgentWatchdog: failed to wake root $sessionId ('$sessionName'): ${e.message}"
            )
            // If the resume failed, don't count this wake-up.
            wakeupCount[sessionId] = count - 1
        }
    }

    private fun parseTimestampMs(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()
        return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching {
                LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()
            ?: runCatching {
                LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()
    }

    companion object {
        private val WATCH_STATES = setOf(
            RuntimeState.RUNNING.wire(),
            RuntimeState.MSGHANDLE.wire(),
            RuntimeState.CANCELLING.wire(),
        )
    }
}
