package org.iotsplab.akiba.server

import org.apache.logging.log4j.LogManager
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.agent.runtime.AgentSessionReconciler
import org.iotsplab.akiba.server.routes.DaemonIdentities
import org.iotsplab.akiba.server.routes.withDaemonSession
import kotlin.concurrent.thread

/**
 * Closes session rows that a dead process left in a running state.
 *
 * The startup pass only covers restarts: a turn killed while the server stays up
 * (a worker that dies, a container stop during a long turn) keeps its row
 * "running" until something clears it, and the conversation spins forever. The
 * pass here repeats on a timer, using the credentials of whoever is signed in —
 * the same ones the routes use, so no service account has to be configured.
 *
 * Safety comes from [AgentSessionReconciler] itself: it only closes rows whose
 * `updated_at` is older than the stale window, and a live turn appends messages
 * continuously, so a working conversation is never touched.
 */
object AgentSessionSweeper {

    private val logger = LogManager.getLogger(AgentSessionSweeper::class.java)

    /** Interval between passes; 0 disables the sweeper. */
    private fun intervalMinutes(): Long =
        System.getenv("AKIBA_AGENT_SWEEP_INTERVAL_MINUTES")
            ?.trim()?.toLongOrNull()
            ?: 5L

    /** How long a session may sit unchanged before it is treated as orphaned. */
    private fun staleMinutes(): Long =
        System.getenv("AKIBA_AGENT_SWEEP_STALE_MINUTES")
            ?.trim()?.toLongOrNull()?.takeIf { it > 0 }
            ?: 15L

    fun start(daemonHost: String, daemonPort: Int) {
        val interval = intervalMinutes()
        if (interval <= 0L) {
            logger.info("AkibaServer: session sweeper disabled (AKIBA_AGENT_SWEEP_INTERVAL_MINUTES=$interval)")
            return
        }
        val stale = staleMinutes()
        logger.info(
            "AkibaServer: session sweeper every {} min, closing rows quiet for {} min",
            interval, stale,
        )
        thread(name = "akiba-session-sweeper", isDaemon = true) {
            while (true) {
                try {
                    Thread.sleep(interval * 60_000L)
                    sweep(daemonHost, daemonPort, stale)
                } catch (e: InterruptedException) {
                    return@thread
                } catch (e: Exception) {
                    logger.warn("AkibaServer: session sweep failed: {}", e.message)
                }
            }
        }
    }

    /**
     * One pass over every instance someone is signed in to. Instances with no
     * cached identity are skipped: without credentials there is nothing to
     * connect with, and the next sign-in registers them.
     */
    private fun sweep(daemonHost: String, daemonPort: Int, staleMinutes: Long) {
        for (instance in DaemonIdentities.instances()) {
            try {
                withDaemonSession(daemonHost, daemonPort, instance, serialize = false) { db ->
                    val report = AgentSessionReconciler(
                        agentDbClient = AgentDatabaseClient(db),
                        reasonTag = "periodic_sweep",
                        staleMinutes = staleMinutes,
                    ).reconcile()
                    if (report.reconciled > 0 || report.failed > 0) {
                        logger.info(
                            "AkibaServer: sweep of '$instance' — scanned={}, reconciled={}, failed={}",
                            report.scanned, report.reconciled, report.failed,
                        )
                    }
                }
            } catch (e: Exception) {
                // Signed out, daemon busy, instance gone: all recoverable, none worth a stack trace.
                logger.debug("AkibaServer: sweep of '{}' skipped: {}", instance, e.message)
            }
        }
    }
}
