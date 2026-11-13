package org.iotsplab.akiba.managers

/**
 * The process-wide pieces of the pooled worker topology.
 *
 * One instance each: the server serves every project, so a second broker or scheduler
 * would mean two callers that cannot see each other's queues — which is exactly the
 * double-use of a program the scheduler exists to prevent.
 */
object ProjectWorkers {
    val broker = TurnBroker()
    val registry = ProjectWorkerRegistry()
    val programs = ProgramScheduler()

    /** True when the pooled topology is switched on; otherwise the per-turn worker runs. */
    val poolingEnabled: Boolean get() = ProjectWorkerRegistry.poolingEnabled()
}
