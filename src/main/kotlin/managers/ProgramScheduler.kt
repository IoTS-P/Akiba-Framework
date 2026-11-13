package org.iotsplab.akiba.managers

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Decides who may touch a Ghidra program right now, and remembers who was there before.
 *
 * A program is not shareable the way a project is: it carries a transaction manager and
 * mutable analysis state, so two agents editing it at once is a data race. This
 * scheduler is the queue in front of it.
 *
 * The lock covers **one tool call**, not a whole turn: an agent waiting for the model
 * holds nothing, so sessions interleave instead of queueing for minutes. That
 * interleaving is the price of sharing, and it is a real problem for an LLM that
 * believes the program still looks the way it did two calls ago — so every lease
 * carries [Lease.foreign] describing what other sessions did in between.
 */
class ProgramScheduler(private val defaultTimeoutMs: Long = 120_000L) {

    /** One tool call somebody made against a program. */
    data class Hold(
        val sessionId: String,
        val operation: String,
        val mutated: Boolean,
        val startedAt: Long,
        val endedAt: Long,
    )

    private class Slot {
        /** Fair, so a session cannot be starved by a busier neighbour. */
        val lock = ReentrantLock(true)
        @Volatile var holder: String? = null
        @Volatile var depth: Int = 0
        @Volatile var ownerThread: Long? = null

        /** Recent holds, newest last, capped at [LOG_LIMIT]. */
        val log = ArrayDeque<Hold>()
        /** Per session: how many entries of [log] it has already been told about. */
        val seen = HashMap<String, Int>()
        var totalHolds: Int = 0
    }

    /** What a caller learns while holding the program. */
    inner class Lease internal constructor(
        val programId: String,
        val sessionId: String,
        /** Holds by *other* sessions since this session last touched the program. */
        val foreign: List<Hold>,
    ) {
        /** True when someone else changed the program behind the agent's back. */
        val stateChanged: Boolean get() = foreign.any { it.mutated }

        /** The line an agent should see as a transient note, or null when nothing happened. */
        fun note(): String? {
            if (foreign.isEmpty()) return null
            val bySession = foreign.groupBy { it.sessionId }
            val parts = bySession.map { (id, holds) ->
                val ops = holds.map { it.operation }.distinct().joinToString(", ")
                // UUIDs get shortened; short ids are already readable.
                val label = if (id.length > 12) id.take(8) else id
                "session $label ran $ops (${holds.size} call${if (holds.size == 1) "" else "s"})"
            }
            val verb = if (stateChanged) "modified" else "read"
            return "Another session $verb this program between your calls: ${parts.joinToString("; ")}. " +
                "Re-read anything you rely on before assuming it is unchanged."
        }
    }

    private val slots = ConcurrentHashMap<String, Slot>()

    /** Raised when a program stays busy past the timeout; names who has it. */
    class ProgramBusyException(
        val programId: String,
        val holder: String?,
        message: String,
    ) : Exception(message)

    /** Diagnostics: how many sessions are queued behind [programId]. */
    fun waiters(programId: String): Int = slots[programId]?.lock?.queueLength ?: 0

    /** Who is inside [programId] right now, if anyone. */
    fun holder(programId: String): String? = slots[programId]?.holder

    /** Recent holds on [programId], oldest first. Used by tests and diagnostics. */
    fun history(programId: String): List<Hold> = slots[programId]?.let { slot ->
        synchronized(slot) { slot.log.toList() }
    } ?: emptyList()

    /**
     * Runs [block] holding [programId] for [sessionId].
     *
     * Reentrant for the same thread: a tool that calls another tool must not deadlock
     * against itself. [operation] and [mutated] are what other sessions get told about.
     */
    fun <R> withProgram(
        programId: String,
        sessionId: String,
        operation: String = "tool",
        mutated: Boolean = true,
        timeoutMs: Long = defaultTimeoutMs,
        block: (Lease) -> R,
    ): R {
        val slot = slots.computeIfAbsent(programId) { Slot() }
        val me = Thread.currentThread().threadId()

        if (slot.ownerThread == me) {
            slot.depth += 1
            return try {
                block(Lease(programId, sessionId, emptyList()))
            } finally {
                slot.depth -= 1
            }
        }

        val acquired = try {
            slot.lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ProgramBusyException(programId, slot.holder, "Interrupted while waiting for $programId")
        }
        if (!acquired) {
            throw ProgramBusyException(
                programId,
                slot.holder,
                "Program '$programId' is busy in session ${slot.holder} " +
                    "(${slot.lock.queueLength} waiting, ${timeoutMs}ms timeout)",
            )
        }

        slot.holder = sessionId
        slot.ownerThread = me
        slot.depth = 1
        val started = System.currentTimeMillis()
        val foreign = synchronized(slot) { pendingFor(slot, sessionId) }
        try {
            val lease = Lease(programId, sessionId, foreign)
            return block(lease)
        } finally {
            synchronized(slot) { record(slot, sessionId, operation, mutated, started) }
            slot.depth = 0
            slot.ownerThread = null
            slot.holder = null
            slot.lock.unlock()
        }
    }

    /** Holds this session has not been told about yet. */
    private fun pendingFor(slot: Slot, sessionId: String): List<Hold> {
        val seen = slot.seen[sessionId] ?: 0
        val unseen = (slot.totalHolds - slot.log.size).coerceAtLeast(0)
        val from = (seen - unseen).coerceAtLeast(0)
        val pending = slot.log.toList().drop(from).filter { it.sessionId != sessionId }
        slot.seen[sessionId] = slot.totalHolds
        return pending
    }

    private fun record(slot: Slot, sessionId: String, operation: String, mutated: Boolean, started: Long) {
        slot.log.addLast(Hold(sessionId, operation, mutated, started, System.currentTimeMillis()))
        if (slot.log.size > LOG_LIMIT) slot.log.removeFirst()
        slot.totalHolds += 1
    }

    companion object {
        private const val LOG_LIMIT = 64
    }
}
