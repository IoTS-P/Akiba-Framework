package org.iotsplab.akiba.llm.agent

import org.apache.logging.log4j.LogManager
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.client.AkibaLLMClient
import org.iotsplab.akiba.llm.client.ChatCompletion
import org.iotsplab.akiba.llm.client.NativeToolCall
import org.iotsplab.akiba.llm.memory.AgentChatMessage
import org.iotsplab.akiba.llm.memory.ChatMemory
import org.iotsplab.akiba.llm.memory.MemoryManager
import org.iotsplab.akiba.llm.memory.MemoryType
import org.iotsplab.akiba.llm.memory.MemoryScope
import org.iotsplab.akiba.llm.tool.ToolCallParser
import org.iotsplab.akiba.llm.tool.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.iotsplab.akiba.llm.agent.ToolResultDuplicateSeverity
import java.util.UUID
import kotlin.system.measureTimeMillis

// ============================================================
//  LLM retry backoff schedule — see AgentConstants.kt
// ============================================================

/** Human-readable label for a backoff duration, used in log messages. */
private fun formatBackoffDuration(ms: Long): String {
    val seconds = ms / 1000
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> {
            val mins = seconds / 60
            val secs = seconds % 60
            if (secs == 0L) "${mins}min" else "${mins}min${secs}s"
        }
        else -> {
            val hours = seconds / 3600
            val mins = (seconds % 3600) / 60
            if (mins == 0L) "${hours}h" else "${hours}h${mins}min"
        }
    }
}

// ============================================================
//  Agent Strategy — interface
// ============================================================

/**
 * Information about a failed LLM call, passed to [StrategyContext.onLLMErrorHook].
 *
 * The hook can inspect this struct to decide whether to retry (e.g. compact
 * memory on the first error), and may also read or update [stats]
 * to track recovery state. The hook is now called only once (attempt
 * is always 1); the unlimited retry loop handles subsequent failures.
 */
data class LLMErrorInfo(
    /** The exception thrown by the LLM client. */
    val exception: Throwable,
    /** 1-based attempt counter for errors within the current run. 1 = first error, 2 = second, ... */
    val attempt: Int,
    /** Total number of LLM calls attempted so far (including this failed one and all previous ones). */
    val totalCalls: Int,
    /** Current estimated token count of the conversation memory at the time of the error. */
    val currentTokens: Int,
    /** Mutable counters for the current loop run. The hook may read or update fields (e.g. `lastError`). */
    val stats: LoopStats
)

/**
 * Strategy that controls how an [AkibaAgent] iterates through the
 * LLM → tool-call → observe cycle.
 *
 * Built-in implementations:
 * - [ReActStrategy] — explicit Thought → Action → Observation loop
 * - [PlanExecuteStrategy] — plan first, then execute each step
 *
 * Custom strategies can be created by implementing this interface.
 */
interface AgentStrategy {

    /** Human-readable name for logging. */
    val name: String

    /**
     * Run the agent loop to completion.
     *
     * @param ctx Fully-wired [StrategyContext] providing all dependencies.
     * @return The final [AgentResult].
     */
    suspend fun execute(ctx: StrategyContext): AgentResult
}

/**
 * Bundle of everything a strategy needs, provided by [AkibaAgent].
 * This avoids strategies needing a direct reference to the agent itself.
 */
