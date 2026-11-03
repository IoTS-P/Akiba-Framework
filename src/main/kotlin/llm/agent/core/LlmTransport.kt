package org.iotsplab.akiba.llm.agent.core

import kotlinx.coroutines.delay
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.agent.context.ContextOverflowDetector
import org.iotsplab.akiba.llm.agent.stream.StreamingChunkPusher
import org.iotsplab.akiba.llm.client.AkibaLLMClient
import org.iotsplab.akiba.llm.client.ChatCompletion
import org.iotsplab.akiba.llm.client.NativeToolCall
import org.iotsplab.akiba.llm.memory.AgentChatMessage
import org.iotsplab.akiba.llm.memory.ChatMemory
import org.iotsplab.akiba.llm.tool.core.ToolCallParser
import org.iotsplab.akiba.llm.tool.core.ToolRegistry

/**
 * The provider-facing half of a strategy run: one logical LLM call, its retry
 * budget, stream progress and the two recovery hooks.
 *
 * [StrategyContext] owns conversation state and tool execution; everything
 * that talks to the model lives here so the transport can be read and tested
 * on its own.
 */

class LlmTransport(
    private val client: AkibaLLMClient,
    private val memory: ChatMemory,
    private val toolRegistry: ToolRegistry,
    private val stats: LoopStats,
    private val sessionId: String?,
    private val logger: Logger,
    private val contextMessagesProvider: (() -> List<AgentChatMessage>)?,
    private val agentDbClient: AgentDatabaseClient?,
    private val beforeChatHook: (() -> Unit)?,
    private val onLLMErrorHook: ((LLMErrorInfo) -> Boolean)?,
    private val onContextOverflowHook: ((consecutiveOverflows: Int) -> Boolean)?,
    private val cancellationReasonProvider: (() -> String?)?,
    private val retryNowRequestedProvider: (() -> Boolean)?,
) {
    /** User messages injected for the next call only; cleared in [callLLM]'s finally block. */
    private val transientUserMessages: MutableList<String> = mutableListOf()

    /** Queue a user message that is visible to the next LLM call but never stored in [memory]. */
    fun addTransientUserMessage(content: String) {
        transientUserMessages.add(content)
    }

    private fun consumeTransientMessages(): List<AgentChatMessage> =
        transientUserMessages.map { AgentChatMessage(role = "user", content = it) }

    private fun clearTransientMessages() {
        transientUserMessages.clear()
    }

    /**
     * Send the conversation to the provider, returning the completion or null
     * when the run was cancelled or the retry budget ran out.
     *
     * Spends at most `1 + maxRetries` attempts with 3/5/7 s waits.  The
     * one-shot error hook may compact the context first; a context-overflow
     * rejection compacts and retries without waiting out the backoff.
     */
    suspend fun callLLM(systemPrompt: String): ChatCompletion? {
        suspend fun invokeChat(progress: LlmProgressReporter): ChatCompletion =
            invokeChatStreaming(systemPrompt, progress)

        val maxRetries = client.config.maxRetries.coerceAtLeast(0)
        var retriesUsed = 0
        var consecutiveOverflows = 0
        var failedOverflowCompactions = 0
        var retryImmediately = false

        fun abort(reason: String): ChatCompletion? {
            stats.lastError = reason
            logger.error(reason)
            return null
        }

        try {
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

            while (true) {
                val attempt = retriesUsed + 1
                val progress = LlmProgressReporter(agentDbClient, sessionId).start()
                try {
                    val result = invokeChat(progress)
                    progress.finish(succeeded = true)
                    if (retriesUsed > 0) {
                        logger.info("LLM call recovered on attempt $attempt/${maxRetries + 1}")
                        writeRetryStatus("retry=$retriesUsed status=recovered")
                    }
                    return result
                } catch (e: Exception) {
                    progress.finish(succeeded = false)
                    // Walk the cause chain so wrapper exceptions (e.g.
                    // "DeepSeek streaming error") don't hide the provider's
                    // actual error payload underneath.
                    val causeChain = generateSequence(e as Throwable) { it.cause }
                        .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
                    stats.lastError = causeChain
                    logger.warn(
                        "LLM chat failed (attempt $attempt/${maxRetries + 1}, " +
                            "retriesLeft=${maxRetries - retriesUsed}): $causeChain"
                    )

                    // ---- One-shot recovery hook (context compaction) ----
                    // Fires only on the first failure of this call, so the
                    // budget cannot be eaten by repeated compactions.
                    if (attempt == 1) {
                        onLLMErrorHook?.let { hook ->
                            val currentTokens =
                                memory.messages().sumOf { client.estimateTokenCount(it.content) }
                            val info = LLMErrorInfo(
                                exception = e,
                                attempt = 1,
                                totalCalls = stats.iterations + 1,
                                currentTokens = currentTokens,
                                stats = stats
                            )
                            logger.warn("Invoking onLLMErrorHook (one-shot, currentTokens=$currentTokens)")
                            try {
                                if (!hook(info)) {
                                    logger.info("onLLMErrorHook declined recovery; proceeding with retries.")
                                }
                            } catch (hookError: Exception) {
                                logger.warn(
                                    "onLLMErrorHook threw: ${hookError.message} (continuing to retries)",
                                    hookError
                                )
                            }
                        }
                    }

                    // ---- Context-overflow emergency compaction ----
                    if (ContextOverflowDetector.isContextOverflow(e)) {
                        consecutiveOverflows++
                        if (consecutiveOverflows >= CONTEXT_OVERFLOW_COMPACT_THRESHOLD) {
                            val tokensBefore =
                                memory.messages().sumOf { client.estimateTokenCount(it.content) }
                            val compacted = try {
                                onContextOverflowHook?.invoke(consecutiveOverflows) == true
                            } catch (hookErr: Exception) {
                                logger.warn("onContextOverflowHook threw: ${hookErr.message}", hookErr)
                                false
                            }
                            val tokensAfter =
                                memory.messages().sumOf { client.estimateTokenCount(it.content) }
                            // A "compacted" report only counts when the context
                            // measurably shrank.  Without this check a hook that
                            // reports success while shrinking nothing (e.g.
                            // re-pruning already-pruned placeholders) resets the
                            // abort counter forever.
                            if (compacted && tokensAfter <= (tokensBefore * 0.95).toInt()) {
                                failedOverflowCompactions = 0
                                retryImmediately = true
                                writeRetryStatus("action=context-compacted")
                                logger.warn(
                                    "Context overflow persisted across $consecutiveOverflows " +
                                        "consecutive rejections — context compacted, retrying promptly."
                                )
                            } else {
                                failedOverflowCompactions++
                                logger.warn(
                                    "Overflow compaction could not shrink the context " +
                                        "(attempt $failedOverflowCompactions/$MAX_FAILED_OVERFLOW_COMPACTIONS)."
                                )
                                if (failedOverflowCompactions >= MAX_FAILED_OVERFLOW_COMPACTIONS) {
                                    return abort(
                                        "Unrecoverable context overflow: the provider keeps rejecting the " +
                                            "prompt for exceeding the model's context length and compaction " +
                                            "cannot shrink it further. Last error: ${e.message}"
                                    )
                                }
                            }
                        }
                    } else {
                        consecutiveOverflows = 0
                        failedOverflowCompactions = 0
                    }

                    // ---- Retry budget exhausted → abort ----
                    // The caller maps null to StopReason.ERROR, and the
                    // runtime propagates the failure to the parent agent.
                    if (retriesUsed >= maxRetries) {
                        val noun = if (maxRetries == 1) "retry" else "retries"
                        return abort(
                            "LLM call failed after $maxRetries $noun " +
                                "(attempts=${retriesUsed + 1}): $causeChain"
                        )
                    }

                    cancellationReasonProvider?.invoke()?.let { reason ->
                        stats.lastError = "Agent cancelled during LLM retry: $reason"
                        logger.warn(stats.lastError)
                        return null
                    }

                    val backoffMs = if (retryImmediately) {
                        retryImmediately = false
                        0L
                    } else {
                        LLM_RETRY_BACKOFF_MS.getOrElse(retriesUsed) { LLM_RETRY_BACKOFF_MS.last() }
                    }
                    retriesUsed++

                    val backoffLabel = formatBackoffDuration(backoffMs)
                    logger.warn(
                        "LLM retry #$retriesUsed of $maxRetries in $backoffLabel: ${e.message}"
                    )
                    writeRetryStatus(
                        "retry=$retriesUsed backoffMs=$backoffMs " +
                            "nextRetryEpochMs=${System.currentTimeMillis() + backoffMs} " +
                            "error=${e.message ?: e.javaClass.simpleName}"
                    )

                    // Wait with early-exit support: the user can request an
                    // immediate retry via the "Retry Now" button.
                    try {
                        val retryProvider = retryNowRequestedProvider
                        if (retryProvider != null) {
                            var remaining = backoffMs
                            while (remaining > 0 && !retryProvider()) {
                                val step = minOf(remaining, 500L)
                                delay(step)
                                remaining -= step
                            }
                        } else if (backoffMs > 0) {
                            delay(backoffMs)
                        }
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        stats.lastError = "LLM retry cancelled: ${e.message}"
                        logger.warn(stats.lastError)
                        return null
                    }
                }
            }
        } finally {
            clearTransientMessages()
        }
    }

    /**
     * Stream one completion and rebuild it from its chunks, falling back to
     * the non-streaming call when the provider reports no streaming support.
     *
     * Each delta is forwarded to the SSE bus so the UI can render the answer
     * while it is generated, and a stalled stream leaves a partial answer the
     * retry can build on.
     */
    private suspend fun invokeChatStreaming(
        systemPrompt: String,
        progress: LlmProgressReporter,
    ): ChatCompletion {
        val baseMessages = contextMessagesProvider?.invoke() ?: memory.messages()
        val messagesForCall = baseMessages + consumeTransientMessages()
        val tools = if (toolRegistry.isEmpty()) null else toolRegistry.toToolSpecifications()

        if (!client.supportsStreaming()) {
            logger.info("[StreamingDiag] client reports no streaming support — falling back to non-streaming chat (session=$sessionId)")
            return client.chat(systemPrompt = systemPrompt, messages = messagesForCall, tools = tools)
        }
        logger.info("[StreamingDiag] invokeChatStreaming starting (session=$sessionId, provider=${client.config.provider}, model=${client.config.modelName})")

        val textBuffer = StringBuilder()
        var finalChunk: org.iotsplab.akiba.llm.client.ChatChunk? = null

        // Progress rows are written by the shared reporter (one writer per
        // attempt).  The consumer coroutine only touches its atomics, so the
        // streaming producer is never back-pressured by a slow daemon write.

        var firstChunkLogged = false
        try {
            client.chatStream(
                systemPrompt = systemPrompt,
                messages = messagesForCall,
                tools = tools,
            ).collect { chunk ->
                if (chunk.isComplete) {
                    finalChunk = chunk
                    logger.info("[StreamingDiag] terminal chunk received (session=$sessionId, totalChunks=${progress.chunkCount}, totalBytes=${progress.byteCount})")
                    // Push the terminal chunk so SSE subscribers see
                    // `done: true` and close the stream cleanly.
                    if (sessionId != null) {
                        StreamingChunkPusher.publish(
                            sessionId = sessionId,
                            delta = "",
                            chunkCount = progress.chunkCount,
                            byteCount = progress.byteCount,
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
                    // Cheap, non-blocking: the reporter writes from its own
                    // coroutine on the next tick.
                    progress.onDelta(
                        delta = chunk.delta,
                        previewTail = textBuffer.toString().takeLast(120).replace("\n", " "),
                    )
                    val cc = progress.chunkCount
                    val bc = progress.byteCount
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
                    chunkCount = progress.chunkCount,
                    byteCount = progress.byteCount,
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
                    progress.chunkCount,
                    progress.byteCount,
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
     * Salvage a stalled stream whose buffered text already holds complete
     * text-embedded tool calls; returns null when the tail is truncated.
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
     * Append a retry/progress row so the UI can show a retry banner, hidden
     * from the LLM context by the persistent memory store.  Best-effort.
     */
    private fun writeRetryStatus(body: String) {
        val sid = sessionId ?: return
        val adb = agentDbClient ?: return
        try {
            adb.appendMessages(
                sid,
                listOf(AgentDatabaseClient.MessageData(
                    role = "system",
                    content = "$LLM_RETRY_STATUS_PREFIX $body",
                ))
            )
        } catch (_: Exception) { /* best-effort UI update */ }
    }

}

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
