package org.iotsplab.akiba.llm.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory store carrying live LLM streaming chunks from producer
 * (worker JVM via internal HTTP, or in-process runtime) to consumer
 * (frontend incremental polls). Transport is plain JSON-over-GET
 * polling, which every proxy passes; the hot path has no DB writes.
 *
 * Design:
 *  - Per-session rolling history of the current LLM generation.
 *    A chunk with `chunkCount <= 1` starts a new generation: history
 *    resets and [SessionHistory.generation] bumps so polling clients
 *    discard text from the previous generation.
 *  - Fire-and-forget: [publish] only appends to an in-memory deque;
 *    a slow/dead browser never back-pressures the worker.
 *  - Bounded memory: each generation is capped at [MAX_CHUNKS] chunks.
 */
object StreamChunkBus {

    /** Maximum chunks retained per generation. */
    private const val MAX_CHUNKS = 8192

    /**
     * One chunk of an in-flight LLM response.  Mirrors the JSON
     * payload sent by the worker via `POST /agent/internal/stream-chunk`.
     */
    data class Chunk(
        val sessionId: String,
        /** Incremental text delta for this chunk. */
        val delta: String,
        /** Cumulative chunk count (1-based). */
        val chunkCount: Int,
        /** Cumulative byte count of the full response so far. */
        val byteCount: Int,
        /** True on the final chunk of the generation. */
        val done: Boolean = false,
        /** Optional error message (only set on terminal chunks that
         *  carry an error). */
        val error: String? = null,
    )

    /** Rolling per-session history of one LLM generation. */
    class SessionHistory {
        @Volatile var generation = 0
        @Volatile var done = false
        @Volatile var error: String? = null
        val lock = Any()
        val chunks = ArrayDeque<Chunk>()
    }

    private val histories = ConcurrentHashMap<String, SessionHistory>()

    /** Snapshot of the chunks published for a session after [since]. */
    data class HistorySnapshot(
        val generation: Int,
        val chunks: List<Chunk>,
        val done: Boolean,
        val error: String?,
        val latestCount: Int,
    )

    /**
     * Record a chunk in the per-session history.  Never blocks the
     * caller.  Cross-process POSTs may arrive slightly out of order,
     * so chunks are inserted ordered by [Chunk.chunkCount].
     */
    fun publish(chunk: Chunk) {
        if (chunk.sessionId.isBlank()) return
        val hist = histories.getOrPut(chunk.sessionId) { SessionHistory() }
        synchronized(hist.lock) {
            if (chunk.chunkCount <= 1) {
                // New LLM generation — drop anything from the previous one.
                hist.generation++
                hist.chunks.clear()
                hist.done = false
                hist.error = null
            }
            if (hist.chunks.isEmpty() || chunk.chunkCount > hist.chunks.last().chunkCount) {
                hist.chunks.addLast(chunk)
            } else {
                // Out-of-order or duplicate delivery (async worker
                // POSTs): insert at the right position, replacing an
                // existing entry with the same chunkCount.
                val list = hist.chunks.toMutableList()
                var idx = list.size
                while (idx > 0 && list[idx - 1].chunkCount > chunk.chunkCount) idx--
                if (idx < list.size && list[idx].chunkCount == chunk.chunkCount) {
                    list[idx] = chunk
                } else {
                    list.add(idx, chunk)
                }
                hist.chunks.clear()
                hist.chunks.addAll(list)
            }
            while (hist.chunks.size > MAX_CHUNKS) hist.chunks.removeFirst()
            if (chunk.done) {
                hist.done = true
                // Overwrite unconditionally: a later clean done
                // (error=null) clears a previously reported interruption.
                hist.error = chunk.error
            }
        }
    }

    /**
     * Terminate the current generation WITHOUT appending a chunk.
     * Used when the producer (worker JVM) was killed externally — e.g.
     * the user cancelled the chat turn — and will therefore never
     * publish its own terminal chunk.  Polling clients then see
     * `done=true` + [error] and end the streaming bubble (interrupted
     * marker) instead of stalling on "waiting for model…" forever.
     *
     * No-op when the session has no history (nothing was ever
     * streamed) or the current generation already finished.
     */
    fun finish(sessionId: String, error: String?) {
        val hist = histories[sessionId] ?: return
        synchronized(hist.lock) {
            if (hist.done) return
            hist.done = true
            hist.error = error
        }
    }

    /**
     * Return every chunk with `chunkCount > since` for [sessionId],
     * or null when the session has no streaming history at all.
     */
    fun historySince(sessionId: String, since: Int): HistorySnapshot? {
        val hist = histories[sessionId] ?: return null
        return synchronized(hist.lock) {
            HistorySnapshot(
                generation = hist.generation,
                chunks = hist.chunks.filter { it.chunkCount > since },
                done = hist.done,
                error = hist.error,
                latestCount = hist.chunks.lastOrNull()?.chunkCount ?: 0,
            )
        }
    }
}
