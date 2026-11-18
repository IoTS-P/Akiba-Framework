package org.iotsplab.akiba.llm.agent.stream

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory store carrying live LLM streaming chunks from producer (worker JVM
 * or in-process runtime) to consumer (frontend incremental polls): plain
 * JSON-over-GET polling, no DB writes on the hot path.
 *
 * The bus owns the authoritative delivery counter ([SessionHistory.nextSeq]),
 * so `seq` never restarts when a new generation begins: a client resumes from
 * the last `seq` it applied and cannot filter out the first chunks of the next
 * generation.  A generation starts on the producer's explicit `newGeneration`
 * chunk (or on a content delta that restarts its numbering at 1).
 * [publish] only appends to a deque, so a slow browser never back-pressures the
 * worker, and history is capped at [MAX_CHUNKS].
 */
object StreamChunkBus {

    /** Answer text. */
    const val KIND_TEXT = "text"
    /** A tool call started; `delta` carries its JSON description. */
    const val KIND_TOOL_START = "tool_start"
    /** That tool call finished; `delta` carries its JSON result summary. */
    const val KIND_TOOL_END = "tool_end"

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
        /** Cumulative chunk count of the producing generation (1-based). */
        val chunkCount: Int,
        /** Cumulative byte count of the full response so far. */
        val byteCount: Int,
        /** True on the final chunk of the generation. */
        val done: Boolean = false,
        /** Optional error message (only set on terminal chunks that
         *  carry an error). */
        val error: String? = null,
        /** Delivery index assigned by the bus; 0 on producer payloads. */
        val seq: Long = 0L,
        /** True on the producer's first chunk of a new LLM call. */
        val newGeneration: Boolean = false,
        /** `text` for answer deltas, `tool_start` / `tool_end` for tool events. */
        val kind: String = KIND_TEXT,
    )

    /** Rolling per-session history of one LLM generation. */
    class SessionHistory {
        @Volatile var generation = 0
        @Volatile var done = false
        @Volatile var error: String? = null

        /** Highest producer [Chunk.chunkCount] of a content delta. */
        var maxDeltaCount = 0

        /** True once a producer declared boundaries explicitly. */
        var generationExplicit = false

        /** Never reset by a generation boundary (see the class doc). */
        var nextSeq = 1L
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
        val latestSeq: Long,
    )

    /**
     * Record a chunk in the per-session history.  Never blocks the caller.
     *
     * A generation starts on the producer's explicit `newGeneration` chunk, or
     * — for producers that only restart their numbering — on a content delta
     * whose `chunkCount` is 1 again.  A terminal chunk NEVER starts a
     * generation, so a one-token answer is not wiped by its own `done`.
     *
     * Chunks are appended in arrival order (the producer's sender is serial),
     * and each one gets the next `seq`.  A content delta whose `chunkCount` is
     * not strictly greater than the highest already stored is a duplicate from
     * a retried push and is dropped, so a retry can never double a token.
     */
    fun publish(chunk: Chunk) {
        if (chunk.sessionId.isBlank()) return
        val hist = histories.getOrPut(chunk.sessionId) { SessionHistory() }
        synchronized(hist.lock) {
            val isText = chunk.kind == KIND_TEXT
            // Tool events ride the same stream but never start or update a
            // generation: their payload is not answer text.
            val implicitStart = isText && chunk.delta.isNotEmpty() && chunk.chunkCount <= 1
            if (chunk.newGeneration || (!hist.generationExplicit && implicitStart)) {
                // New LLM generation — drop the previous one's text, but keep its tool
                // events: a fast model starts the next generation before the browser has
                // polled, and clearing them there left a card spinning with no end in
                // sight.  The client matches start and end by id, so a late end is fine.
                hist.generation++
                hist.generationExplicit = chunk.newGeneration
                hist.chunks.removeAll { it.kind == KIND_TEXT }
                hist.done = false
                hist.error = null
                hist.maxDeltaCount = 0
            }
            // Text deltas are deduped by chunkCount; tool events carry no
            // count of their own and must always land.
            if (isText && chunk.delta.isNotEmpty()) {
                if (chunk.chunkCount <= hist.maxDeltaCount) return
                hist.maxDeltaCount = chunk.chunkCount
            }
            // A terminal chunk shares the last delta's chunkCount; it gets its
            // own entry instead of replacing (and so erasing) that delta.
            hist.chunks.addLast(chunk.copy(seq = hist.nextSeq++))
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
     * Return every chunk with `seq > since` for [sessionId], or null when the
     * session has no streaming history at all.  [since] is the client's own
     * last-applied [Chunk.seq], so a generation change can never hide chunks.
     */
    fun historySince(sessionId: String, since: Long): HistorySnapshot? {
        val hist = histories[sessionId] ?: return null
        return synchronized(hist.lock) {
            HistorySnapshot(
                generation = hist.generation,
                chunks = hist.chunks.filter { it.seq > since },
                done = hist.done,
                error = hist.error,
                latestCount = hist.chunks.lastOrNull()?.chunkCount ?: 0,
                latestSeq = hist.chunks.lastOrNull()?.seq ?: 0L,
            )
        }
    }
}
