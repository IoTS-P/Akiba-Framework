package org.iotsplab.akiba.llm.agent.core

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.iotsplab.akiba.data.database.AgentDatabaseClient

/**
 * Best-effort "LLM still working" feedback: one reporter per LLM attempt writes
 * a `__llm_progress__` row every [LLM_PROGRESS_INTERVAL_MS], then `done`/`failed`.
 *
 * All state is atomics and writes happen on a separate IO coroutine, because a
 * consumer blocking on `appendMessages` back-pressures the streaming producer;
 * failures are swallowed so a dead daemon never fails a generation.  Rows carry
 * the [LLM_PROGRESS_PREFIX] sentinel, filtered from context by
 * [org.iotsplab.akiba.llm.memory.PersistentChatMemory].
 */
internal class LlmProgressReporter(
    private val agentDbClient: AgentDatabaseClient?,
    private val sessionId: String?,
) {
    private val startedAt = System.currentTimeMillis()
    private val chunks = AtomicInteger(0)
    private val bytes = AtomicInteger(0)
    private val preview = AtomicReference("")
    private val stopped = AtomicBoolean(false)
    private var job: Job? = null

    /** Number of content deltas seen so far (used by the SSE publisher). */
    val chunkCount: Int get() = chunks.get()

    /** Number of content bytes seen so far (used by the SSE publisher). */
    val byteCount: Int get() = bytes.get()

    /** Start the periodic writer. No-op (but still usable) without a session. */
    fun start(): LlmProgressReporter {
        val sid = sessionId ?: return this
        val adb = agentDbClient ?: return this
        job = GlobalScope.launch(Dispatchers.IO) {
            while (!stopped.get()) {
                delay(LLM_PROGRESS_INTERVAL_MS)
                if (stopped.get()) break
                append(adb, sid, buildString {
                    append("status=in_flight elapsedMs=")
                    append(System.currentTimeMillis() - startedAt)
                    append(" chunkCount=").append(chunks.get())
                    append(" byteCount=").append(bytes.get())
                    val tail = preview.get()
                    if (tail.isNotEmpty()) append(" preview=").append(tail)
                })
            }
        }
        return this
    }

    /**
     * Record one streamed delta.  Non-blocking: the caller is the streaming
     * consumer and must stay cheap.
     *
     * @param previewTail short tail of the accumulated response, already
     *        newline-collapsed by the caller.
     */
    fun onDelta(delta: String, previewTail: String) {
        chunks.incrementAndGet()
        bytes.addAndGet(delta.length)
        preview.set(previewTail)
    }

    /** Stop the writer and emit the terminal row for this attempt. */
    suspend fun finish(succeeded: Boolean) {
        stopped.set(true)
        job?.cancel()
        val sid = sessionId ?: return
        val adb = agentDbClient ?: return
        append(
            adb,
            sid,
            "status=${if (succeeded) "done" else "failed"} " +
                "elapsedMs=${System.currentTimeMillis() - startedAt} " +
                "chunkCount=${chunks.get()} byteCount=${bytes.get()}",
        )
    }

    private fun append(adb: AgentDatabaseClient, sid: String, body: String) {
        try {
            adb.appendMessages(
                sid,
                listOf(
                    AgentDatabaseClient.MessageData(
                        role = "system",
                        content = "$LLM_PROGRESS_PREFIX $body",
                    )
                ),
            )
        } catch (_: Exception) {
            // Best-effort: the UI indicator is not worth failing a generation.
        }
    }
}
