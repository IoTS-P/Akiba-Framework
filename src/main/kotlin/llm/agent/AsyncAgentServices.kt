package org.iotsplab.akiba.llm.agent

import org.apache.logging.log4j.LogManager
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import java.util.concurrent.ConcurrentHashMap

/**
 * Bundle of the per-binary async agent components. Created lazily
 * via [AsyncAgentServices.forBinary]. The bundle is shared across
 * every AgentModule on the same binary, so the dispatcher / reaper
 * singletons are not duplicated.
 */
class AsyncAgentServices internal constructor(
    val binaryId: Int,
    val agentDbClient: AgentDatabaseClient,
    val runtime: AgentRuntime,
    val dispatcher: AgentMailboxDispatcher,
    val reaper: OrphanReaper,
    val watchdog: AgentWatchdog,
) {
    fun startBackground() {
        dispatcher.start()
        reaper.start()
        watchdog.start()
    }

    fun stopBackground() {
        dispatcher.stop()
        reaper.stop()
        watchdog.stop()
    }

    /**
     * Startup-time session-state reconciliation for this binary.
     *
     * Wraps the package-level [AgentSessionReconciler] with the
     * `module_startup` reason tag.  Idempotent across the JVM
     * (the reconciler has an in-process guard) so calling it
     * from both [AgentModule.startProcess] and
     * [org.iotsplab.akiba.server.AkibaServer.start] is safe —
     * only the first call per JVM does the actual work.
     *
     * Errors are caught inside the reconciler (logged at
     * WARN); this method itself never throws.
     */
    fun reconcileOnStartup(): AgentSessionReconciler.ReconcileReport =
        AgentSessionReconciler(
            agentDbClient = agentDbClient,
            reasonTag = "module_startup",
        ).reconcile()

    companion object {
        private val logger = LogManager.getLogger(AsyncAgentServices::class.java)
        private val perBinary = ConcurrentHashMap<Int, AsyncAgentServices>()

        fun forBinary(binaryId: Int, agentDbClient: AgentDatabaseClient): AsyncAgentServices =
            perBinary.computeIfAbsent(binaryId) {
                val runtime = AgentRuntime.forBinary(binaryId, agentDbClient)
                val dispatcher = AgentMailboxDispatcher(binaryId, agentDbClient, runtime)
                val reaper = OrphanReaper(binaryId, agentDbClient, runtime)
                val watchdog = AgentWatchdog(binaryId, agentDbClient, runtime)
                AsyncAgentServices(binaryId, agentDbClient, runtime, dispatcher, reaper, watchdog)
            }

        fun shutdownAll() {
            perBinary.values.forEach { it.stopBackground() }
            perBinary.clear()
            AgentRuntime.resetForTests()
        }
    }
}
