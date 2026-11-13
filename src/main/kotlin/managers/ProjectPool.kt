package org.iotsplab.akiba.managers

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Handles out Ghidra projects to whoever needs them, instead of letting one session
 * own a project exclusively.
 *
 * Ghidra allows a single process per project directory, so the unit that is pooled is
 * "one open project", not "one project per session": many sessions share the handle and
 * conflicts are narrowed down to the program being worked on. [withProgram] is where
 * that happens — two sessions may hold the same project, never the same program at the
 * same time.
 */
class ProjectPool<T : AutoCloseable>(
    private val idleTimeoutMs: Long = 10 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val open: (key: String) -> T,
) {

    private class Entry<T>(val handle: T) {
        var refs: Int = 0
        var idleSince: Long? = null
    }

    private val entries = ConcurrentHashMap<String, Entry<T>>()
    private val programLocks = ConcurrentHashMap<String, ReentrantLock>()

    /** A borrowed project. Closing it returns the project to the pool. */
    inner class Lease internal constructor(val key: String, val handle: T) : AutoCloseable {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            release(key)
        }
    }

    /** Returns the shared handle for [key], opening it on first use. */
    fun acquire(key: String): Lease {
        val entry = entries.compute(key) { _, existing ->
            val current = existing ?: Entry(open(key))
            synchronized(current) {
                current.refs += 1
                current.idleSince = null
            }
            current
        }!!
        return Lease(key, entry.handle)
    }

    private fun release(key: String) {
        entries[key]?.let { entry ->
            synchronized(entry) {
                entry.refs = (entry.refs - 1).coerceAtLeast(0)
                if (entry.refs == 0) entry.idleSince = clock()
            }
        }
    }

    /** Sessions currently holding [key]. */
    fun refCount(key: String): Int = entries[key]?.let { synchronized(it) { it.refs } } ?: 0

    /**
     * Closes projects nobody has held for [idleTimeoutMs]. Returns the keys it closed.
     * A project with a live lease is never touched, however long the session runs.
     */
    fun sweepIdle(): List<String> {
        val closed = mutableListOf<String>()
        entries.forEach { (key, entry) ->
            val expired = synchronized(entry) {
                val since = entry.idleSince
                entry.refs == 0 && since != null && clock() - since >= idleTimeoutMs
            }
            if (expired && entries.remove(key, entry)) {
                runCatching { entry.handle.close() }
                closed += key
            }
        }
        return closed
    }

    /**
     * Runs [block] holding the lock for one program inside [key].
     *
     * Callers that work on different programs of the same project run in parallel; two
     * callers on the same program queue up. This is the replacement for the old
     * "project is busy" rejection.
     */
    fun <R> withProgram(key: String, programId: String, block: () -> R): R {
        val lock = programLocks.computeIfAbsent("$key\u0000$programId") { ReentrantLock() }
        return lock.withLock(block)
    }

    /** True when [key] is open and someone holds it. */
    fun isHeld(key: String): Boolean = refCount(key) > 0

    /** Closes everything regardless of leases. Used on shutdown. */
    fun closeAll() {
        entries.keys.toList().forEach { key ->
            entries.remove(key)?.let { runCatching { it.handle.close() } }
        }
        entries.clear()
        programLocks.clear()
    }
}
