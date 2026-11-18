package org.iotsplab.akiba.llm.tool.core

import java.util.concurrent.ConcurrentHashMap

/**
 * "Stop that tool" pressed by a person, carried across processes.
 *
 * When an agent runs inside the server, [ToolRunner.interrupt] is enough — the future is in
 * the same JVM. A worker process owns the tool call instead, so the server parks the wish
 * here and the worker asks about it while it waits. The entry expires quickly: a press
 * with nothing running is not allowed to kill the next call.
 */
object ToolInterrupts {

    private const val WINDOW_MS = 30_000L

    private val wanted = ConcurrentHashMap<String, Long>()

    fun request(sessionId: String) {
        if (sessionId.isNotBlank()) wanted[sessionId] = System.currentTimeMillis()
    }

    /** True once per press, and only while the press is still recent. */
    fun consume(sessionId: String): Boolean {
        val at = wanted.remove(sessionId) ?: return false
        return System.currentTimeMillis() - at <= WINDOW_MS
    }

    fun clear(sessionId: String) {
        wanted.remove(sessionId)
    }
}
