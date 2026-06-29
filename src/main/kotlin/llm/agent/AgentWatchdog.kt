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
 */
data class AgentWatchdogConfig(
    val enabled: Boolean = true,
    val scanIntervalMs: Long = 60_000L,
    val staleAfterMs: Long = 10 * 60_000L,
    val cancelGraceMs: Long = 15_000L,
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
