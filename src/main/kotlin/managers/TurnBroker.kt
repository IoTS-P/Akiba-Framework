package org.iotsplab.akiba.managers

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Hands turns to the worker that owns a project, and lets the caller wait for the result.
 *
 * Sessions sharing a project are served round-robin rather than first-come-first-served:
 * a session with a backlog must not push another session's turn behind all of it, while
 * each session's own turns still run in order. The worker pulls through [next], which
 * blocks until work arrives or the poll times out, and reports back through [finish].
 */
class TurnBroker(private val capacityPerSession: Int = 8) {

    /**
     * A turn waiting for, or running on, a worker.
     *
     * It carries the whole request — prompt, system prompt, user and program — because
     * the worker does not call back for details: forwarding the message once and letting
     * the worker own the conversation with the model keeps the two sides in step.
     */
    data class Turn(
        val id: String,
        val project: String,
        val sessionId: String,
        val programId: String?,
        val prompt: String,
        val systemPrompt: String = "",
        val username: String = "",
        val programName: String? = null,
        val llm: TurnLlm? = null,
    )

    /**
     * Where and with what credentials this turn talks to the model.
     *
     * The worker holds no credentials of its own: it is told per turn, so changing the
     * model or the provider in Settings takes effect on the next message instead of
     * requiring the process to be restarted. The key only travels over loopback.
     */
    data class TurnLlm(
        val provider: String,
        val modelName: String,
        val apiKey: String,
        val baseUrl: String? = null,
    )

    /** How a turn ended; [error] is the message the user should see. */
    data class Result(val status: String, val error: String? = null) {
        val ok: Boolean get() = status == "ok"
    }

    private class SessionSlot {
        val turns = ArrayDeque<Turn>()
    }

    // project -> (session -> its queue); LinkedHashMap keeps a stable rotation order.
    private val byProject = ConcurrentHashMap<String, LinkedHashMap<String, SessionSlot>>()
    private val results = ConcurrentHashMap<String, CompletableFuture<Result>>()
    private var cursor = 0

    /** Queues [turn]; false when that session already has [capacityPerSession] waiting. */
    @Synchronized
    fun submit(turn: Turn): Boolean {
        val slots = byProject.computeIfAbsent(turn.project) { LinkedHashMap() }
        val slot = slots.getOrPut(turn.sessionId) { SessionSlot() }
        if (slot.turns.size >= capacityPerSession) return false
        slot.turns.addLast(turn)
        results[turn.id] = CompletableFuture()
        (this as java.lang.Object).notifyAll()
        return true
    }

    /** The future completed by [finish]; null when the turn is unknown (already collected). */
    fun resultOf(turnId: String): CompletableFuture<Result>? = results[turnId]

    /**
     * Next turn for [project], rotating between sessions. Returns null if nothing arrives
     * within [timeoutMs]; the worker uses that as its chance to check for shutdown.
     */
    @Synchronized
    fun next(project: String, timeoutMs: Long): Turn? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val slots = byProject[project]?.values?.toList().orEmpty()
            if (slots.isNotEmpty()) {
                for (i in slots.indices) {
                    val index = (cursor + i) % slots.size
                    val turn = slots[index].turns.removeFirstOrNull()
                    if (turn != null) {
                        cursor = (index + 1) % slots.size
                        return turn
                    }
                }
            }
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            (this as java.lang.Object).wait(minOf(50L, left))
        }
    }

    /** Records how [turnId] ended and wakes whoever is waiting on it. */
    fun finish(turnId: String, result: Result) {
        results.remove(turnId)?.complete(result)
    }

    /** Drops a session's backlog and fails anyone still waiting on those turns. */
    @Synchronized
    fun cancelSession(sessionId: String, project: String): Int {
        val slot = byProject[project]?.remove(sessionId) ?: return 0
        val dropped = slot.turns.size
        slot.turns.forEach { results.remove(it.id)?.complete(Result("failed", "Session was stopped.")) }
        slot.turns.clear()
        return dropped
    }

    /** Turns queued for [project] across all its sessions. */
    @Synchronized
    fun pending(project: String): Int = byProject[project]?.values?.sumOf { it.turns.size } ?: 0
}
