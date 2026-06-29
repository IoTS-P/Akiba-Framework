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
import java.util.concurrent.ConcurrentHashMap

// ============================================================
//  OrphanReaper — picks up children whose parent died
// ============================================================
//
// Background loop. Every [scanIntervalMs]:
//   1. For every binary, find sessions whose parent is already
//      closed (or whose parent_session_id points at a non-existent
//      session) and that are not themselves closed.
//   2. For each orphan, trigger AgentRuntime.cancelSubtree
//      with reason='orphan_reaped' and a short grace period.
//
// The reaper is per-binary; one process can run multiple
// reapers if it serves multiple binaries. The DB walk is a single
// recursive CTE per binary per tick.

data class OrphanReaperConfig(
    val enabled: Boolean = true,
    /** How often the reaper scans (ms). Default 60s. */
    val scanIntervalMs: Long = 60_000L,
    /** Grace period given to each orphan before hard cancel (ms). */
    val graceMs: Long = 15_000L,
)

class OrphanReaper(
    val binaryId: Int,
    val agentDbClient: AgentDatabaseClient,
    val runtime: AgentRuntime,
    val config: OrphanReaperConfig = OrphanReaperConfig(),
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val logger = LogManager.getLogger(OrphanReaper::class.java)
    @Volatile private var runner: Job? = null
    private val inFlight = ConcurrentHashMap<String, Long>()  // sessionId -> deadline

    fun start() {
        if (runner != null) return
        runner = scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (e: Exception) {
                    logger.warn("OrphanReaper tick threw: ${e.message}", e)
                }
                delay(config.scanIntervalMs)
            }
        }
        logger.info("OrphanReaper started for binary=$binaryId (scan=${config.scanIntervalMs}ms)")
    }

    fun stop() {
        runner?.cancel()
        runner = null
    }

    /**
     * One scan: list non-closed sessions, filter to those whose
     * parent is closed (or missing), and cancel them with grace.
     */
    private suspend fun tick() {
        if (!config.enabled) return
        val candidates = findOrphans()
        if (candidates.isEmpty()) return
        for (sessionId in candidates) {
            // Skip if we already triggered a reap for this session
            // recently and the grace timer hasn't fired.
            val now = System.currentTimeMillis()
            val previous = inFlight[sessionId]
            if (previous != null && previous > now) continue
            inFlight[sessionId] = now + config.graceMs
            logger.info(
                "OrphanReaper: reaping orphan session $sessionId (parent closed or missing)"
            )
            try {
                // System-level cancel: the orphan reaper runs
                // on behalf of the runtime, not as an agent.
                // callerSessionId = null bypasses the ancestor-only
                // rule so orphans can always be reaped.
                runtime.cancel(
                    sessionId = sessionId,
                    callerSessionId = null,
                    reason = "orphan_reaped",
                    graceMs = config.graceMs,
                )
            } catch (e: Exception) {
                logger.warn("Reap of $sessionId failed: ${e.message}")
            } finally {
                inFlight.remove(sessionId)
            }
        }
    }

    /**
     * List non-closed sessions in this binary whose parent is
     * closed or missing. The query is conservative: a session
     * whose parent is in the same binary but already closed is
     * an orphan; a session whose parent_session_id points at a
     * row that does not exist is also an orphan.
     */
    private fun findOrphans(): List<String> {
        // The listLiveSubtree route returns every descendant of a
        // given root. Walking all top-level roots in this binary
        // would require a separate list call; the current shape
        // is sufficient for the common case where a parent died
        // and we walk its known subtree.
        //
        // We query the daemon for a list of non-closed sessions
        // in this binary whose parent_session_id IS NULL but
        // their lifecycle/runtime_state indicate they are not the
        // root — those are session rows whose parent has been
        // hard-deleted (rare). For the more common case
        // (parent's status='closed' but row still present) we
        // walk each non-closed session and check the parent.
        val all = try {
            agentDbClient.listSessions(
                status = null,
                binaryId = binaryId,
                moduleName = null,
                limit = 500,
                offset = 0,
                parentSessionId = "ALL",
            )
        } catch (e: Exception) {
            return emptyList()
        }
        val out = mutableListOf<String>()
        for (s in all) {
            val state = try { agentDbClient.getRuntimeState(s.sessionId) } catch (e: Exception) { null }
                ?: continue
            if (state.runtimeState == RuntimeState.CLOSED.wire()) continue
            val parentId = s.parentSessionId ?: continue  // root, not an orphan
            val parentState = try { agentDbClient.getRuntimeState(parentId) } catch (e: Exception) { null }
            val parentClosed = parentState == null ||
                parentState.runtimeState == RuntimeState.CLOSED.wire()
            if (parentClosed) out.add(s.sessionId)
        }
        return out
    }
}