class StrategyContext(
    val client: AkibaLLMClient,
    val memory: ChatMemory,
    val memoryManager: MemoryManager?,
    val toolRegistry: ToolRegistry,
    val sessionId: String?,
    val systemPrompt: String?,
    val maxIterations: Int,
    val enrichSystemPromptWithMemory: Boolean,
    val auditToolCalls: Boolean,
    val logger: org.apache.logging.log4j.Logger = LogManager.getLogger(StrategyContext::class.java),
    val transcript: AgentTranscriptWriter? = null,
    /** Optional hook invoked before every LLM chat call. Used for threshold-based context compaction. */
    val beforeChatHook: (() -> Unit)? = null,
    /**
     * Optional hook invoked **once** on the first LLM call failure.
     *
     * The hook may perform side effects such as context compaction
     * or lowering the effective context cap.  Its return value is
     * **informational only** — the callLLM retry loop always enters
     * an unlimited exponential-backoff retry regardless of the
     * hook's decision, so that long-running unattended agents
     * survive transient provider outages.
     *
     * The hook is called at most once per failed call (attempt = 1).
     * Subsequent retries do not re-invoke the hook.
     *
     * If null (default), no compaction attempt is made and the
     * retry loop starts immediately.
     */
    val onLLMErrorHook: ((LLMErrorInfo) -> Boolean)? = null,
    /**
     * Optional hook invoked when the provider rejects the request with a
     * context-length overflow [CONTEXT_OVERFLOW_COMPACT_THRESHOLD] times
     * in a row (see [ContextOverflowDetector]). This is the escape hatch
     * for models whose context window is unknown to
     * `ModelContextLengthService`: without it the unlimited retry loop
     * spins forever on a request the provider can never accept.
     *
     * Implementations should compact memory — ideally with a fallback
     * that shrinks the context WITHOUT an LLM call (the summarising call
     * itself may be over the limit) — and return true when the context
     * was reduced. May fire repeatedly (each time the overflow persists,
     * compact deeper). After [MAX_FAILED_OVERFLOW_COMPACTIONS]
     * consecutive `false` reports the retry loop aborts (returns null).
     */
    val onContextOverflowHook: ((consecutiveOverflows: Int) -> Boolean)? = null,
    /**
     * Deprecated — no longer used to cap retries.  Retained for
     * backward-compatible constructor signatures.  The callLLM
     * retry loop is now unlimited with backoff (see
     * [LLM_RETRY_BACKOFF_MS]).
     */
    val maxLLMErrorRetries: Int = 1,
    /** Agent database client for audit / session updates. */
    val agentDbClient: AgentDatabaseClient? = null,
    /** Optional provider for the compacted context view sent to the model. */
    val contextMessagesProvider: (() -> List<org.iotsplab.akiba.llm.memory.AgentChatMessage>)? = null,
    /** Advisory detector for repeated tool outputs within the same session. */
    val toolResultDuplicateDetector: ToolResultDuplicateDetector? = null,
    /** Optional domain-specific workflow harness. Defaults to no-op. */
    val harness: AgentHarness = DefaultAgentHarness,
    /**
     * Optional mailbox service threaded into [StrategyContext] so the
     * default [AgentHarness.beforeIteration] can drain unread messages
     * before each LLM call. Null disables the drain.
     */
    val mailboxService: AgentMailboxService? = null,
    /**
     * True when this run was triggered by a STANDBY resume
     * (i.e. the user input matches the
     * `[[AKIBA_INTERNAL:STANDBY_RESUME:<uuid>]]` marker produced
     * by [AgentRuntime.newStandbyResumePrompt]).  Strategies can
     * read this to inject a system-prompt hint so the LLM
     * knows "I am being woken by mail, not starting fresh"
     * and skips re-introductions.  False on a cold start.
     */
    val resumedFromStandby: Boolean = false,
    /**
     * Lifecycle the agent was constructed with.  Strategies
     * use this to decide whether to compact-on-park: when
     * [Lifecycle.STANDBY] the agent is going to park, and a
     * smaller memory footprint means a cheaper next resume.
     * When [Lifecycle.ONE_SHOT] the session is terminal so
     * compacting would be wasted work.
     */
    val lifecycle: Lifecycle = Lifecycle.ONE_SHOT,
    /**
     * Callback to compress [memory] in-place, persisting the
     * summary back to the underlying store (DB when
     * `agentDbClient` is set).  Provided by [AkibaAgent] via
     * `::compact` so strategies do not need a back-reference
     * to the agent instance.  Null when compact is disabled
     * (e.g. `autoCompact=false` or `compactOnStandby=false`).
     *
     * Not a suspend function: [AkibaAgent.compact] blocks
     * synchronously on the LLM call.
     */
    val compactFn: (() -> Boolean)? = null,
    /** Returns a cancellation reason when the runtime has requested this agent to stop. */
    val cancellationReasonProvider: (() -> String?)? = null,

    /**
     * Returns `true` when the user has requested this agent to be
     * paused.  The strategy loop checks this at the top of each
     * iteration (after the current LLM response has been processed)
     * and blocks until the provider returns `false`.
     *
     * Unlike [cancellationReasonProvider], pausing does NOT abort the
     * loop — it simply waits.  The agent finishes the in-flight LLM
     * call, processes its response, then blocks before the next call.
     */
    val pauseCheckProvider: (() -> Boolean)? = null,

    /**
     * Returns `true` when the user has requested an immediate LLM retry
     * (skipping the remaining backoff).  The [callLLM] retry loop polls
     * this every 500ms during the backoff delay and breaks out early
     * when it returns `true`.
     */
    val retryNowRequestedProvider: (() -> Boolean)? = null,
) {
    /** Accumulated counters during the loop. Mutable, shared across the strategy. */
    @Suppress("LeakingThis")
    val stats: LoopStats = LoopStats()

    /**
     * Transient user messages for the **current LLM call only**.
     *
     * Wake boards and resume-context panels are highly real-time
     * scheduling views. They must be visible to the immediate LLM
     * call, but they must NOT be persisted into chat history or
     * compacted summaries. Otherwise old wake boards linger in
     * history and confuse later turns.
     *
     * [applyHarnessDirective] writes wake-board messages here
     * instead of [memory]. [callLLM] appends them to the message
     * list sent to the model and then clears them in `finally`.
     */
    private val transientUserMessages: MutableList<String> = mutableListOf()

    fun addTransientUserMessage(content: String) {
        transientUserMessages.add(content)
    }

    private fun consumeTransientMessages(): List<AgentChatMessage> =
        transientUserMessages.map { AgentChatMessage(role = "user", content = it) }

    private fun clearTransientMessages() {
        transientUserMessages.clear()
    }

    /** Build the effective system prompt with optional memory enrichment. */
    fun buildEffectiveSystemPrompt(extraInject: String? = null): String {
        var base = systemPrompt ?: ""

        if (enrichSystemPromptWithMemory && memoryManager != null) {
            val memoryContext = memoryManager.contextSummary()
            if (memoryContext.isNotBlank()) {
                base = "$base\n\n## Relevant Memories\n$memoryContext"
            }
        }

        if (!extraInject.isNullOrBlank()) {
            base = "$base\n\n$extraInject"
        }

        return base
    }

    /**
     * Call the LLM with the current conversation history.
     *
     * On failure (timeout or any other error), the call is retried
     * indefinitely with a backoff schedule (see
     * [LLM_RETRY_BACKOFF_MS]: 2min for retries 1-5, 3min for retries
     * 6-10, then from retry 11 the interval starts at 5min and grows
     * by 2min for each subsequent retry).
     *
     * If [onLLMErrorHook] is provided, it is invoked **once** before
     * the retry loop starts, so it can compact memory as a recovery
     * side effect. The hook's return value does not affect whether
     * retries occur.
     *
     * The retry loop only exits on:
     * - Success (returns the completion)
     * - Cancellation via [cancellationReasonProvider] (returns null)
     * - Thread interruption (returns null)
     *
     * @return the completion, or null if cancelled or interrupted.
     */
    /**
     * Start a coroutine that periodically writes a "LLM still working"
     * progress heartbeat to `agent_messages`.  The heartbeat lets the
     * frontend render "agent still working on LLM (Ns elapsed)" so the
     * user knows the agent hasn't frozen — critical for long-form
     * generations that take 60+ seconds.
     *
     * Returns a [Job] that the caller must cancel when the LLM call
     * completes (success or failure).  The coroutine is started with
     * [Dispatchers.IO] so daemon HTTP writes don't block the caller.
     *
     * The heartbeat writes a system message with the
     * [LLM_PROGRESS_PREFIX] sentinel, which `PersistentChatMemory`
     * filters out of the LLM context (same as retry-status rows).
     * Failures are swallowed — heartbeat is best-effort UI feedback.
     */
    private fun startProgressHeartbeat(): kotlinx.coroutines.Job? {
        if (sessionId == null || agentDbClient == null) return kotlinx.coroutines.Job()
        val sid = sessionId
        val adb = agentDbClient
        val startedAt = System.currentTimeMillis()
        return kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(LLM_PROGRESS_HEARTBEAT_MS)
                val elapsed = System.currentTimeMillis() - startedAt
                try {
                    adb?.appendMessages(
                        sid,
                        listOf(AgentDatabaseClient.MessageData(
                            role = "system",
                            content = "$LLM_PROGRESS_PREFIX elapsedMs=$elapsed status=in_flight",
                        ))
                    )
                } catch (_: Exception) { /* best-effort */ }
            }
        }
    }

    /** Cancel [heartbeatJob] and write the final "done" progress row. */
    private suspend fun stopProgressHeartbeat(heartbeatJob: kotlinx.coroutines.Job?, succeeded: Boolean) {
        heartbeatJob?.cancel()
        if (sessionId == null || agentDbClient == null) return
        try {
            agentDbClient.appendMessages(
                sessionId,
                listOf(AgentDatabaseClient.MessageData(
                    role = "system",
                    content = "$LLM_PROGRESS_PREFIX status=${if (succeeded) "done" else "failed"}",
                ))
            )
        } catch (_: Exception) { /* best-effort */ }
    }

    /**
     * Invoke the LLM **via streaming**, accumulating chunks into a single
     * [ChatCompletion].  Falls back to the non-streaming [AkibaLLMClient.chat]
     * when the provider reports no streaming support.
     *
     * Why streaming: the user's bug report was "agent appears frozen for
     * 18-30 minutes when generating a long response".  Non-streaming
     * `chat()` gives no signal until the entire response is assembled
     * server-side, so a slow generation looks identical to a hang.
     * Streaming surfaces incremental chunks — we can both (a) write
     * progress previews to `agent_messages` so the frontend sees the
     * response being built, and (b) apply a tight **per-chunk timeout**
     * so a stalled stream is detected within 30 s instead of 120 s.
     *
     * Tool calls: langchain4j's streaming API assembles the full
     * tool-call list only at completion time, so we surface them on
     * the final chunk and copy into the resulting [ChatCompletion].
     */
    private suspend fun invokeChatStreaming(systemPrompt: String): ChatCompletion {
        val baseMessages = contextMessagesProvider?.invoke() ?: memory.messages()
        val messagesForCall = baseMessages + consumeTransientMessages()
        val tools = if (toolRegistry.isEmpty()) null else toolRegistry.toJsonSchemas()

        if (!client.supportsStreaming()) {
            logger.info("[StreamingDiag] client reports no streaming support — falling back to non-streaming chat (session=$sessionId)")
            return client.chat(systemPrompt = systemPrompt, messages = messagesForCall, tools = tools)
        }
        logger.info("[StreamingDiag] invokeChatStreaming starting (session=$sessionId, provider=${client.config.provider}, model=${client.config.modelName})")

        val textBuffer = StringBuilder()
        var finalChunk: org.iotsplab.akiba.llm.client.ChatChunk? = null

        // ---------------------------------------------------------------
        // Streaming progress writer — runs in its own coroutine so the
        // Flow consumer is NEVER blocked by a slow daemon HTTP call.
        //
        // The bug this fixes: previously we called
        // `agentDbClient.appendMessages(...)` inline inside `collect { }`.
        // That call uses `runBlocking` under the hood, so it *blocks the
        // current thread* instead of suspending the coroutine.  During
        // that block, langchain4j's streaming producer kept firing
        // `onPartialResponse`, but our `chatStream` channel (capacity=64)
        // would fill up and `trySend` would silently drop chunks.  The
        // result was the user-observed "preview shows the first 1-2
        // chars, then everything dumps at once" — the Flow only saw a
        // tiny prefix plus the final drain.
        //
        // We instead share state via atomics and let a *separate*
        // coroutine write the progress rows every ~3 s.  The consumer
        // coroutine only ever touches cheap in-memory state, so the
        // producer is never back-pressured.
        // ---------------------------------------------------------------
        val chunkCountAtomic = java.util.concurrent.atomic.AtomicInteger(0)
        val byteCountAtomic  = java.util.concurrent.atomic.AtomicInteger(0)
        val latestPreviewRef = java.util.concurrent.atomic.AtomicReference<String>("")
        val streamFinishedRef = java.util.concurrent.atomic.AtomicBoolean(false)

        val progressWriterJob: kotlinx.coroutines.Job? =
            if (sessionId != null && agentDbClient != null) {
                val sid = sessionId
                val adb = agentDbClient
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    while (!streamFinishedRef.get()) {
                        kotlinx.coroutines.delay(3_000)
                        if (streamFinishedRef.get()) break
                        val cc = chunkCountAtomic.get()
                        val bc = byteCountAtomic.get()
                        val pv = latestPreviewRef.get()
                        try {
                            adb?.appendMessages(
                                sid,
                                listOf(AgentDatabaseClient.MessageData(
                                    role = "system",
                                    content = "$LLM_PROGRESS_PREFIX status=in_flight chunkCount=$cc byteCount=$bc preview=${pv.take(80)}",
                                ))
                            )
                        } catch (_: Exception) { /* best-effort */ }
                    }
                }
            } else null

        var firstChunkLogged = false
        try {
            client.chatStream(
                systemPrompt = systemPrompt,
                messages = messagesForCall,
                tools = tools,
            ).collect { chunk ->
                if (chunk.isComplete) {
                    finalChunk = chunk
                    logger.info("[StreamingDiag] terminal chunk received (session=$sessionId, totalChunks=${chunkCountAtomic.get()}, totalBytes=${byteCountAtomic.get()})")
                    // Push the terminal chunk so SSE subscribers see
                    // `done: true` and close the stream cleanly.
                    if (sessionId != null) {
                        StreamingChunkPusher.publish(
                            sessionId = sessionId,
                            delta = "",
                            chunkCount = chunkCountAtomic.get(),
                            byteCount = byteCountAtomic.get(),
                            done = true,
                            error = null,
                        )
                    }
                } else {
                    if (!firstChunkLogged) {
                        firstChunkLogged = true
                        logger.info("[StreamingDiag] first chunk received (session=$sessionId, deltaLen=${chunk.delta.length})")
                    }
                    textBuffer.append(chunk.delta)
                    val cc = chunkCountAtomic.incrementAndGet()
                    val bc = byteCountAtomic.addAndGet(chunk.delta.length)
                    // Cheap, non-blocking — just update the AtomicReference
                    // for the writer coroutine to pick up on its next tick.
                    latestPreviewRef.set(textBuffer.toString().takeLast(120).replace("\n", " "))
                    // Push this chunk to the SSE bus so the frontend sees
                    // the response grow token-by-token.  This call is
                    // fire-and-forget (in-process: bus trySend; cross-
                    // process: HTTP sendAsync with 2s timeout).
                    if (sessionId != null) {
                        StreamingChunkPusher.publish(
                            sessionId = sessionId,
                            delta = chunk.delta,
                            chunkCount = cc,
                            byteCount = bc,
                            done = false,
                            error = null,
                        )
                    } else {
                        if (cc == 1) {
                            logger.warn("[StreamingDiag] sessionId is NULL — chunks will NOT be pushed to SSE (this is why the bubble is empty)")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // On streaming failure we surface the error to the caller
            // (the retry loop in [callLLM] will catch and retry).
            // Notify stream subscribers so the frontend can mark the
            // bubble as interrupted.
            if (sessionId != null) {
                StreamingChunkPusher.publish(
                    sessionId = sessionId,
                    delta = "",
                    chunkCount = chunkCountAtomic.get(),
                    byteCount = byteCountAtomic.get(),
                    done = true,
                    error = e.message ?: e.javaClass.simpleName,
                )
            }
            val partial = textBuffer.toString()
            if (partial.isNotBlank()) {
                // First try to salvage the buffer: the stream may have
                // stalled AFTER the response was already complete.
                tryRecoverCompletedStream(
                    partial,
                    chunkCountAtomic.get(),
                    byteCountAtomic.get(),
                )?.let { return it }
                // Genuinely truncated. Strip reasoning markup before
                // persisting: think-block content is not worth keeping.
                val partialBody = ToolCallParser.stripThinking(
                    ToolCallParser.stripLeadingThoughtBlock(partial)
                )
                if (partialBody.isNotBlank()) {
                    // Persist the partial with an interrupted marker plus
                    // a continuation instruction, so the retry's message
                    // list (rebuilt from this memory) shows the model its
                    // own partial answer and how to continue. The rule
                    // depends on where the stream was cut: inside a
                    // tool_call JSON block the tail is unparseable, so
                    // demand a fresh complete block; in plain prose,
                    // seamless continuation is fine.
                    val truncatedInToolCall = ToolCallParser.endsWithTruncatedToolCall(partialBody)
                    val instruction = if (truncatedInToolCall) {
                        "The previous assistant message was interrupted WHILE a " +
                            "tool_call JSON block was being emitted. The truncated JSON is " +
                            "invalid and CANNOT be completed by writing only the missing " +
                            "tail — that would leave two unparseable halves. You MUST " +
                            "re-emit the ENTIRE tool call as a single complete JSON block. " +
                            "The text before the truncated block remains valid and may be " +
                            "referenced or reused."
                    } else {
                        "The previous assistant message was interrupted mid-stream " +
                            "(connection stalled) but NOT inside a tool call. Continue the " +
                            "response seamlessly from exactly where it stopped, preserving " +
                            "structure and intent — do not repeat the already-generated " +
                            "content. (If your next output is a tool_call, always emit it " +
                            "as one complete JSON block.)"
                    }
                    runCatching {
                        memory.addAssistantMessage(INTERRUPTED_PARTIAL_PREFIX + partialBody)
                        memory.add("user", RETRY_INSTRUCTION_PREFIX + instruction)
                    }.onFailure { memErr ->
                        logger.warn("[StreamingDiag] failed to persist interrupted partial (session=$sessionId): ${memErr.message}")
                    }
                    logger.info("[StreamingDiag] interrupted partial persisted (session=$sessionId, partialChars=${partialBody.length}, truncatedInToolCall=$truncatedInToolCall)")
                }
            }
            throw e
        } finally {
            streamFinishedRef.set(true)
            progressWriterJob?.cancel()
        }

        val final = finalChunk
            ?: throw org.iotsplab.akiba.llm.client.LLMTimeoutException(
                "Streaming completed without a final chunk (model=${client.config.modelName})"
            )
        return ChatCompletion(
            content = textBuffer.toString(),
            tokenUsage = final.tokenUsage,
            model = client.config.modelName,
            finishReason = final.finishReason,
            toolCalls = final.toolCalls,
        )
    }

    /**
     * Salvage a stalled stream whose buffered text already looks
     * COMPLETE (provider finished generating but never closed the
     * connection), using the same parsing as the normal path:
     *
     *  1. Final Answer marker present → return a plain completion.
     *  2. Complete text-embedded tool call(s) → return them as native
     *     tool calls. A truncated trailing call is NOT parsed
     *     (`ToolCallParser` requires balanced JSON); the next
     *     iteration lets the model re-emit it.
     *
     * Returns null when the buffer looks genuinely truncated — the
     * caller then persists the partial and retries. On success a
     * final clean done chunk is published first so the frontend
     * clears the interrupted marker.
     */
    private fun tryRecoverCompletedStream(
        partial: String,
        chunkCount: Int,
        byteCount: Int,
    ): ChatCompletion? {
        // Strip reasoning markup: a stream interrupted INSIDE a
        // <think> block has no visible body at all (nothing to
        // salvage), and completed think blocks would only add noise
        // to the Final-Answer / tool-call parsing below.
        val stripped = ToolCallParser.stripThinking(
            ToolCallParser.stripLeadingThoughtBlock(partial)
        )
        if (stripped.isBlank()) return null

        fun publishRecoveredDone() {
            if (sessionId != null) {
                StreamingChunkPusher.publish(
                    sessionId = sessionId,
                    delta = "",
                    chunkCount = chunkCount,
                    byteCount = byteCount,
                    done = true,
                    error = null,
                )
            }
        }

        val finalAnswer = SharedFinalAnswerExtractor.extract(stripped)
        if (finalAnswer != null) {
            logger.info("[StreamingDiag] stream stalled but a Final Answer is present — recovering without regeneration (session=$sessionId, chars=${partial.length})")
            publishRecoveredDone()
            return ChatCompletion(
                content = partial,
                tokenUsage = null,
                model = client.config.modelName,
                finishReason = "stop",
                toolCalls = emptyList(),
            )
        }

        val toolCalls = ToolCallParser.parseAll(stripped)
        if (toolCalls.isNotEmpty()) {
            logger.info("[StreamingDiag] stream stalled but ${toolCalls.size} complete tool call(s) parsed — executing them instead of regenerating (session=$sessionId, chars=${partial.length})")
            publishRecoveredDone()
            return ChatCompletion(
                content = partial,
                tokenUsage = null,
                model = client.config.modelName,
                finishReason = "tool_calls",
                toolCalls = toolCalls.map {
                    NativeToolCall(id = it.callId, name = it.name, argumentsJson = it.argumentsJson)
                },
            )
        }
        return null
    }

    /**
     * Record the prompt size of a successful LLM call into
     * [ContextLengthObserver], raising the model's empirically-proven
     * context-window lower bound. Prefers the provider-reported input
     * token count; falls back to the local estimate.
     */
    private fun recordObservedPromptTokens(completion: ChatCompletion) {
        try {
            val promptTokens = completion.tokenUsage?.inputTokenCount
                ?: (contextMessagesProvider?.invoke() ?: memory.messages())
                    .sumOf { client.estimateTokenCount(it.content) }
            ContextLengthObserver.recordSuccess(
                client.config.provider, client.config.modelName, promptTokens
            )
        } catch (_: Exception) { /* best-effort observation */ }
    }

    suspend fun callLLM(systemPrompt: String): ChatCompletion? {
        suspend fun invokeChat(): ChatCompletion = invokeChatStreaming(systemPrompt)

        return try {
            cancellationReasonProvider?.invoke()?.let { reason ->
                stats.lastError = "Agent cancelled before LLM call: $reason"
                logger.warn(stats.lastError)
                return null
            }
            beforeChatHook?.invoke()
            cancellationReasonProvider?.invoke()?.let { reason ->
                stats.lastError = "Agent cancelled before LLM call: $reason"
                logger.warn(stats.lastError)
                return null
            }
            val heartbeat = startProgressHeartbeat()
            try {
                val r = invokeChat()
                stopProgressHeartbeat(heartbeat, succeeded = true)
                recordObservedPromptTokens(r)
                r
            } catch (initialError: Exception) {
                stopProgressHeartbeat(heartbeat, succeeded = false)
                // Walk the cause chain so wrapper exceptions (e.g.
                // "DeepSeek streaming error") don't hide the provider's
                // actual error payload underneath.
                val causeChain = generateSequence(initialError as Throwable) { it.cause }
                    .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
                stats.lastError = causeChain
                logger.warn("LLM chat failed: $causeChain")

                // ---- One-shot recovery hook (context compaction) ----
                // Call the hook ONCE on the first error.  The hook may
                // compact memory (side effect).  Its return value is
                // informational only — we always enter the unlimited
                // retry loop below regardless, because long-running
                // unattended agents must survive transient provider
                // outages without manual intervention.
                onLLMErrorHook?.let { hook ->
                    val currentTokens = memory.messages().sumOf { client.estimateTokenCount(it.content) }
                    val info = LLMErrorInfo(
                        exception = initialError,
                        attempt = 1,
                        totalCalls = stats.iterations + 1,
                        currentTokens = currentTokens,
                        stats = stats
                    )
                    logger.warn(
                        "Invoking onLLMErrorHook (one-shot, currentTokens=$currentTokens): ${initialError.message}"
                    )
                    try {
                        val shouldRetry = hook(info)
                        if (!shouldRetry) {
                            logger.info("onLLMErrorHook declined retry, but entering retry loop anyway (unlimited retries enabled).")
                        }
                    } catch (hookError: Exception) {
                        logger.warn(
                            "onLLMErrorHook threw: ${hookError.message} (continuing to retry loop)",
                            hookError
                        )
                    }
                }

                // ---- Unlimited retry loop with backoff ----
                // Both timeouts and other errors trigger retries.  The
                // backoff schedule is 2min for retries 1-5, 3min for
                // retries 6-10, then from retry 11 it starts at 5min and
                // grows by 2min for each subsequent retry.  The loop only
                // exits on success, cancellation, or thread interruption.
                var retryAttempt = 0
                // Context-overflow tracking: consecutive provider
                // context-length rejections (ContextOverflowDetector —
                // regex on the error text first, then structured checks;
                // the initial error counts as the first). At
                // CONTEXT_OVERFLOW_COMPACT_THRESHOLD the overflow hook
                // compacts the context; other errors reset the count.
                var consecutiveOverflows =
                    if (ContextOverflowDetector.isContextOverflow(initialError)) 1 else 0
                var failedOverflowCompactions = 0
                // Set after a successful overflow compaction: retry
                // promptly instead of waiting out the full backoff.
                var retryImmediately = false
                while (true) {
                    // Check cancellation before sleeping
                    cancellationReasonProvider?.invoke()?.let { reason ->
                        stats.lastError = "Agent cancelled during LLM retry: $reason"
                        logger.warn(stats.lastError)
                        return null
                    }

                    // Beyond the explicit list (retry 11+), the backoff
                    // continues to grow by 2 minutes per retry starting
                    // from 5 minutes at retry 11.
                    val backoffMs = if (retryImmediately) {
                        retryImmediately = false
                        5_000L
                    } else LLM_RETRY_BACKOFF_MS.getOrElse(retryAttempt) {
                        val beyond = retryAttempt - LLM_RETRY_BACKOFF_MS.size
                        // retry 11 = 5min (300_000ms); each step beyond adds 2min (120_000ms)
                        300_000L + beyond * 120_000L
                    }
                    retryAttempt++

                    val backoffLabel = formatBackoffDuration(backoffMs)
                    val nextRetryEpoch = System.currentTimeMillis() + backoffMs
                    logger.warn(
                        "LLM call failed (retry #$retryAttempt). " +
                            "Retrying in $backoffLabel: ${initialError.message}"
                    )

                    // Write a UI-visible status message so the frontend
                    // can show "LLM timed out, retry #N in X minutes".
                    // Uses role="system" with a sentinel prefix that
                    // PersistentChatMemory filters out of the LLM context.
                    if (sessionId != null && agentDbClient != null) {
                        try {
                            val statusContent = "$LLM_RETRY_STATUS_PREFIX retry=$retryAttempt backoffMs=$backoffMs nextRetryEpochMs=$nextRetryEpoch error=${initialError.message ?: initialError.javaClass.simpleName}"
                            agentDbClient.appendMessages(
                                sessionId,
                                listOf(AgentDatabaseClient.MessageData(
                                    role = "system",
                                    content = statusContent,
                                ))
                            )
                        } catch (_: Exception) { /* best-effort UI update */ }
                    }

                    // Wait with early-exit support: the user can request
                    // an immediate retry via the "Retry Now" button,
                    // which sets retryNowRequested on the JobHandle.
                    try {
                        val retryProvider = retryNowRequestedProvider
                        if (retryProvider != null) {
                            // Poll every 500ms for early-exit
                            var remaining = backoffMs
                            while (remaining > 0 && !retryProvider()) {
                                val step = minOf(remaining, 500L)
                                delay(step)
                                remaining -= step
                            }
                            if (retryProvider()) {
                                logger.info("LLM retry #$retryAttempt triggered manually (early exit from ${formatBackoffDuration(backoffMs - remaining)} waited, ${formatBackoffDuration(remaining)} skipped)")
                            }
                        } else {
                            delay(backoffMs)
                        }
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        stats.lastError = "LLM retry cancelled: ${initialError.message}"
                        logger.warn(stats.lastError)
                        return null
                    }

                    // Check cancellation again after sleeping — the
                    // agent may have been cancelled during the wait.
                    cancellationReasonProvider?.invoke()?.let { reason ->
                        stats.lastError = "Agent cancelled during LLM retry backoff: $reason"
                        logger.warn(stats.lastError)
                        return null
                    }

                    val retryHeartbeat = startProgressHeartbeat()
                    try {
                        val result = invokeChat()
                        stopProgressHeartbeat(retryHeartbeat, succeeded = true)
                        recordObservedPromptTokens(result)
                        logger.info("LLM retry #$retryAttempt succeeded after $backoffLabel backoff")
                        // Write a success-notice so the frontend can
                        // clear the retry banner.
                        if (sessionId != null && agentDbClient != null) {
                            try {
                                agentDbClient.appendMessages(
                                    sessionId,
                                    listOf(AgentDatabaseClient.MessageData(
                                        role = "system",
                                        content = "$LLM_RETRY_STATUS_PREFIX retry=$retryAttempt status=recovered",
                                    ))
                                )
                            } catch (_: Exception) {}
                        }
                        return result
                    } catch (retryError: Exception) {
                        stopProgressHeartbeat(retryHeartbeat, succeeded = false)
                        stats.lastError = "${initialError.message}; retry #$retryAttempt failed: ${retryError.message}"
                        logger.warn("LLM retry #$retryAttempt failed: ${retryError.message}", retryError)

                        // ---- Context-overflow emergency compaction ----
                        // After CONTEXT_OVERFLOW_COMPACT_THRESHOLD
                        // consecutive context-length rejections, compact
                        // the context and retry promptly (see hook docs).
                        if (ContextOverflowDetector.isContextOverflow(retryError)) {
                            consecutiveOverflows++
                            // Learn the exact window when the provider
                            // tells us ("maximum context length is N") —
                            // far more precise than any heuristic.
                            ContextOverflowDetector.extractProviderLimit(retryError)?.let { limit ->
                                ContextLengthObserver.recordProviderLimit(
                                    client.config.provider, client.config.modelName, limit
                                )
                            }
                            if (consecutiveOverflows >= CONTEXT_OVERFLOW_COMPACT_THRESHOLD) {
                                // A "compacted" report only counts when the
                                // context measurably shrank.  Without this
                                // check a hook that repeatedly reports success
                                // while shrinking nothing (e.g. re-pruning
                                // already-pruned placeholders) resets the
                                // abort counter forever and the retry loop
                                // never terminates.
                                val tokensBefore = memory.messages().sumOf { client.estimateTokenCount(it.content) }
                                val compacted = try {
                                    onContextOverflowHook?.invoke(consecutiveOverflows) == true
                                } catch (hookErr: Exception) {
                                    logger.warn("onContextOverflowHook threw: ${hookErr.message}", hookErr)
                                    false
                                }
                                val tokensAfter = memory.messages().sumOf { client.estimateTokenCount(it.content) }
                                val actuallyShrank = compacted &&
                                    tokensAfter <= (tokensBefore * 0.95).toInt()
                                if (compacted && !actuallyShrank) {
                                    logger.warn(
                                        "Overflow hook reported success but context only moved " +
                                            "$tokensBefore -> $tokensAfter tokens (<5% shrink); " +
                                            "counting as a failed compaction."
                                    )
                                }
                                if (actuallyShrank) {
                                    failedOverflowCompactions = 0
                                    retryImmediately = true
                                    logger.warn(
                                        "Context overflow persisted across $consecutiveOverflows " +
                                            "consecutive rejections — context compacted, retrying promptly."
                                    )
                                    // UI-visible status so the frontend shows
                                    // "compacted" rather than endless retrying.
                                    if (sessionId != null && agentDbClient != null) {
                                        try {
                                            agentDbClient.appendMessages(
                                                sessionId,
                                                listOf(AgentDatabaseClient.MessageData(
                                                    role = "system",
                                                    content = "$LLM_RETRY_STATUS_PREFIX retry=$retryAttempt action=context-compacted",
                                                ))
                                            )
                                        } catch (_: Exception) { /* best-effort UI update */ }
                                    }
                                } else {
                                    failedOverflowCompactions++
                                    logger.warn(
                                        "Overflow compaction could not shrink the context " +
                                            "(attempt $failedOverflowCompactions/$MAX_FAILED_OVERFLOW_COMPACTIONS)."
                                    )
                                    if (failedOverflowCompactions >= MAX_FAILED_OVERFLOW_COMPACTIONS) {
                                        stats.lastError =
                                            "Unrecoverable context overflow: the provider keeps " +
                                                "rejecting the prompt for exceeding the model's context " +
                                                "length and compaction cannot shrink it further. " +
                                                "Last error: ${retryError.message}"
                                        logger.error(stats.lastError)
                                        return null
                                    }
                                }
                            }
                        } else {
                            consecutiveOverflows = 0
                            failedOverflowCompactions = 0
                        }
                        // Continue the loop for the next retry with longer backoff.
                    }
                }
                // Unreachable — while(true) only exits via return inside the loop.
                @Suppress("UNREACHABLE_CODE")
                return null
            }
        } finally {
            clearTransientMessages()
        }
    }

    /**
     * Execute a tool call and return the result string.
     *
     * Tool calls are NOT cached or deduplicated: many tools are not
     * idempotent (e.g. anything that mutates program state, queries
     * timestamps, or depends on prior side-effects of other tools), so
     * repeating the "same" call at a later point may legitimately yield
     * a different result. We always re-execute.
     */
    /** Result of executing a single tool call. */
    data class ToolResult(
        val output: String,
        val durationMs: Long,
        val rawOutput: String = output,
        /** Duplicate detection result for this tool call, if a
         *  detector is configured. Null when no detector or when
         *  the result was exempted (short output, etc.). */
        val duplicateDetection: ToolResultDuplicateDetection? = null,
    )

    fun executeToolWithDuration(toolCall: ParsedToolCall): ToolResult {
        val tool = toolRegistry.get(toolCall.name)
        if (tool == null) {
            val errMsg = "Unknown tool: ${toolCall.name}. Available: ${toolRegistry.names()}"
            transcript?.writeToolResult(toolCall.name, errMsg)
            return ToolResult(errMsg, 0L)
        }

        // Write tool call to transcript
        transcript?.writeToolCall(
            toolCall.name, toolCall.argumentsJson, toolCall.arguments, stats.iterations
        )

        var rawResult: String
        val durationMs = measureTimeMillis {
            rawResult = tool.safeExecute(toolCall.arguments)
        }

        stats.toolCallsMade++

        // Write tool result to transcript
        transcript?.writeToolResult(toolCall.name, rawResult, durationMs)

        val resultUuid = UUID.randomUUID().toString()
        val stored = ToolResultContext.prepareForStorage(rawResult)

        val isError = rawResult.startsWith("Tool '${toolCall.name}' execution error")
        val duplicateDetection = toolResultDuplicateDetector?.inspect(
            ToolResultInspectionRequest(
                sessionId = sessionId,
                iteration = stats.iterations,
                toolCall = toolCall,
                resultUuid = resultUuid,
                stored = stored,
                isError = isError,
                dedupStrategy = tool.dedupStrategyResolver?.invoke(toolCall.arguments) ?: tool.dedupStrategy,
            )
        )

        // Audit to database and store the retrievable result snapshot.
        // Record FIRST — if this fails, we must NOT include result_uuid
        // in the observation, otherwise the LLM would try
        // read_history_tool_call and get a 404 (the UUID was never stored).
        var resultStored = false
        if (auditToolCalls && sessionId != null && agentDbClient != null) {
            try {
                agentDbClient.recordToolCall(
                    sessionId = sessionId,
                    toolCallId = toolCall.callId,
                    toolName = toolCall.name,
                    toolArgs = toolCall.argumentsJson,
                    resultUuid = resultUuid,
                    resultSummary = rawResult.take(2000),
                    resultContent = stored.content,
                    resultOriginalBytes = stored.originalBytes,
                    resultStoredBytes = stored.storedBytes,
                    resultTruncated = stored.truncated,
                    resultSha256 = stored.sha256,
                    storagePolicy = stored.storagePolicy,
                    success = !isError,
                    durationMs = durationMs
                )
                resultStored = true
            } catch (_: Exception) {}
        }

        // Format the observation — only include result_uuid if the
        // result was successfully stored and can be retrieved later.
        var result = ToolResultContext.formatCurrentResult(
            rawResult,
            if (resultStored) resultUuid else null,
            stored
        )
        duplicateDetection?.toObservationPrefix(toolCall.name)?.let { prefix ->
            result = "$prefix\n\n$result"
        }

        // Auto-remember significant tool results
        if (memoryManager != null && auditToolCalls &&
            result.length >= 100 &&
            !rawResult.startsWith("Tool '${toolCall.name}' execution error")
        ) {
            memoryManager.remember(
                content = "[${toolCall.name}] ${result.take(500)}",
                type = MemoryType.FINDING,
                scope = MemoryScope.SESSION,
                key = "tool:${toolCall.name}",
                importance = 0.6
            )
        }

        return ToolResult(result, durationMs, rawResult, duplicateDetection)
    }

    // Backward-compatible wrapper
    fun executeTool(toolCall: ParsedToolCall): String = executeToolWithDuration(toolCall).output

    /**
     * Update session status in the database.
     *
     * Mirrors the new value to BOTH the legacy `status` column and
     * the canonical `runtime_state` column.  Without the second
     * write, an agent that reaches the "Enter standby mode."
     * marker only flips `status`, leaving `runtime_state` on
     * `running`; the frontend's
     * `GET /agent/sessions/{id}` route reports
     * `runtime_state` as the source of truth, so the status pill
     * in the chat header would stay on "Running" for the rest of
     * the session's lifetime even though the agent has parked.
     *
     * `runtime_state` is the schema's authoritative column —
     * `status` is kept for legacy clients that still read it.
     * The two columns must agree at every observer-visible
     * point; this helper is the single chokepoint that the
     * strategies use, so doing both writes here is sufficient.
     */
    fun updateSessionStatus(status: String) {
        updateSessionStatus(status, reason = null)
    }

    /**
     * Same as [updateSessionStatus] but lets the caller override the
     * `closing_reason` stored alongside the runtime_state. Use this
     * on error / failure paths so the DB row carries the real error
     * detail (which the frontend surfaces in its error banner) rather
     * than the generic `"llm_status:<status>"` placeholder.
     *
     * Pass `null` (or use the no-arg overload) for non-error
     * transitions where the placeholder is fine.
     */
    fun updateSessionStatus(status: String, reason: String?) {
        if (sessionId != null && agentDbClient != null) {
            try {
                agentDbClient.updateSession(sessionId, status = status)
            } catch (_: Exception) {}
            // Mirror onto runtime_state.  Map the strategy's
            // status vocabulary onto the runtime_state enum
            // (closed vs cancelled, error vs failed) so the
            // canonical column is never left holding an
            // out-of-vocabulary value.
            val rs = mapStatusToRuntimeState(status)
            if (rs != null) {
                val closingReason = reason ?: "llm_status:$status"
                try {
                    agentDbClient.setRuntimeState(
                        sessionId = sessionId,
                        runtimeState = rs,
                        closingReason = closingReason,
                    )
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Map the strategy's status vocabulary (which still carries
     * legacy terms like "completed" and "failed") onto the
     * canonical runtime_state enum.  Returns null when the
     * value is not a runtime state and should be left to the
     * legacy `status` column only.
     */
    private fun mapStatusToRuntimeState(status: String): String? = when (status.lowercase()) {
        "running" -> "running"
        "standby" -> "standby"
        "msghandle" -> "msghandle"
        "cancelling" -> "cancelling"
        "closed", "completed" -> "closed"
        "cancelled" -> "closed"
        "error", "failed" -> "error"
        else -> null
    }
}

private fun isAwaitConditionRegistrationSuccess(rawOutput: String): Boolean {
    val out = rawOutput.trim()
    if (out.startsWith("Error:") ||
        out.startsWith("Tool argument error") ||
        out.startsWith("Tool 'await_condition' execution error")
    ) return false
    return Regex("\\\"status\\\"\\s*:\\s*\\\"registered\\\"").containsMatchIn(out)
}

/** Mutable counters shared across a strategy execution. */
class LoopStats {
    var iterations: Int = 0
    var toolCallsMade: Int = 0
    var totalInputTokens: Int = 0
    var totalOutputTokens: Int = 0
    var lastError: String? = null
    /**
     * Set to true when the LLM calls `await_condition` during this
     * run.  When the LLM subsequently produces a Final Answer, the
     * strategy checks this flag: if true, the run ends with
     * [StopReason.STANDBY] (the agent parks and waits for the
     * registered wake condition); if false, the run ends with
     * [StopReason.COMPLETED] (the agent is truly done).
     *
     * The LLM expresses "I want to park" by calling `await_condition`.
     */
    var awaitConditionRegistered: Boolean = false

    /**
     * Consecutive tool calls that returned a WARNING-level
     * duplicate detection. Reset to 0 whenever a non-duplicate
     * (or only NOTICE-level) tool call is observed.
     *
     * When this counter reaches
     * [DUPLICATE_LOOP_FORCE_COMPACT_THRESHOLD], the strategy
     * forces a context compaction and injects a hard break
     * instruction to disrupt the loop.
     */
    var consecutiveDuplicateWarnings: Int = 0
    /**
     * Total times a forced compaction was triggered by the
     * duplicate-loop guard. Capped at
     * [MAX_FORCED_COMPACTIONS] to prevent infinite
     * compaction loops.
     */
    var forcedCompactions: Int = 0

    /**
     * Set once the wake board has AGED its messages during THIS
     * execution (see applyMailboxDrain).  A strategy execution == one
     * wake: aging must happen exactly once per wake, otherwise the
     * "seen N wakes" escalation counter actually counts ReAct
     * *iterations* and long-running requests get falsely escalated
     * while the agent is actively working on them (observed: a
     * native-analysis request "escalated" 11 times during a single
     * continuous analysis, pressuring premature replies).
     */
    var wakeBoardAged: Boolean = false
}

// ============================================================
//  ParsedToolCall — unified tool-call representation
// ============================================================

/**
 * A tool call parsed from an LLM response, regardless of how the
 * provider encodes it (native function_call, JSON in text, etc.).
 */
data class ParsedToolCall(
    val callId: String,
    val name: String,
    val arguments: Map<String, Any?>,
    val argumentsJson: String
)

// ============================================================
//  ReAct Strategy
// ============================================================

/**
 * ReAct (Reasoning + Acting) strategy.
 *
 * The agent follows an explicit **Thought → Action → Observation** cycle:
 *
 * ```
 * ┌──────────────────────────────────────────────┐
 * │  THOUGHT: I need to find the entry point.    │
 * │  ACTION:  list_functions()                   │
 * │  OBSERVATION: main @ 0x401234, foo @ 0x401256│
 * │  THOUGHT: The entry point is main...         │
 * │  ACTION:  decompile(address="0x401234")      │
 * │  OBSERVATION: int main() { return 0; }       │
 * │  THOUGHT: I now have the answer.             │
 * │  FINAL ANSWER: The binary's entry point...   │
 * └──────────────────────────────────────────────┘
 * ```
 *
 * The LLM is instructed to follow this structured format.  On each
 * iteration:
 *
 * 1. The LLM reasons about the current state (THOUGHT)
 * 2. It either calls a tool (ACTION) or gives the final answer
 * 3. If a tool was called, the result is injected as OBSERVATION
 * 4. The loop repeats until a final answer or [maxIterations]
 *
 * This strategy is ideal for tasks requiring step-by-step reasoning
 * and tool usage, such as binary analysis, debugging, or research.
 */
class ReActStrategy : AgentStrategy {

    override val name: String = "ReAct"

    companion object {
        /**
         * Maximum number of tool calls executed sequentially in a single
         * iteration before requesting another LLM round-trip. Defined in
         * [AgentConstants] (it is also referenced inside the ReAct instruction).
         */

        /** The system prompt supplement that instructs the LLM to follow ReAct. */
        val REACT_INSTRUCTION: String get() = AgentPrompts.REACT_INSTRUCTION
    }

    /**
     * Built-in ReAct harness for generic recovery behaviours that used to live
     * inline in [execute]. Domain harnesses supplied by modules are still run
     * first; this one only handles ReAct's default no-action recovery.
     */
    private object DefaultReActHarness : AgentHarness {
        override val name: String = "DefaultReActHarness"

        override fun afterNoAction(
            ctx: StrategyContext,
            assistantText: String,
            completion: ChatCompletion?
        ): AgentHarnessDirective {
            if (completion?.finishReason == "length") {
                ctx.logger.warn("[ReAct] LLM response truncated (finishReason=length). Requesting continuation instead of ending loop.")
                return AgentHarnessDirective.userMessage(
                    "Your previous response was truncated because it exceeded the output length limit. " +
                        "Continue from where you left off. If you had started a tool call, please repeat it now."
                )
            }

            ctx.logger.info(
                "[ReAct] No tool call or final answer detected. Sending format reminder. " +
                    "Text preview: ${assistantText.take(300)}"
            )
            val toolNames = ctx.toolRegistry.names().joinToString(", ")
            return AgentHarnessDirective.userMessage(AgentPrompts.formatReminder(toolNames))
        }
    }

    override suspend fun execute(ctx: StrategyContext): AgentResult {
        val logger = ctx.logger

        logger.info("[ReAct] Harness: ${ctx.harness.name}")
        ctx.applyHarnessDirective(ctx.harness.beforeRun(ctx), "harness.beforeRun")

        // Write system prompt to transcript (only on first iteration)
        ctx.transcript?.writeSystemPrompt(ctx.buildEffectiveSystemPrompt(REACT_INSTRUCTION))

        while (ctx.stats.iterations < ctx.maxIterations) {
            ctx.stats.iterations++
            logger.debug("[ReAct] iteration ${ctx.stats.iterations}/${ctx.maxIterations}")

            // ---- User-requested pause ----
            // Check before each LLM call.  If the user has paused the
            // session, block here until they resume.  The previous
            // iteration's LLM response has already been fully processed
            // (tool calls executed, memory updated), so this is the
            // correct boundary to wait.
            //
            // We use coroutine `delay` (not `Thread.sleep`) so the
            // underlying coroutine Job can still be cancelled while
            // the agent is paused — `delay` is a suspending function
            // that checks for cancellation on every wake.
            if (ctx.pauseCheckProvider?.invoke() == true) {
                logger.info("[ReAct] Session paused by user; blocking before LLM call")
                while (ctx.pauseCheckProvider?.invoke() == true) {
                    if (ctx.cancellationReasonProvider?.invoke() != null) {
                        logger.info("[ReAct] Cancelled while paused")
                        return ctx.stats.toResult(
                            output = "Agent cancelled while paused.",
                            stopReason = StopReason.ERROR
                        )
                    }
                    // `delay` is cooperative — if the coroutine Job is
                    // cancelled (e.g. via runtime.cancel), the
                    // CancellationException propagates here and up
                    // through runChildJob.
                    delay(500)
                }
                logger.info("[ReAct] Session resumed from pause")
            }

            // Mailbox draining is framework infrastructure, not a
            // domain-harness responsibility. Run it unconditionally
            // before the overridable harness hook so every resumed
            // agent marks its wake message as read.
            val mailboxDrain = applyMailboxDrain(ctx, ctx.mailboxService)
            ctx.applyHarnessDirective(mailboxDrain, "mailbox.drain")
            val beforeIteration = ctx.harness.beforeIteration(ctx)
            ctx.applyHarnessDirective(beforeIteration, "harness.beforeIteration")
            val beforeChat = ctx.harness.beforeChat(ctx)
            ctx.applyHarnessDirective(beforeChat, "harness.beforeChat")
            val resumeHint = if (ctx.resumedFromStandby) {
                "[system: you are being woken from standby by new mailbox messages; " +
                    "the framework has drained the unread messages into this turn " +
                    "as user messages. Continue from where you parked — do NOT re-introduce " +
                    "yourself or restart the task.]"
            } else null
            val systemPrompt = ctx.buildEffectiveSystemPrompt(
                joinPromptParts(
                    REACT_INSTRUCTION,
                    beforeIteration.systemPromptAppend,
                    beforeChat.systemPromptAppend,
                    resumeHint,
                )
            )

            val completion = ctx.callLLM(systemPrompt) ?: run {
                ctx.updateSessionStatus("error", reason = ctx.stats.lastError?.take(500))
                return ctx.stats.toResult(
                    output = buildErrorOutput(
                        errorLabel = "Agent error",
                        errorDetail = ctx.stats.lastError,
                        memory = ctx.memory,
                    ),
                    stopReason = StopReason.ERROR
                )
            }

            completion.tokenUsage?.let { usage ->
                ctx.stats.totalInputTokens += usage.inputTokenCount
                ctx.stats.totalOutputTokens += usage.outputTokenCount
            }

            // When the provider uses native function calling, completion.content is often
            // empty (no visible Thought/Action text). We synthesise a ReAct-formatted
            // assistant message so the conversation history remains complete for the LLM.
            val assistantText = when {
                completion.toolCalls.isNotEmpty() && completion.content.isBlank() -> {
                    val actions = completion.toolCalls.joinToString("\n") { tc ->
                        "```json\n{\"tool_call\": {\"name\": \"${tc.name}\", \"arguments\": ${tc.argumentsJson}}}\n```"
                    }
                    "**Thought:** Invoking ${completion.toolCalls.size} tool call(s).\n\n**Action:**\n$actions"
                }
                else -> ToolCallParser.stripThinking(completion.content)
            }
            ctx.memory.addAssistantMessage(
                assistantText,
                tokenCount = completion.tokenUsage?.outputTokenCount,
                inputTokenCount = completion.tokenUsage?.inputTokenCount
            )

            logger.info("[ReAct] Assistant (${assistantText.length} chars): ${assistantText.take(300)}...")
            ctx.transcript?.writeAssistantMessage(
                assistantText, ctx.stats.iterations,
                ctx.stats.totalInputTokens, ctx.stats.totalOutputTokens
            )
            ctx.applyHarnessDirective(
                ctx.harness.afterAssistantMessage(ctx, assistantText, completion),
                "harness.afterAssistantMessage"
            )

            // Parse tool calls.  Final-Answer check MUST come first
            // because the LLM's Final Answer payload is itself JSON and
            // can contain nested objects with `name` / `tool` keys that
            // would otherwise be misclassified as tool calls.  Without
            // this precedence fix, sub-agents that emit a structured JSON
            // final answer (e.g. linear_checker's
            // `{ "suspiciousFunctions": [ { "name": "...", ... } ] }`)
            // trigger spurious tool calls, the agent thinks the LLM has
            // not finished, and the loop runs until maxIterations.
            //
            // Native provider tool calls (`completion.toolCalls`) are
            // untouched by this fix: providers that support native
            // function calling do not interleave tool calls with
            // Final-Answer text in a single completion, so the parser's
            // native path is the source of truth in that case.
            val finalAnswerText = extractFinalAnswer(assistantText)

            val allToolCalls = when {
                completion.toolCalls.isNotEmpty() ->
                    ToolCallParser.parseAllFromCompletion(completion)
                finalAnswerText != null ->
                    // Final Answer marker present — skip text-based tool
                    // call parsing to avoid false positives from JSON
                    // snippets in the answer payload.
                    emptyList()
                else ->
                    ToolCallParser.parseAllFromCompletion(completion).ifEmpty {
                        ToolCallParser.parseAll(assistantText)
                    }
            }

            if (allToolCalls.isNotEmpty()) {
                val toolCallsDirective = ctx.harness.beforeToolCalls(ctx, allToolCalls)
                ctx.applyHarnessDirective(toolCallsDirective, "harness.beforeToolCalls")
                if (toolCallsDirective.blockCurrentAction) {
                    logger.warn("[ReAct] Harness blocked current tool-call batch")
                    continue
                }

                // Cap the batch size so a single response can't blow through the
                // iteration budget or overwhelm downstream tools.
                // Built-in batch-size and same-tool pattern hint.
                // Runs after the domain harness so it doesn't interfere
                // with domain-specific blocking. The hint is added to
                // memory before the observations, so the LLM sees it
                // alongside the results in the next iteration.
                val batchHint = batchToolCallHint(allToolCalls, MAX_BATCH_TOOL_CALLS)
                ctx.applyHarnessDirective(batchHint, "react.batchHint")

                val batch = allToolCalls.take(MAX_BATCH_TOOL_CALLS)
                if (allToolCalls.size > MAX_BATCH_TOOL_CALLS) {
                    logger.warn("[ReAct] LLM emitted ${allToolCalls.size} tool calls in one response, " +
                        "capping at $MAX_BATCH_TOOL_CALLS")
                }

                logger.info("[ReAct] Executing ${batch.size} tool call(s) in this iteration")

                for ((idx, toolCall) in batch.withIndex()) {
                    logger.info("[ReAct] Action ${idx + 1}/${batch.size}: " +
                        "${toolCall.name}(${toolCall.argumentsJson.take(200)})")
                    val beforeTool = ctx.harness.beforeToolExecution(ctx, toolCall)
                    if (beforeTool.blockCurrentAction) {
                        ctx.applyHarnessDirective(beforeTool, "harness.beforeToolExecution")
                        logger.warn("[ReAct] Harness blocked tool call: ${toolCall.name}")
                        continue
                    }

                    // Pre-execution park gate: validate await_condition
                    // BEFORE the tool registers a wake condition.  A
                    // rejected park then surfaces as a single clean
                    // rejection — never as a misleading "registered"
                    // result contradicted by a later rejection message,
                    // and no registry rollback is needed.
                    val prePark = if (!beforeTool.skipCurrentAction && toolCall.name == "await_condition") {
                        ctx.harness.validatePark(ctx, assistantText)
                    } else {
                        AgentHarnessDirective.None
                    }

                    val toolResult = if (beforeTool.skipCurrentAction) {
                        ctx.applyHarnessDirective(beforeTool, "harness.beforeToolExecution")
                        val synthetic = beforeTool.userMessages.firstOrNull() ?: "[harness synthetic] handled: ${toolCall.name}"
                        StrategyContext.ToolResult(synthetic, 0L)
                    } else if (prePark.rejectPark) {
                        ctx.applyHarnessDirective(prePark, "harness.validatePark")
                        StrategyContext.ToolResult(
                            prePark.userMessages.firstOrNull()
                                ?: "[harness.validatePark] park rejected",
                            0L,
                        )
                    } else {
                        ctx.executeToolWithDuration(toolCall)
                    }
                    val observation = toolResult.output
                    val durationMs = toolResult.durationMs
                    val durationStr = if (durationMs > 0) ", duration=${durationMs}ms" else ""

                    val obsMessage = if (batch.size == 1) {
                        "**Observation (${toolCall.name}, args=${toolCall.argumentsJson}$durationStr):** $observation"
                    } else {
                        "**Observation (call ${idx + 1}/${batch.size}, ${toolCall.name}, args=${toolCall.argumentsJson}$durationStr):** $observation"
                    }
                    ctx.memory.addToolMessage(
                        toolCallId = toolCall.callId,
                        toolName = toolCall.name,
                        args = toolCall.argumentsJson,
                        result = obsMessage
                    )
                    if (!beforeTool.skipCurrentAction) {
                        ctx.applyHarnessDirective(beforeTool, "harness.beforeToolExecution")
                    }

                    logger.debug("[ReAct] Observation: ${observation.take(200)}...")

                    // ── Duplicate-loop guard ──────────────────────────
                    // Track consecutive WARNING-level duplicate tool
                    // results.  When the count reaches the threshold,
                    // force a context compaction to break the loop.
                    val dupDetection = toolResult.duplicateDetection
                    if (dupDetection != null &&
                        dupDetection.severity == ToolResultDuplicateSeverity.WARNING
                    ) {
                        ctx.stats.consecutiveDuplicateWarnings++
                    } else {
                        ctx.stats.consecutiveDuplicateWarnings = 0
                    }

                    // ── Harness-requested or auto forced compaction ──
                    val afterToolDirective = ctx.harness.afterToolExecution(ctx, toolCall, toolResult)
                    val needForceCompact = afterToolDirective.forceCompaction ||
                        (ctx.stats.consecutiveDuplicateWarnings >= DUPLICATE_LOOP_FORCE_COMPACT_THRESHOLD &&
                            ctx.stats.forcedCompactions < MAX_FORCED_COMPACTIONS)

                    if (needForceCompact) {
                        val reason = afterToolDirective.loopBreakReason
                            ?: "Detected ${ctx.stats.consecutiveDuplicateWarnings} consecutive " +
                                "WARNING-level duplicate tool calls. The agent appears to be " +
                                "stuck in an unrecoverable loop. Forcing context compaction " +
                                "to disrupt the pattern."
                        logger.warn("[ReAct] Force compaction triggered: $reason")
                        ctx.applyHarnessDirective(afterToolDirective, "harness.afterToolExecution")
                        ctx.memory.addUserMessage(
                            "[SYSTEM] $reason\n\n" +
                                "You have been repeatedly calling the same tool with the same " +
                                "arguments without progress. The context has been compacted. " +
                                "Review what you have accomplished so far (see the summary) and " +
                                "either:\n" +
                                "  1. Produce **Final Answer:** if the task is already answerable, or\n" +
                                "  2. Switch to a meaningfully different tool or argument, or\n" +
                                "  3. If you are stuck, call `await_condition` to park and wait " +
                                "for external input.\n\n" +
                                "Do NOT repeat the same tool call that triggered this compaction."
                        )
                        ctx.transcript?.writeFormatReminder("[forced-compaction] $reason")
                        ctx.compactFn?.let { compactFn ->
                            val ok = compactFn()
                            ctx.stats.forcedCompactions++
                            ctx.stats.consecutiveDuplicateWarnings = 0
                            logger.info("[ReAct] Forced compaction result: ok=$ok, " +
                                "total forced compactions=${ctx.stats.forcedCompactions}")
                        }
                        // Break out of the inner tool-batch loop; the
                        // outer while loop will start a fresh iteration
                        // with the compacted context.
                        break
                    }

                    // Normal path: apply the afterToolExecution directive
                    // (we already fetched it above; no need to call again).
                    ctx.applyHarnessDirective(afterToolDirective, "harness.afterToolExecution")

                    // Detect `await_condition` tool call: this is the
                    // LLM's signal that it wants to park and wait for a
                    // wake condition.  We immediately exit the loop with
                    // [StopReason.STANDBY] — do NOT continue to the next
                    // LLM call.  The LLM already expressed its intent to
                    // park; making it produce another response just to
                    // emit a Final Answer wastes a round-trip and risks
                    // the LLM calling more tools instead of wrapping up.
                    if (toolCall.name == "await_condition") {
                        if (prePark.rejectPark) {
                            // Park was rejected PRE-execution; the
                            // rejection message was already injected.
                            // Nothing registered — just continue.
                            continue
                        }
                        if (!isAwaitConditionRegistrationSuccess(toolResult.rawOutput)) {
                            logger.warn(
                                "[ReAct] await_condition did not register successfully; " +
                                    "continuing so the LLM can correct the arguments. Result: ${toolResult.rawOutput.take(300)}"
                            )
                            continue
                        }
                        ctx.stats.awaitConditionRegistered = true

                        // Park-side gate: let the harness inspect current
                        // state and optionally block the park.  This is
                        // independent from validateFinalAnswer (exit gate)
                        // because the checks differ — e.g. "children still
                        // running" is fine for park but not for exit.
                        val parkDirective = ctx.harness.validatePark(ctx, assistantText)
                        ctx.applyHarnessDirective(parkDirective, "harness.validatePark")
                        if (parkDirective.rejectPark) {
                            // The tool has already registered the condition.
                            // A rejected park must roll it back; otherwise the
                            // still-running agent carries a stale condition
                            // that may fire before its next valid park.
                            ctx.sessionId?.let { WakeConditionRegistry.clearAll(it) }
                            ctx.stats.awaitConditionRegistered = false
                            logger.info("[ReAct] Harness rejected park; rolled back wake condition and continuing loop")
                            continue
                        }

                        logger.info("[ReAct] await_condition registered — exiting loop with StopReason.STANDBY")
                        ctx.updateSessionStatus("standby")
                        val standbyResult = ctx.stats.toResult(
                            output = assistantText,
                            stopReason = StopReason.STANDBY,
                        )
                        ctx.transcript?.writeSessionEnd(standbyResult)
                        return compactAndReturn(ctx, standbyResult)
                    }
                }
            } else {
                // No tool call detected — `finalAnswerText` was computed
                // earlier (it short-circuited the tool-call parser), so
                // reuse it here instead of re-scanning `assistantText`.
                val finalAnswer = finalAnswerText

                if (finalAnswer != null) {
                    val finalAnswerDirective = ctx.harness.validateFinalAnswer(ctx, assistantText, finalAnswer)

                    ctx.applyHarnessDirective(finalAnswerDirective, "harness.validateFinalAnswer")

                    if (!finalAnswerDirective.rejectFinalAnswer) {
                        // Final Answer is the sole exit path. Park is
                        // exclusively requested via await_condition.
                        ctx.updateSessionStatus("cancelling")
                        val result = ctx.stats.toResult(
                            output = finalAnswer,
                            stopReason = StopReason.COMPLETED
                        )
                        ctx.transcript?.writeSessionEnd(result)
                        return compactAndReturn(ctx, result)
                    }
                    logger.warn("[ReAct] Harness rejected Final Answer; continuing loop")
                    continue
                }

                ctx.applyHarnessDirective(
                    ctx.harness.afterNoAction(ctx, assistantText, completion),
                    "harness.afterNoAction"
                )
                ctx.applyHarnessDirective(
                    DefaultReActHarness.afterNoAction(ctx, assistantText, completion),
                    "react.afterNoAction"
                )
            }
        }

        // Max iterations: preserve long-lived STANDBY agents as a
        // safety fallback; ONE_SHOT agents remain terminal errors.
        logger.warn("[ReAct] reached max iterations (${ctx.maxIterations})")
        if (ctx.lifecycle == Lifecycle.STANDBY) {
            ctx.updateSessionStatus("standby", reason = "max_iterations: ${ctx.maxIterations}")
        } else {
            ctx.updateSessionStatus("error", reason = "max_iterations: ${ctx.maxIterations}")
        }
        val result = ctx.stats.toResult(
            output = extractLastAnswer(ctx.memory)
                ?: "Agent reached maximum iterations without producing a final answer.",
            stopReason = StopReason.MAX_ITERATIONS
        )
        ctx.transcript?.writeSessionEnd(result)
        return compactAndReturn(ctx, result)
    }

    /** Extract the Final Answer portion from a ReAct response. */
    private fun extractFinalAnswer(text: String): String? =
        SharedFinalAnswerExtractor.extract(text)

    private fun extractLastAnswer(memory: ChatMemory): String? {
        return memory.messages().lastOrNull { it.role == "assistant" }?.content
    }
}

/**
 * Compact-on-park helper for the default strategies.
 *
 * When the session is going to park (i.e. [StrategyContext.lifecycle]
 * is [Lifecycle.STANDBY] and the run is producing a terminal
 * result), call [StrategyContext.compactFn] to shrink the
 * in-memory history into a single summary message + the
 * trailing rounds.  The compact writes back to the persistent
 * store (when `agentDbClient` is set), so the next
 * `runtime.resumeStandby` runs against a small, summarisable
 * context — cheaper to send and cheaper to reason over.
 *
 * Inactive when:
 *  - lifecycle is ONE_SHOT (session is terminal; compacting is
 *    wasted work and the persistent memory is not consulted
 *    again),
 *  - the strategy was constructed with `compactOnStandby=false`,
 *  - `compactFn` is null,
 *  - the underlying [AkibaAgent.compact] call returns false
 *    (nothing to compact).
 *
 * Errors are caught and logged: a compact failure must not
 * prevent the agent from returning its result and parking.
 *
 * Not a suspend function: the inner LLM call inside
 * [AkibaAgent.compact] is synchronous from the strategy's
 * point of view (the LLM client blocks on the HTTP exchange).
 * The strategy's execute() therefore does not need to grow
 * a `suspend` modifier.
 */
internal fun compactAndReturn(
    ctx: StrategyContext,
    result: AgentResult,
): AgentResult {
    val compactFn = ctx.compactFn ?: return result
    if (ctx.lifecycle != Lifecycle.STANDBY) return result
    try {
        val did = compactFn()
        if (did) {
            ctx.logger.info(
                "[strategy] compacted on park (lifecycle=STANDBY, " +
                    "iterations=${ctx.stats.iterations}, toolCalls=${ctx.stats.toolCallsMade})"
            )
        }
    } catch (e: Exception) {
        ctx.logger.warn(
            "[strategy] compact-on-park failed: ${e.javaClass.simpleName}: ${e.message}",
            e,
        )
    }
    return result
}

// ============================================================
//  Plan-Execute Strategy
// ============================================================

/**
 * Plan-Execute strategy.
 *
 * The agent works in two distinct phases:
 *
 * **Phase 1 — Planning:**
 * The LLM is asked to produce a numbered plan of steps. Each step
 * describes a goal and optionally a tool to use. The plan is stored
 * in [MemoryManager] as type [MemoryType.PLAN].
 *
 * **Phase 2 — Execution:**
 * The agent iterates through each planned step, executing tools and
 * reasoning about observations. After completing all steps (or if
 * execution fails), the agent reflects and optionally **re-plans**.
 *
 * ```
 * ┌───────────────────────────────────────┐
 * │  PLAN Phase                           │
 * │  Step 1: List all functions           │
 * │  Step 2: Decompile the entry point    │
 * │  Step 3: Identify crypto patterns     │
 * ├───────────────────────────────────────┤
 * │  EXECUTE Phase                        │
 * │  ▶ Step 1: list_functions()           │
 * │    → Observation: main, foo, bar...   │
 * │  ▶ Step 2: decompile("0x401234")      │
 * │    → Observation: int main(){...}     │
 * │  ▶ Step 3: search_strings("encrypt")  │
 * │    → Observation: AES_encrypt found   │
 * ├───────────────────────────────────────┤
 * │  REFLECT Phase                        │
 * │  Final Answer: This binary is...      │
 * └───────────────────────────────────────┘
 * ```
 *
 * This strategy excels at complex, multistep tasks where upfront
 * planning reduces meandering and wasted tool calls.
 */
class PlanExecuteStrategy(
    /** Maximum number of re-planning cycles before forcing completion. */
    val maxReplanCycles: Int = 1,

    /** Whether to include step numbers in the execution prompt. */
    val includeStepNumbers: Boolean = true
) : AgentStrategy {

    override val name: String = "Plan-Execute"

    companion object {
        val PLANNING_INSTRUCTION: String get() = AgentPrompts.PLANNING_INSTRUCTION
        val EXECUTION_INSTRUCTION: String get() = AgentPrompts.EXECUTION_INSTRUCTION
        val REFLECTION_INSTRUCTION: String get() = AgentPrompts.REFLECTION_INSTRUCTION
    }

    /** Represents a parsed plan step. */
    data class PlanStep(
        val index: Int,
        val description: String,
        val tool: String?,
        val expected: String?
    )

    override suspend fun execute(ctx: StrategyContext): AgentResult {
        val logger = ctx.logger
        var replanCycle = 0

        logger.info("[PlanExec] Harness: ${ctx.harness.name}")
        ctx.applyHarnessDirective(ctx.harness.beforeRun(ctx), "harness.beforeRun")
        // Planning performs an LLM call before executePlan reaches its
        // per-step drain, so consume wake mail once at strategy entry.
        val initialMailboxDrain = applyMailboxDrain(ctx, ctx.mailboxService)
        ctx.applyHarnessDirective(initialMailboxDrain, "mailbox.drain")

        // Write system prompt to transcript at start
        ctx.transcript?.writeSystemPrompt(ctx.buildEffectiveSystemPrompt(PLANNING_INSTRUCTION))

        while (replanCycle <= maxReplanCycles) {
            // ── Phase 1: Planning ──────────────────────────────────────
            val plan = if (replanCycle == 0) {
                logger.info("[PlanExec] Phase 1: Planning (cycle $replanCycle)")
                createPlan(ctx)
            } else {
                logger.info("[PlanExec] Re-planning (cycle $replanCycle)")
                replan(ctx)
            }

            if (plan.isEmpty()) {
                logger.warn("[PlanExec] Failed to create a plan, falling back to direct answer")
                ctx.updateSessionStatus("error", reason = "empty_plan")
                val result = ctx.stats.toResult(
                    output = "Agent could not formulate a plan for this task.",
                    stopReason = StopReason.ERROR
                )
                ctx.transcript?.writeSessionEnd(result)
                return compactAndReturn(ctx, result)
            }

            // Store plan in memory
            if (ctx.memoryManager != null) {
                val planText = plan.mapIndexed { i, s ->
                    "${i + 1}. ${s.description}" + (s.tool?.let { " [Tool: $it]" } ?: "")
                }.joinToString("\n")
                ctx.memoryManager.remember(
                    content = planText,
                    type = MemoryType.PLAN,
                    scope = MemoryScope.SESSION,
                    key = "plan:cycle$replanCycle",
                    importance = 0.8
                )
            }

            logger.info("[PlanExec] Plan created with ${plan.size} steps: " +
                plan.joinToString("; ") { it.description })

            // ── Phase 2: Execution ─────────────────────────────────────
            when (val executionResult = executePlan(ctx, plan)) {
                is ExecResult.Completed -> {
                    // ── Phase 3: Reflection ────────────────────────────
                    val finalAnswer = reflect(ctx)
                    // Final Answer is the sole exit path. Park is
                    // exclusively requested via await_condition.
                    ctx.updateSessionStatus("cancelling")
                    val result = ctx.stats.toResult(
                        output = finalAnswer,
                        stopReason = StopReason.COMPLETED
                    )
                    ctx.transcript?.writeSessionEnd(result)
                    return compactAndReturn(ctx, result)
                }
                is ExecResult.StandbyRequested -> {
                    ctx.updateSessionStatus("standby")
                    val result = ctx.stats.toResult(
                        output = extractLastAnswer(ctx.memory) ?: "Awaiting wake condition.",
                        stopReason = StopReason.STANDBY,
                    )
                    ctx.transcript?.writeSessionEnd(result)
                    return compactAndReturn(ctx, result)
                }
                is ExecResult.ReplanNeeded -> {
                    replanCycle++
                    logger.info("[PlanExec] Replan requested: ${executionResult.reason}")
                    continue
                }
                is ExecResult.MaxIterations -> {
                    if (ctx.lifecycle == Lifecycle.STANDBY) {
                        ctx.updateSessionStatus("standby", reason = "plan_exec_max_iterations")
                    } else {
                        ctx.updateSessionStatus("error", reason = "plan_exec_max_iterations")
                    }
                    val result = ctx.stats.toResult(
                        output = extractLastAnswer(ctx.memory)
                            ?: "Agent reached maximum iterations during plan execution.",
                        stopReason = StopReason.MAX_ITERATIONS
                    )
                    ctx.transcript?.writeSessionEnd(result)
                    return compactAndReturn(ctx, result)
                }
                is ExecResult.Error -> {
                    ctx.updateSessionStatus("error", reason = executionResult.message.take(500))
                    val result = ctx.stats.toResult(
                        output = buildErrorOutput(
                            errorLabel = "Agent error",
                            errorDetail = executionResult.message,
                            memory = ctx.memory,
                        ),
                        stopReason = StopReason.ERROR
                    )
                    ctx.transcript?.writeSessionEnd(result)
                    return compactAndReturn(ctx, result)
                }
            }
        }

        // Exceeded max replan cycles
        logger.warn("[PlanExec] Exceeded max replan cycles ($maxReplanCycles)")
        val finalAnswer = reflect(ctx)
        // Final Answer always requests exit; parking is handled by
        // await_condition (or the max-iteration safety fallback).
        ctx.updateSessionStatus("cancelling")
        val result = ctx.stats.toResult(
            output = finalAnswer,
            stopReason = StopReason.COMPLETED
        )
        ctx.transcript?.writeSessionEnd(result)
        return compactAndReturn(ctx, result)
    }

    // ---- Phase 1: Planning ────────────────────────────────────────────

    private suspend fun createPlan(ctx: StrategyContext): List<PlanStep> {
        return requestPlan(ctx, PLANNING_INSTRUCTION)
    }

    private suspend fun replan(ctx: StrategyContext): List<PlanStep> {
        return requestPlan(ctx, AgentPrompts.replanPrompt())
    }

    private suspend fun requestPlan(ctx: StrategyContext, instruction: String): List<PlanStep> {
        val beforeChat = ctx.harness.beforeChat(ctx)
        ctx.applyHarnessDirective(beforeChat, "harness.beforeChat")
        val systemPrompt = ctx.buildEffectiveSystemPrompt(
            joinPromptParts(instruction, beforeChat.systemPromptAppend)
        )

        val completion = ctx.callLLM(systemPrompt) ?: return emptyList()
        ctx.stats.iterations++
        completion.tokenUsage?.let { usage ->
            ctx.stats.totalInputTokens += usage.inputTokenCount
            ctx.stats.totalOutputTokens += usage.outputTokenCount
        }

        val planText = ToolCallParser.stripThinking(completion.content)
        ctx.memory.addAssistantMessage(
            planText,
            tokenCount = completion.tokenUsage?.outputTokenCount,
            inputTokenCount = completion.tokenUsage?.inputTokenCount
        )

        ctx.transcript?.writeAssistantMessage(
            planText, ctx.stats.iterations,
            ctx.stats.totalInputTokens, ctx.stats.totalOutputTokens
        )
        ctx.applyHarnessDirective(
            ctx.harness.afterAssistantMessage(ctx, planText, completion),
            "harness.afterAssistantMessage"
        )

        return parsePlan(planText)
    }

    /** Parse a numbered plan from the LLM's text. */
    internal fun parsePlan(text: String): List<PlanStep> {
        val steps = mutableListOf<PlanStep>()
        // Match patterns like "1. description — Tool: name — Expected: info"
        // or simpler "1. description"
        val stepRegex = Regex(
            """^\s*(\d+)\.\s*(.+?)(?:\s*[—–-]\s*Tool:\s*(\S+))?(?:\s*[—–-]\s*Expected:\s*(.+))?\s*$""",
            RegexOption.MULTILINE
        )
        for (match in stepRegex.findAll(text)) {
            val index = match.groupValues[1].toIntOrNull() ?: continue
            val description = match.groupValues[2].trim()
            if (description.isBlank()) continue
            steps.add(PlanStep(
                index = index,
                description = description,
                tool = match.groupValues[3].trim().ifBlank { null },
                expected = match.groupValues[4].trim().ifBlank { null }
            ))
        }
        return steps
    }

    // ---- Phase 2: Execution ───────────────────────────────────────────

    private sealed class ExecResult {
        object Completed : ExecResult()
        class ReplanNeeded(val reason: String) : ExecResult()
        object MaxIterations : ExecResult()
        class Error(val message: String) : ExecResult()
        /** LLM called await_condition — park to STANDBY immediately. */
        object StandbyRequested : ExecResult()
    }

    private suspend fun executePlan(ctx: StrategyContext, plan: List<PlanStep>): ExecResult {
        val logger = ctx.logger

        for ((stepIdx, step) in plan.withIndex()) {
            if (ctx.stats.iterations >= ctx.maxIterations) {
                return ExecResult.MaxIterations
            }

            logger.info("[PlanExec] Executing step ${stepIdx + 1}/${plan.size}: ${step.description}")

            // Build the step-specific prompt
            val stepContext = buildString {
                append("## Plan\n")
                plan.forEachIndexed { i, s ->
                    val marker = when {
                        i < stepIdx -> "✓"
                        i == stepIdx -> "▶"
                        else -> "○"
                    }
                    append("$marker ${i + 1}. ${s.description}")
                    s.tool?.let { append(" [Tool: $it]") }
                    append("\n")
                }
                append("\n")
            }

            val execInstruction = AgentPrompts.executionStepInstruction(
                "Step ${step.index}: ${step.description}" +
                    (step.tool?.let { " (suggested tool: $it)" } ?: "")
            )

            // Execute this step (may need multiple LLM calls for one step)
            var stepIterations = 0
            val maxStepIterations = 3  // safety limit per step

            while (stepIterations < maxStepIterations && ctx.stats.iterations < ctx.maxIterations) {
                stepIterations++
                ctx.stats.iterations++

                // Keep mailbox consumption independent from custom
                // harness implementations, matching the ReAct path.
                val mailboxDrain = applyMailboxDrain(ctx, ctx.mailboxService)
                ctx.applyHarnessDirective(mailboxDrain, "mailbox.drain")
                val beforeIteration = ctx.harness.beforeIteration(ctx)
                ctx.applyHarnessDirective(beforeIteration, "harness.beforeIteration")
                val beforeChat = ctx.harness.beforeChat(ctx)
                ctx.applyHarnessDirective(beforeChat, "harness.beforeChat")
                val effectiveSystemPrompt = ctx.buildEffectiveSystemPrompt(
                    joinPromptParts("$stepContext\n$execInstruction", beforeIteration.systemPromptAppend, beforeChat.systemPromptAppend)
                )

                val completion = ctx.callLLM(effectiveSystemPrompt) ?: return ExecResult.Error(
                    ctx.stats.lastError ?: "LLM call failed"
                )
                completion.tokenUsage?.let { usage ->
                    ctx.stats.totalInputTokens += usage.inputTokenCount
                    ctx.stats.totalOutputTokens += usage.outputTokenCount
                }

                val assistantText = ToolCallParser.stripThinking(completion.content)
                ctx.memory.addAssistantMessage(
                    assistantText,
                    tokenCount = completion.tokenUsage?.outputTokenCount,
                    inputTokenCount = completion.tokenUsage?.inputTokenCount
                )
                ctx.transcript?.writeAssistantMessage(
                    assistantText, ctx.stats.iterations,
                    ctx.stats.totalInputTokens, ctx.stats.totalOutputTokens
                )
                ctx.applyHarnessDirective(
                    ctx.harness.afterAssistantMessage(ctx, assistantText, completion),
                    "harness.afterAssistantMessage"
                )

                // Check for replan request
                if (assistantText.contains("Replan Needed:", ignoreCase = true)) {
                    val reason = assistantText.substringAfter("Replan Needed:").trim().take(500)
                    return ExecResult.ReplanNeeded(reason)
                }

                val stepFinalAnswer = extractFinalAnswer(assistantText)

                // Parse tool call.
                val toolCall = when {
                    completion.toolCalls.isNotEmpty() ->
                        ToolCallParser.parseFromCompletion(completion)
                    stepFinalAnswer != null ->
                        // Final Answer marker present — skip text-based
                        // tool-call parsing to avoid false positives.
                        null
                    else ->
                        ToolCallParser.parseFromCompletion(completion)
                            ?: ToolCallParser.parse(assistantText)
                }

                if (toolCall != null) {
                    val toolCallsDirective = ctx.harness.beforeToolCalls(ctx, listOf(toolCall))
                    ctx.applyHarnessDirective(toolCallsDirective, "harness.beforeToolCalls")
                    if (toolCallsDirective.blockCurrentAction) {
                        logger.warn("[PlanExec] Harness blocked tool-call action")
                        continue
                    }

                    logger.info("[PlanExec]   Tool: ${toolCall.name}(${toolCall.arguments})")
                    val beforeTool = ctx.harness.beforeToolExecution(ctx, toolCall)
                    if (beforeTool.blockCurrentAction) {
                        ctx.applyHarnessDirective(beforeTool, "harness.beforeToolExecution")
                        logger.warn("[PlanExec] Harness blocked tool call: ${toolCall.name}")
                        continue
                    }

                    // Pre-execution park gate (same as the ReAct path):
                    // validate await_condition BEFORE registration.
                    val prePark = if (!beforeTool.skipCurrentAction && toolCall.name == "await_condition") {
                        ctx.harness.validatePark(ctx, assistantText)
                    } else {
                        AgentHarnessDirective.None
                    }

                    val toolResult = if (beforeTool.skipCurrentAction) {
                        ctx.applyHarnessDirective(beforeTool, "harness.beforeToolExecution")
                        val synthetic = beforeTool.userMessages.firstOrNull() ?: "[harness synthetic] handled: ${toolCall.name}"
                        StrategyContext.ToolResult(synthetic, 0L)
                    } else if (prePark.rejectPark) {
                        ctx.applyHarnessDirective(prePark, "harness.validatePark")
                        StrategyContext.ToolResult(
                            prePark.userMessages.firstOrNull()
                                ?: "[harness.validatePark] park rejected",
                            0L,
                        )
                    } else {
                        ctx.executeToolWithDuration(toolCall)
                    }
                    val observation = toolResult.output
                    ctx.memory.addToolMessage(
                        toolCallId = toolCall.callId,
                        toolName = toolCall.name,
                        args = toolCall.argumentsJson,
                        result = "**Observation (Step ${step.index}):** $observation"
                    )
                    if (!beforeTool.skipCurrentAction) {
                        ctx.applyHarnessDirective(beforeTool, "harness.beforeToolExecution")
                    }
                    ctx.applyHarnessDirective(
                        ctx.harness.afterToolExecution(ctx, toolCall, toolResult),
                        "harness.afterToolExecution"
                    )
                    // Detect await_condition: same as ReAct path —
                    // immediately exit with StandbyRequested.  The
                    // outer execute() maps it to STANDBY.  The LLM
                    // has expressed its intent to park; don't wait
                    // for a Final Answer.
                    if (toolCall.name == "await_condition") {
                        if (prePark.rejectPark) {
                            // Park rejected PRE-execution; message already
                            // injected, nothing registered — continue.
                            continue
                        }
                        if (!isAwaitConditionRegistrationSuccess(toolResult.rawOutput)) {
                            logger.warn(
                                "[PlanExec] await_condition did not register successfully; " +
                                    "continuing so the LLM can correct the arguments. Result: ${toolResult.rawOutput.take(300)}"
                            )
                            continue
                        }
                        ctx.stats.awaitConditionRegistered = true

                        val parkDirective = ctx.harness.validatePark(ctx, assistantText)
                        ctx.applyHarnessDirective(parkDirective, "harness.validatePark")
                        if (parkDirective.rejectPark) {
                            ctx.sessionId?.let { WakeConditionRegistry.clearAll(it) }
                            ctx.stats.awaitConditionRegistered = false
                            logger.info("[PlanExec] Harness rejected park; rolled back wake condition and continuing step loop")
                            continue
                        }

                        logger.info("[PlanExec] await_condition registered — exiting immediately")
                        return ExecResult.StandbyRequested
                    }
                    // Continue loop to let the agent process the observation
                } else {
                    // No tool call — reuse the Final Answer already detected
                    // during the tool-call parse step.
                    val finalAnswer = stepFinalAnswer
                    if (finalAnswer != null) {
                        val finalAnswerDirective = ctx.harness.validateFinalAnswer(ctx, assistantText, finalAnswer)

                        ctx.applyHarnessDirective(finalAnswerDirective, "harness.validateFinalAnswer")
                        if (!finalAnswerDirective.rejectFinalAnswer) {
                            return ExecResult.Completed
                        }
                        logger.warn("[PlanExec] Harness rejected Final Answer; continuing step loop")
                        continue
                    }
                    ctx.applyHarnessDirective(
                        ctx.harness.afterNoAction(ctx, assistantText, completion),
                        "harness.afterNoAction"
                    )
                    // Otherwise, the step is done without a tool call; move on
                    break
                }
            }
        }

        // All steps executed
        return ExecResult.Completed
    }

    // ---- Phase 3: Reflection ──────────────────────────────────────────

    private suspend fun reflect(ctx: StrategyContext): String {
        val beforeChat = ctx.harness.beforeChat(ctx)
        ctx.applyHarnessDirective(beforeChat, "harness.beforeChat")
        val systemPrompt = ctx.buildEffectiveSystemPrompt(
            joinPromptParts(REFLECTION_INSTRUCTION, beforeChat.systemPromptAppend)
        )
        ctx.stats.iterations++

        val completion = ctx.callLLM(systemPrompt) ?: return extractLastAnswer(ctx.memory)
            ?: "Agent completed but reflection failed."

        completion.tokenUsage?.let { usage ->
            ctx.stats.totalInputTokens += usage.inputTokenCount
            ctx.stats.totalOutputTokens += usage.outputTokenCount
        }

        val reflection = ToolCallParser.stripThinking(completion.content)
        ctx.memory.addAssistantMessage(
            reflection,
            tokenCount = completion.tokenUsage?.outputTokenCount,
            inputTokenCount = completion.tokenUsage?.inputTokenCount
        )

        ctx.transcript?.writeAssistantMessage(
            reflection, ctx.stats.iterations,
            ctx.stats.totalInputTokens, ctx.stats.totalOutputTokens
        )
        ctx.applyHarnessDirective(
            ctx.harness.afterAssistantMessage(ctx, reflection, completion),
            "harness.afterAssistantMessage"
        )

        // Store reflection as insight
        ctx.memoryManager?.remember(
            content = reflection.take(1000),
            type = MemoryType.INSIGHT,
            scope = MemoryScope.SESSION,
            key = "reflection",
            importance = 0.9
        )

        val finalAnswer = extractFinalAnswer(reflection)
        if (finalAnswer != null) {
            val finalAnswerDirective = ctx.harness.validateFinalAnswer(ctx, reflection, finalAnswer)
            ctx.applyHarnessDirective(finalAnswerDirective, "harness.validateFinalAnswer")
            if (finalAnswerDirective.rejectFinalAnswer) {
                return "Final answer rejected by harness; additional analysis is required before completion."
            }
            return finalAnswer
        }
        return reflection
    }

    // ---- Helpers ──────────────────────────────────────────────────────

    private fun extractFinalAnswer(text: String): String? =
        SharedFinalAnswerExtractor.extract(text)

    private fun extractLastAnswer(memory: ChatMemory): String? {
        return memory.messages().lastOrNull { it.role == "assistant" }?.content
    }
}

/**
 * Shared helper for Final-Answer detection. Used by both [ReActStrategy]
 * and [PlanExecuteStrategy] so the two strategies agree on what counts
 * as a Final Answer marker (and so the precedence fix in
 * `parseToolCallsAfterFinalAnswerCheck` has a single source of truth).
 *
 * IMPORTANT: this is also the boundary used to STOP text-based tool
 * call parsing.  When a Final Answer marker is detected, the strategies
 * MUST NOT scan the text after the marker for tool calls — JSON
 * snippets inside the answer payload (e.g. `{ "name": "process_packet",
 * "address": "0x401234" }` inside a `suspiciousFunctions` array)
 * otherwise get misclassified as actual tool calls, the agent thinks
 * the LLM has not finished, and the loop runs until maxIterations.
 */
internal object SharedFinalAnswerExtractor {
    /**
     * Patterns that locate the START of a Final Answer marker (group 0
     * is the marker text itself).  Order matters: the canonical
     * `**Final Answer:**` form is checked first; the loose
     * `Final Answer:` form (preceded only by non-alphanumeric chars,
     * so `MyFinal Answer:` is excluded) is the fallback.
     */
    private val MARKER_PATTERNS: List<Regex> = listOf(
        Regex("""(?i)\*\*Final Answer:\*\*"""),
        Regex("""(?i)(?<![A-Za-z0-9])Final Answer:"""),
    )

    /**
     * Patterns that extract the full Final Answer payload.  Group 1 is
     * the answer body (everything after `Final Answer:`).  Same
     * precedence as [MARKER_PATTERNS].
     */
    private val ANSWER_PATTERNS: List<Regex> = listOf(
        Regex("""(?i)\*\*Final Answer:\*\*\s*(.*)""", RegexOption.DOT_MATCHES_ALL),
        Regex("""(?i)(?<![A-Za-z0-9])Final Answer:\s*(.*)""", RegexOption.DOT_MATCHES_ALL),
    )

    /** Returns the Final Answer payload, or null if no marker is present. */
    fun extract(text: String): String? {
        for (pattern in ANSWER_PATTERNS) {
            val match = pattern.find(text) ?: continue
            val answer = match.groupValues[1].trim()
            if (answer.isNotBlank()) return answer
        }
        return null
    }

    /**
     * Returns the index of the first Final Answer marker in [text],
     * or null if none.  Used by strategies to slice the assistant text
     * into "before the marker" (where tool calls live) and "after the
     * marker" (where the answer payload lives, which must not be
     * parsed for tool calls).
     */
    fun markerIndex(text: String): Int? {
        for (pattern in MARKER_PATTERNS) {
            val match = pattern.find(text) ?: continue
            return match.range.first
        }
        return null
    }
}

// ============================================================
//  LoopStats → AgentResult convenience
// ============================================================

private fun LoopStats.toResult(output: String, stopReason: StopReason): AgentResult = AgentResult(
    output = output,
    iterations = iterations,
    toolCallsMade = toolCallsMade,
    totalInputTokens = totalInputTokens,
    totalOutputTokens = totalOutputTokens,
    stopReason = stopReason
)

// ============================================================
//  Error envelopes — surface partial progress when the loop dies
// ============================================================
//
// When the strategy loop is interrupted by an LLM error (model busy, network
// timeout, rate limit, context overflow that the recovery hook couldn't fix,
// etc.), the parent's downstream agent previously saw only a one-line error
// string. That discarded whatever progress the child had already made —
// burned tokens, burned tool calls, gone. The helpers below let the
// strategies and the `spawn_sub_agent` tool compose a structured error
// envelope that pairs the error with the child's last successful assistant
// turn (and the tool observations that fed into it), so the parent can:
//
//   1. read the partial output and decide whether the work is salvageable,
//   2. spawn a follow-up sub-agent for the un-finished portion without
//      re-doing everything from scratch,
//   3. cite the partial findings when reporting the dispatch failure.
//
// The snapshot is bounded by `maxChars`; the full conversation is still
// available in the child's persistent memory / transcript for forensic
// detail (use `read_history_tool_call` against `sessionId`).

/**
 * Build a single string suitable for [AgentResult.output] when the loop was
 * interrupted by an LLM error. The envelope contains the error label and a
 * bounded snapshot of the last successful assistant turn (paired with the
 * most recent tool observations that fed into it).
 *
 * @param errorLabel short human-readable label (e.g. "Agent error",
 *   "Child agent crashed").
 * @param errorDetail the actual error message; falls back to a generic
 *   placeholder if null/blank.
 * @param memory the child's [ChatMemory] — the last assistant turn and any
 *   preceding tool results are extracted from here.
 * @param maxChars hard cap on the output size (default 40000, leaving room
 *   for the surrounding envelope text and the parent's own context).
 * @param stopReason the resulting [StopReason]; included for downstream
 *   consumers that switch on it.
 */
internal fun buildErrorOutput(
    errorLabel: String,
    errorDetail: String?,
    memory: ChatMemory,
    maxChars: Int = 8_000,
    stopReason: StopReason = StopReason.ERROR,
): String {
    val detail = errorDetail?.takeIf { it.isNotBlank() } ?: "LLM call failed (no detail)"
    // Reserve ~600 chars for the envelope framing; the snapshot fills the rest.
    val snapshotBudget = (maxChars - 600).coerceAtLeast(512)
    val snapshot = lastAssistantSnapshot(memory, snapshotBudget)
    val sessionHint = memory.sessionId?.let { " (child sessionId=$it; full transcript readable via read_history_tool_call)" }
        ?: ""
    return buildString {
        appendLine("$errorLabel: $detail")
        appendLine("stopReason: ${stopReason.name}")
        if (sessionHint.isNotEmpty()) appendLine(sessionHint)
        appendLine()
        appendLine("Last successful assistant turn before the failure " +
            "(so the parent can salvage partial progress):")
        appendLine("--- begin last-turn snapshot ---")
        if (snapshot == null) {
            appendLine("<no assistant turn recorded before the failure — " +
                "the child crashed before the first model response completed>")
        } else {
            appendLine(snapshot)
        }
        appendLine("--- end last-turn snapshot ---")
    }
}

/**
 * Extract a bounded snapshot of the child's most recent assistant turn and
 * the tool observations that immediately preceded it. Returns null when no
 * assistant turn has been recorded yet.
 *
 * The walk stops at the first non-tool message before the last assistant
 * turn, so the snapshot contains exactly the observations the assistant was
 * reacting to when it produced its last message — not the entire preceding
 * conversation. That keeps the envelope small while preserving the chain
 * of evidence the parent most likely needs.
 */
internal fun lastAssistantSnapshot(
    memory: ChatMemory,
    maxChars: Int = 8_000,
): String? {
    val msgs = try {
        memory.messages()
    } catch (_: Throwable) {
        return null
    }
    if (msgs.isEmpty()) return null

    val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
    if (lastAssistantIdx < 0) return null
    val lastAssistant = msgs[lastAssistantIdx].content

    // Walk back from the last assistant message to gather the tool
    // observations that fed into it. Stop at the previous user/assistant
    // boundary so we capture only the most recent tool batch.
    val preceding = mutableListOf<org.iotsplab.akiba.llm.memory.AgentChatMessage>()
    for (i in (lastAssistantIdx - 1) downTo 0) {
        val m = msgs[i]
        if (m.role != "tool") break
        preceding.add(0, m)
    }

    val snapshot = buildString {
        if (preceding.isNotEmpty()) {
            appendLine("Most recent observation(s) the child had processed:")
            for (m in preceding) {
                val toolName = m.toolName ?: "tool"
                appendLine("- $toolName:")
                appendLine(m.content)
                appendLine()
            }
        }
        appendLine("Last assistant turn before the failure:")
        appendLine(lastAssistant)
    }

    return if (snapshot.length > maxChars) {
        snapshot.substring(0, maxChars) +
            "\n... (truncated; full text in child transcript" +
            (memory.sessionId?.let { ", sessionId=$it" } ?: "") + ")"
    } else {
        snapshot
    }
}
