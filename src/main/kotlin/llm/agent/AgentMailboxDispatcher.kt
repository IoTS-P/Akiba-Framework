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
     */
    private suspend fun tick() {
        if (!config.enabled) return
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

    private fun findStandbyWithUnread(): List<String> {
        val out = mutableListOf<String>()
        val sessions = try {
            agentDbClient.listSessions(
                status = null,
                binaryId = binaryId,
                moduleName = null,
                limit = 200,
                offset = 0,
                parentSessionId = null,
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
