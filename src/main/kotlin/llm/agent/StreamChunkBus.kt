package org.iotsplab.akiba.llm.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory store that carries live LLM streaming chunks from the
 * **producer** (a manual-agent worker JVM, via the internal HTTP
 * endpoint, or the in-process runtime) to the **consumer** (the
 * frontend, via short incremental polls).
 *
 * Why this exists: the previous "write a progress row to
 * agent_messages every 3 s" approach could only deliver a batch
 * preview every few seconds — the user explicitly asked for
 * "natural per-token growth", which polling the DB can never
 * deliver.  This store keeps the hot path entirely in memory:
 * worker → HTTP POST → map lookup → browser poll, with no DB write
 * in between.  (An earlier revision fanned chunks out over SSE, but
 * the deployment sits behind an outer reverse proxy that kills
 * `text/event-stream` connections, so the transport is plain
 * JSON-over-GET polling instead — a transport every proxy passes.)
 *
 * Design:
 *  - Per-session rolling history of the current (or most recent)
 *    LLM generation.  A new generation starts when a chunk with
 *    `chunkCount <= 1` arrives: the history resets and
 *    [SessionHistory.generation] is bumped so polling clients can
 *    detect the rollover and discard text from the previous
 *    generation.
 *  - Fire-and-forget: [publish] only appends to an in-memory deque.
 *    A slow/dead browser never back-pressures the worker.
 *  - Bounded memory: each generation is capped at [MAX_CHUNKS]
 *    chunks (far beyond any realistic LLM response).
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
                // Overwrite unconditionally: a later CLEAN done
                // (error=null) must clear a previously reported
                // interruption.  This is the "stalled stream turned
                // out to be complete" recovery path — the strategy
                // first publishes done+error, then discovers the
                // buffered text parses as a full response and
                // publishes a final clean done so the frontend swaps
                // the interrupted bubble for the canonical row.
                hist.error = chunk.error
            }
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
