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

// ============================================================
//  AgentMailboxDispatcher — wakes STANDBY agents on new mail
// ============================================================
//
// Per-binary background loop. Every [pollIntervalMs]:
//   1. Find sessions where lifecycle=standby and
//      runtime_state=standby (parked after primary task).
//   2. For each one with unread mailbox messages, ASK the
//      runtime to resume the session via
//      [AgentRuntime.resumeStandby].  The runtime transitions
//      state through the hook chain (STANDBY → MSGHANDLE →
//      RUNNING) and starts a fresh `agent.run()` on a new
//      coroutine Job; the harness's `beforeIteration` drains
//      the mailbox as user messages, so the LLM sees the
//      unread mail as the input to its next reasoning round.
//
// Cross-process sessions: a session spawned in another JVM
// shows up as `standby + unread` here but the local runtime
// does not have the factory closure.  We detect that via
// [AgentRuntime.canResume] and skip — the owning process is
// the only one that can actually resume it.

data class MailboxDispatcherConfig(
    val enabled: Boolean = true,
    val pollIntervalMs: Long = 5_000L,
    val maxWakesPerTick: Int = 16,
)

class AgentMailboxDispatcher(
    val binaryId: Int,
    val agentDbClient: AgentDatabaseClient,
    val runtime: AgentRuntime,
    val config: MailboxDispatcherConfig = MailboxDispatcherConfig(),
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val logger = LogManager.getLogger(AgentMailboxDispatcher::class.java)
    @Volatile private var runner: Job? = null

    fun start() {
        if (runner != null) return
        runner = scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (e: Exception) {
                    logger.warn("MailboxDispatcher tick threw: ${e.message}", e)
                }
                delay(config.pollIntervalMs)
            }
        }
        logger.info("AgentMailboxDispatcher started for binary=$binaryId (poll=${config.pollIntervalMs}ms)")
    }

    fun stop() {
        runner?.cancel()
        runner = null
    }

    /**
     * One tick: find standby sessions with unread > 0 and ask
     * the runtime to resume them.  Idempotent: a session that
     * has already been resumed (state moved past MSGHANDLE) is
     * skipped on subsequent ticks.
     *
     * Also evaluates registered [WakeCondition]s for all standby
     * sessions: if a condition is satisfied, a synthetic "wake"
     * message is sent to the agent's mailbox so it will be
     * picked up by the next tick's `findStandbyWithUnread`.
     */
    private suspend fun tick() {
        if (!config.enabled) return

        // 1. Evaluate wake conditions for all sessions that have
        //    registered conditions.  This may deliver synthetic
        //    messages that make a standby session "have unread"
        //    even if no real message arrived.
        evaluateWakeConditions()

        // 2. Resume standby sessions with unread messages.
        val targets = findStandbyWithUnread()
        if (targets.isEmpty()) return
        val take = targets.take(config.maxWakesPerTick)
        for (sessionId in take) {
            try {
                val current = agentDbClient.getRuntimeState(sessionId) ?: continue
                if (current.runtimeState == RuntimeState.MSGHANDLE.wire()) continue
                if (current.runtimeState != RuntimeState.STANDBY.wire()) continue
                if (current.lifecycle != Lifecycle.STANDBY.name.lowercase()) continue

                // Cross-process guard: only resume sessions
                // whose factory closure lives in this JVM.
                if (!runtime.canResume(sessionId)) {
                    logger.debug(
                        "MailboxDispatcher: $sessionId is standby+unread in DB " +
                            "but its factory lives in another process; skipping resume"
                    )
                    continue
                }

                runtime.resumeStandby(sessionId = sessionId)
                logger.info(
                    "MailboxDispatcher: standby session $sessionId has unread " +
                        "messages; resumed via runtime.resumeStandby"
                )
            } catch (e: Exception) {
                logger.warn("MailboxDispatcher tick for $sessionId failed: ${e.message}")
            }
        }
    }

    /**
     * Evaluate [WakeCondition]s for all sessions that have
     * registered conditions.  For each satisfied condition, send
     * a synthetic mailbox message to the target session so it
     * will be picked up by [findStandbyWithUnread].
     *
     * The evaluation reads the current state of all sessions
     * (for [StateChanged] conditions) and the unread message
     * counts (for [MessageArrived] conditions) in a single pass.
     * [TimeElapsed] conditions are evaluated against the current
     * timestamp.
     */
    private suspend fun evaluateWakeConditions() {
        // Get all sessions for this binary to build the state map.
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
            return
        }

        // Build sessionId → runtime_state wire value map
        val sessionStates = mutableMapOf<String, String>()
        for (s in sessions) {
            try {
                val st = agentDbClient.getRuntimeState(s.sessionId)
                if (st != null) sessionStates[s.sessionId] = st.runtimeState
            } catch (_: Exception) {}
        }

        val now = System.currentTimeMillis()

        // For each session that has registered wake conditions,
        // build a WakeEvalContext and evaluate.
        for (s in sessions) {
            val conditions = WakeConditionRegistry.list(s.sessionId)
            if (conditions.isEmpty()) continue

            // Check if the session is still standby (conditions
            // are only meaningful for parked agents)
            if (sessionStates[s.sessionId] != RuntimeState.STANDBY.wire()) {
                // Agent is not standby; clear its conditions (it
                // already woke up via another path).
                WakeConditionRegistry.clearAll(s.sessionId)
                continue
            }

            val unreadCount = try {
                agentDbClient.countUnreadMailbox(s.sessionId)
            } catch (_: Exception) { 0 }

            val unreadMessages = if (unreadCount > 0) {
                try {
                    // IMPORTANT: do NOT drain here. Wake-condition
                    // evaluation must be read-only.  Draining in the
                    // dispatcher would mark the message as read before
                    // findStandbyWithUnread() runs, preventing the
                    // default permanent message wake from resuming the
                    // agent.  The real unread→seen transition belongs
                    // to applyMailboxDrain() inside the agent run.
                    agentDbClient.listMailboxMessages(
                        sessionId = s.sessionId,
                        limit = 50,
                        includeRead = false,
                    )
                } catch (_: Exception) { emptyList() }
            } else emptyList()

            val evalCtx = WakeEvalContext(
                agentSessionId = s.sessionId,
                unreadCount = unreadCount,
                unreadMessages = unreadMessages,
                sessionStates = sessionStates,
                registeredAt = conditions.first().registeredAt,
                now = now,
            )

            val satisfied = WakeConditionRegistry.peek(s.sessionId, evalCtx)
            if (satisfied.isNotEmpty()) {
                // Send a synthetic wake message for each satisfied
                // condition.  Only remove the condition from the
                // registry AFTER the message is successfully
                // inserted — if the send fails (e.g. transient DB
                // error) the condition stays registered and will be
                // retried on the next tick.
                val sent = mutableListOf<WakeConditionRegistry.Entry>()
                for (entry in satisfied) {
                    try {
                        val body = buildString {
                            appendLine("[wake condition satisfied] ${entry.condition.description}")
                            if (entry.label != null) {
                                appendLine("(label: ${entry.label})")
                            }
                            appendLine("You registered this condition and it has been met.")
                            appendLine("Process the triggering event and produce a response or")
                            appendLine("register a new condition if you need to wait again.")
                        }
                        agentDbClient.sendMailboxMessage(
                            senderSessionId = "system",
                            recipientSessionId = s.sessionId,
                            kind = "note",
                            subject = "wake condition: ${entry.condition.description}",
                            body = body,
                        )
                        sent.add(entry)
                        logger.info(
                            "WakeCondition fired for ${s.sessionId.take(8)}: " +
                                "${entry.condition.description}"
                        )
                    } catch (e: Exception) {
                        logger.warn(
                            "WakeCondition: failed to send wake message to " +
                                "${s.sessionId.take(8)}: ${e.message}"
                        )
                    }
                }
                if (sent.isNotEmpty()) {
                    WakeConditionRegistry.removeEntries(s.sessionId, sent)
                }
            }
        }
    }

    private fun findStandbyWithUnread(): List<String> {
        val out = mutableListOf<String>()
        val sessions = try {
            agentDbClient.listSessions(
                status = null,
                binaryId = binaryId,
                moduleName = null,
                limit = 200,
                offset = 0,
                parentSessionId = "ALL",
            )
        } catch (e: Exception) {
            return emptyList()
        }
        for (s in sessions) {
            val state = try { agentDbClient.getRuntimeState(s.sessionId) } catch (e: Exception) { null }
                ?: continue
            if (state.runtimeState != RuntimeState.STANDBY.wire()) continue
            if (state.lifecycle != Lifecycle.STANDBY.name.lowercase()) continue
            val unread = try { agentDbClient.countUnreadMailbox(s.sessionId) } catch (e: Exception) { 0 }
            if (unread > 0) out.add(s.sessionId)
        }
        return out
    }
}
