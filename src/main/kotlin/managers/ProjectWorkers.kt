package org.iotsplab.akiba.managers

/**
 * The process-wide pieces of the pooled worker topology.
 *
 * One broker for the whole server: turns are queued per project and handed to whichever
 * worker owns that project. Nothing else is shared — the program queue lives inside the
 * worker, because the worker is the only process holding the Ghidra project.
 */
object ProjectWorkers {

    /** Turns waiting for, or running on, a project's worker. */
    val broker = TurnBroker()

    /** Off by default; the switch decides whether /chat uses pooled workers. */
    val poolingEnabled: Boolean get() = System.getenv("AKIBA_PROJECT_WORKERS") == "1"
}
