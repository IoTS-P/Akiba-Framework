package org.iotsplab.akiba.managers

import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps one long-lived worker process per project and lends it to whoever is running a
 * turn.
 *
 * Ghidra allows a single process to hold a project directory, so the old model — a fresh
 * worker per chat turn, each opening the project — made two sessions on one project
 * impossible. Here the worker outlives the turn: sessions borrow it, and everything they
 * do inside is serialised per program by [ProgramScheduler], not per project.
 */
class ProjectWorkerPool(
    private val idleTimeoutMs: Long = 10 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val spawn: (key: String) -> WorkerProcess,
) {

    /** The bit of a worker process the pool cares about. */
    interface WorkerProcess {
        val alive: Boolean
        val exitCode: Int?
        fun stop()
    }

    private class Entry(val process: WorkerProcess) {
        var refs: Int = 0
        var idleSince: Long? = null
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    /** A borrowed worker. Closing it returns the worker to the pool. */
    inner class Lease internal constructor(val key: String, val process: WorkerProcess) : AutoCloseable {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            release(key)
        }
    }

    /**
     * Returns a live worker for [key], starting one when the pool has none or the one
     * it had died. A dead worker is never handed out: that would be a turn that fails
     * without saying why.
     */
    fun acquire(key: String): Lease {
        val entry = entries.compute(key) { _, existing ->
            val current = when {
                existing == null -> Entry(spawn(key))
                existing.process.alive -> existing
                else -> {
                    runCatching { existing.process.stop() }
                    Entry(spawn(key))
                }
            }
            synchronized(current) {
                current.refs += 1
                current.idleSince = null
            }
            current
        }!!
        return Lease(key, entry.process)
    }

    private fun release(key: String) {
        entries[key]?.let { entry ->
            synchronized(entry) {
                entry.refs = (entry.refs - 1).coerceAtLeast(0)
                if (entry.refs == 0) entry.idleSince = clock()
            }
        }
    }

    /** Turns currently borrowing [key]. */
    fun refCount(key: String): Int = entries[key]?.let { synchronized(it) { it.refs } } ?: 0

    /** True when a live worker is already running for [key]. */
    fun isRunning(key: String): Boolean = entries[key]?.process?.alive == true

    /**
     * Stops workers nobody has borrowed for [idleTimeoutMs]. Returns the keys it stopped.
     * A worker with a live lease is never touched, however long the session runs.
     */
    fun sweepIdle(): List<String> {
        val stopped = mutableListOf<String>()
        entries.forEach { (key, entry) ->
            val expired = synchronized(entry) {
                val since = entry.idleSince
                (entry.refs == 0 && since != null && clock() - since >= idleTimeoutMs) || !entry.process.alive
            }
            if (expired && entries.remove(key, entry)) {
                runCatching { entry.process.stop() }
                stopped += key
            }
        }
        return stopped
    }

    /** Drops a worker after a crash so the next turn starts a clean one. */
    fun discard(key: String) {
        entries.remove(key)?.let { runCatching { it.process.stop() } }
    }

    fun closeAll() {
        entries.keys.toList().forEach { discard(it) }
        entries.clear()
    }
}
