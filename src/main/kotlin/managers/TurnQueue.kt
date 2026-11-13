package org.iotsplab.akiba.managers

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Turns waiting for the project worker.
 *
 * One worker now serves several sessions, so it needs a queue per session rather than a
 * process per turn: a session's turns must run in order, while two sessions make progress
 * independently. A session that floods the worker is capped, so one script in a loop
 * cannot starve the rest.
 */
class TurnQueue(private val capacityPerSession: Int = 8) {

    /** One thing to run. [id] lets the caller correlate the result with the request. */
    data class Request(
        val id: String,
        val sessionId: String,
        val programId: String,
        val prompt: String,
        val submittedAt: Long = System.currentTimeMillis(),
    )

    private val queues = ConcurrentHashMap<String, LinkedBlockingQueue<Request>>()

    private fun queueOf(sessionId: String) = queues.computeIfAbsent(sessionId) {
        LinkedBlockingQueue(capacityPerSession)
    }

    /** Enqueues [request]; false when this session already has [capacityPerSession] waiting. */
    fun submit(request: Request): Boolean {
        val accepted = queueOf(request.sessionId).offer(request)
        if (!accepted) return false
        return true
    }

    /** Next request for [sessionId] without blocking, or null when there is none. */
    fun poll(sessionId: String): Request? = queues[sessionId]?.poll()

    /** Waits up to [timeoutMs] for [sessionId]'s next request. */
    fun take(sessionId: String, timeoutMs: Long): Request? =
        queueOf(sessionId).poll(timeoutMs, TimeUnit.MILLISECONDS)

    /** Requests still waiting for [sessionId]. */
    fun pending(sessionId: String): Int = queues[sessionId]?.size ?: 0

    /** Sessions with at least one request waiting. */
    fun waitingSessions(): Set<String> = queues.filterValues { it.isNotEmpty() }.keys.toSet()

    /**
     * Drops everything queued for [sessionId] — the session was stopped or deleted, and
     * running its backlog would waste the program on work nobody is waiting for.
     */
    fun cancelSession(sessionId: String): Int {
        val queue = queues.remove(sessionId) ?: return 0
        val dropped = queue.size
        queue.clear()
        return dropped
    }
}
