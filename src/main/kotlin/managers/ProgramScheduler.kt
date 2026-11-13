package org.iotsplab.akiba.managers

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Decides who may touch a Ghidra program right now.
 *
 * A program is not shareable in the way a project is: it carries a transaction manager
 * and mutable analysis state, so two agents editing it at once is a data race, not a
 * slow path. This scheduler is the queue in front of it.
 *
 * The lock covers **one tool call**, not a whole turn. An agent that is waiting for the
 * model to answer holds nothing, so two sessions interleave between steps instead of
 * each waiting minutes for the other's turn to end.
 */
class ProgramScheduler(private val defaultTimeoutMs: Long = 120_000L) {

    private class Slot {
        /** Fair, so a session cannot be starved by a busier neighbour. */
        val lock = ReentrantLock(true)
        @Volatile var holder: String? = null
        @Volatile var depth: Int = 0
        @Volatile var ownerThread: Long? = null
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

    /**
     * Runs [block] holding [programId] for [sessionId].
     *
     * Reentrant for the same thread: a tool that calls another tool must not deadlock
     * against itself.
     */
    fun <R> withProgram(
        programId: String,
        sessionId: String,
        timeoutMs: Long = defaultTimeoutMs,
        block: () -> R,
    ): R {
        val slot = slots.computeIfAbsent(programId) { Slot() }
        val me = Thread.currentThread().threadId()

        if (slot.ownerThread == me) {
            slot.depth += 1
            return try {
                block()
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
        return try {
            block()
        } finally {
            // An exception must never leave the program locked: the next session would
            // hang until the timeout, which is indistinguishable from a crash.
            slot.depth = 0
            slot.ownerThread = null
            slot.holder = null
            slot.lock.unlock()
        }
    }
}
