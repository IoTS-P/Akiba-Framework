package org.iotsplab.akiba.llm.client

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.http.client.HttpClientBuilder
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema
import dev.langchain4j.model.chat.request.json.JsonEnumSchema
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import dev.langchain4j.model.chat.request.json.JsonSchemaElement
import dev.langchain4j.model.chat.request.json.JsonStringSchema
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.PartialThinking
import dev.langchain4j.model.chat.response.PartialThinkingContext
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.apache.logging.log4j.LogManager

/**
 * Thrown when a single LLM attempt exceeds
 * [LLM_ATTEMPT_TIMEOUT_MS][org.iotsplab.akiba.llm.client.LLM_ATTEMPT_TIMEOUT_MS]
 * (no response, or 30 s of stream silence).
 */
class LLMTimeoutException(message: String) : RuntimeException(message)

/**
 * Deadline for one **streaming** attempt, in milliseconds — both the initial
 * connection (no first byte) and the stall watchdog, which declares the stream
 * dead when **no** chunk (content *or* thinking delta) arrives for this long.
 *
 * One-shot `chat()` calls are deliberately **not** bounded by this: a summary
 * compaction has no chunk stream to prove liveness and can legitimately take
 * minutes, so only [LLMConfig.timeoutSeconds] applies.
 *
 * Retries live one layer up in `StrategyContext.callLLM`, which owns the
 * retry budget.
 */
const val LLM_ATTEMPT_TIMEOUT_MS: Long = 30_000L

/**
 * Base class for all langchain4j-backed [AkibaLLMClient] implementations.
 *
 * Subclasses initialise [chatModel] and [streamingModel] (passing
 * [httpClientBuilder] so [close] can release the connection pool) and set
 * [providerTag]; [supportsToolCalling] is overridden when the provider has no
 * tools. When [tools] are supplied they reach the provider as langchain4j
 * [ToolSpecification]s, and [AiMessage.toolExecutionRequests] come back as
 * [ChatCompletion.toolCalls].
 */
abstract class AbstractLangChainAdapter(
    override val config: LLMConfig
) : AkibaLLMClient {

    /** The langchain4j synchronous chat model – must be initialized by subclass. */
    protected abstract val chatModel: ChatModel

    /** The langchain4j streaming chat model – must be initialized by subclass. */
    protected abstract val streamingModel: StreamingChatModel

    /** Short tag used in log messages (e.g. "OpenAI", "Gemini"). */
    protected abstract val providerTag: String

    /**
     * Logger for adapter-level messages (chat requests, connection
     * lifecycle, errors).  Defaults to the per-class logger;
     * [org.iotsplab.akiba.llm.agent.module.AgentModule] swaps in the module
     * logger after client creation so these lines land in the module's log
     * file.  Assignment happens before any chat call, so a plain `var` is
     * enough.
     */
    var logger: org.apache.logging.log4j.Logger = LogManager.getLogger(this::class.java)
        internal set

    /**
     * JDK HTTP clients created through [httpClientBuilder], kept so [close]
     * can shut their connection pools down.
     */
    private val trackedHttpClients = java.util.concurrent.CopyOnWriteArrayList<HttpClient>()

    /**
     * A model builder argument that records every JDK [HttpClient] langchain4j
     * creates from it.
     *
     * langchain4j's own `HttpClient` SPI has no `close()`, so without this the
     * underlying `java.net.http.HttpClient` (1200 s keep-alive) outlives the
     * session and holds the provider's per-IP connection budget hostage — the
     * "20-30 minute freeze" class of bug.  Subclasses pass this to
     * `.httpClientBuilder(...)` on each model builder.
     */
    protected fun httpClientBuilder(): HttpClientBuilder =
        TrackingHttpClientBuilder(JdkHttpClientBuilder(), trackedHttpClients::add)

    /**
     * Transport timeout for the **streaming** model builders.
     *
     * Capped at [LLM_ATTEMPT_TIMEOUT_MS]: for SSE this is the per-read timeout,
     * i.e. "30 s without a chunk = stalled", which must never outlive the
     * attempt that owns it.
     */
    protected fun streamTimeout(): Duration {
        val secs = minOf(config.timeoutSeconds, (LLM_ATTEMPT_TIMEOUT_MS / 1000).toInt())
            .coerceAtLeast(1)
        return Duration.ofSeconds(secs.toLong())
    }

    /**
     * Transport timeout for the **one-shot** chat model.
     *
     * NOT capped at 30 s: a single completion (summary compaction, BTW
     * follow-up) can legitimately take minutes, and there is no chunk stream
     * to prove liveness.  Bounded by [LLMConfig.timeoutSeconds] only.
     */
    protected fun chatTimeout(): Duration =
        Duration.ofSeconds(config.timeoutSeconds.coerceAtLeast(1).toLong())

    private fun <T> runWithHardTimeout(label: String, timeoutMs: Long, block: () -> T): T {
        val budget = timeoutMs.coerceAtLeast(1)
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "akiba-llm-$providerTag-$label-timeout").apply { isDaemon = true }
        }
        val future = executor.submit<T> { block() }
        return try {
            future.get(budget, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw LLMTimeoutException(
                "$providerTag $label timed out after ${budget}ms " +
                    "(model=${config.modelName})"
            )
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            when (cause) {
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw RuntimeException(cause)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    // ============================================================
    //  Chat (sync)
    // ============================================================

    override fun chat(
        systemPrompt: String?,
        messages: List<org.iotsplab.akiba.llm.memory.AgentChatMessage>,
        tools: List<ToolSpecification>?
    ): ChatCompletion {
        val lcMessages = toLangChainMessages(systemPrompt, messages)
        if (config.debugLogging) {
            logger.info("[$providerTag] chat request: ${lcMessages.size} messages, model=${config.modelName}")
        }

        val response: ChatResponse = runWithHardTimeout("chat", config.timeoutSeconds * 1000L) {
            if (!tools.isNullOrEmpty() && supportsToolCalling()) {
                val request = ChatRequest.builder()
                    .messages(lcMessages)
                    .toolSpecifications(tools)
                    .build()
                chatModel.chat(request)
            } else {
                chatModel.chat(lcMessages)
            }
        }

        return toChatCompletion(response)
    }

    // ============================================================
    //  Chat (streaming)
    // ============================================================

    override fun chatStream(
        systemPrompt: String?,
        messages: List<org.iotsplab.akiba.llm.memory.AgentChatMessage>,
        tools: List<ToolSpecification>?
    ): Flow<ChatChunk> = flow {
        val lcMessages = toLangChainMessages(systemPrompt, messages)

        // Channel that bridges the langchain4j callback world (push) into
        // the Kotlin Flow world (pull).  Use UNLIMITED capacity: the
        // consumer is cheap (just appends to a StringBuilder), so we
        // don't want `trySend` to silently drop chunks when the producer
        // fires faster than the consumer can drain.  A previous 64-slot
        // cap caused exactly that bug: while the consumer was blocked
        // on a slow DB write, the channel filled and the user saw only
        // the first few chunks in the preview before the whole response
        // dumped at the end.
        val chunkChannel = kotlinx.coroutines.channels.Channel<String>(
            capacity = kotlinx.coroutines.channels.Channel.UNLIMITED
        )
        val errorRef = AtomicReference<Throwable?>(null)
        val completionRef = AtomicReference<ChatResponse?>(null)
        val doneLatch = CountDownLatch(1)
        // Updated by ANY streaming activity (content OR thinking deltas).
        // The watchdog below keys off this instead of content chunks only:
        // thinking models (e.g. deepseek-v4-flash) can stream minutes of
        // reasoning_content before the first content token, and those
        // deltas arrive via onPartialThinking, not onPartialResponse.
        val lastActivityAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

        // Sent through the same channel as content deltas so the consumer
        // wakes the moment the provider finishes or errors, instead of
        // discovering it at the next probe timeout (that lag used to delay the
        // terminal chunk — and the "stream closed" signal to the UI — by up to
        // 5 s after the last content token).
        val terminalSentinel = "\u0000akiba:stream-terminal\u0000"

        // Emit any deltas queued behind the completion signal, skipping the
        // sentinel itself.  Declared locally so it shares the collector.
        suspend fun drainPendingDeltas() {
            while (true) {
                val rest = chunkChannel.tryReceive().getOrNull() ?: break
                if (rest != terminalSentinel) {
                    emit(ChatChunk(delta = rest, isComplete = false))
                }
            }
        }

        val handler = object : StreamingChatResponseHandler {
            override fun onPartialResponse(partial: String) {
                lastActivityAt.set(System.currentTimeMillis())
                chunkChannel.trySend(partial)
            }

            override fun onPartialThinking(thinking: PartialThinking) {
                lastActivityAt.set(System.currentTimeMillis())
            }

            override fun onPartialThinking(thinking: PartialThinking, context: PartialThinkingContext) {
                lastActivityAt.set(System.currentTimeMillis())
            }

            override fun onCompleteResponse(response: ChatResponse) {
                completionRef.set(response)
                chunkChannel.trySend(terminalSentinel)
                doneLatch.countDown()
            }

            override fun onError(error: Throwable) {
                errorRef.set(error)
                chunkChannel.trySend(terminalSentinel)
                doneLatch.countDown()
            }
        }

        // Kick off the streaming call in a hard-timeout wrapper so a
        // stuck *initial* connection (no first chunk ever) still times
        // out within LLM_ATTEMPT_TIMEOUT_MS.
        runWithHardTimeout("stream-start", LLM_ATTEMPT_TIMEOUT_MS) {
            if (!tools.isNullOrEmpty() && supportsToolCalling()) {
                val request = ChatRequest.builder()
                    .messages(lcMessages)
                    .toolSpecifications(tools)
                    .build()
                streamingModel.chat(request, handler)
            } else {
                streamingModel.chat(lcMessages, handler)
            }
        }

        // Stall watchdog: if NO streaming activity (content or thinking
        // deltas) arrives for LLM_ATTEMPT_TIMEOUT_MS, the stream is dead
        // (provider throttling / network half-open) — treat it as a
        // timeout so the retry budget kicks in.  There is deliberately no
        // overall time cap: thinking models can legitimately stream for
        // many minutes, and any activity proves the stream is alive.
        // Short probe interval so completion/error signals are picked up
        // promptly even when no content chunks flow.
        val stallTimeoutMs = LLM_ATTEMPT_TIMEOUT_MS
        // Probe interval only bounds how late a *silent* stream's completion is
        // noticed; the terminal sentinel wakes us immediately in the common
        // case.  1 s keeps the watchdog alarm close to stallTimeoutMs.
        val probeMs = 1_000L

        try {
            while (true) {
                val idleForMs = System.currentTimeMillis() - lastActivityAt.get()
                if (idleForMs >= stallTimeoutMs) {
                    throw LLMTimeoutException(
                        "$providerTag streaming chat stalled: no stream activity for ${stallTimeoutMs}ms " +
                            "(model=${config.modelName})"
                    )
                }
                val chunk = kotlinx.coroutines.withTimeoutOrNull(
                    minOf(probeMs, stallTimeoutMs - idleForMs)
                ) {
                    chunkChannel.receive()
                }
                if (chunk == null) {
                    // Probe window elapsed.  If the completion latch
                    // fired we're done; otherwise re-check liveness at
                    // the top of the loop.
                    if (doneLatch.count == 0L) break
                    continue
                }
                if (chunk == terminalSentinel) {
                    drainPendingDeltas()
                    break
                }
                // Got a chunk — emit it as an incremental delta.
                emit(ChatChunk(delta = chunk, isComplete = false))
                // Check if the producer signalled completion while we
                // were emitting.  If so, drain any remaining queued
                // chunks and exit.
                if (doneLatch.count == 0L) {
                    drainPendingDeltas()
                    break
                }
            }
        } finally {
            chunkChannel.close()
        }

        errorRef.get()?.let { throw RuntimeException("$providerTag streaming error", it) }

        val finalResponse = completionRef.get()
        val usage = finalResponse?.tokenUsage()
        // Extract native tool calls from the final response.  langchain4j's
        // streaming API assembles the full tool-call list only at completion
        // time, so we surface it on the final chunk.
        val nativeToolCalls = finalResponse?.aiMessage()?.toolExecutionRequests()?.map { req ->
            NativeToolCall(
                id = req.id() ?: "tc_${System.nanoTime()}",
                name = req.name(),
                argumentsJson = req.arguments() ?: "{}"
            )
        } ?: emptyList()
        emit(ChatChunk(
            delta = "",
            isComplete = true,
            tokenUsage = usage?.let {
                TokenUsage(
                    inputTokenCount = it.inputTokenCount(),
                    outputTokenCount = it.outputTokenCount(),
                    totalTokenCount = it.totalTokenCount()
                )
            },
            finishReason = finalResponse?.finishReason()?.name?.lowercase(),
            toolCalls = nativeToolCalls
        ))
    }

    // ============================================================
    //  Common helpers
    // ============================================================

    protected fun toLangChainMessages(
        systemPrompt: String?,
        messages: List<org.iotsplab.akiba.llm.memory.AgentChatMessage>
    ): MutableList<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        if (systemPrompt != null) {
            result.add(SystemMessage.from(systemPrompt))
        }
        for (msg in messages) {
            when (msg.role.lowercase()) {
                "user" -> result.add(UserMessage.from(msg.content))
                "assistant" -> result.add(AiMessage.from(msg.content))
                // Tool results are presented as user messages, not
                // ToolExecutionResultMessage: the framework flattens
                // native tool calls into assistant text before
                // persisting, so no assistant message in memory ever
                // carries structured tool_calls.  A role=tool message
                // would therefore be orphaned and strict
                // OpenAI-compatible providers reject the whole request
                // (e.g. DeepSeek: "Messages with role 'tool' must be a
                // response to a preceding message with 'tool_calls'").
                "tool" -> result.add(UserMessage.from(msg.content))
                else -> result.add(UserMessage.from(msg.content))
            }
        }
        return result
    }

    protected fun toChatCompletion(response: ChatResponse): ChatCompletion {
        val aiMessage = response.aiMessage()
        val usage = response.tokenUsage()

        // Extract native tool calls from the response
        val nativeToolCalls = aiMessage?.toolExecutionRequests()?.map { req ->
            NativeToolCall(
                id = req.id() ?: "tc_${System.nanoTime()}",
                name = req.name(),
                argumentsJson = req.arguments() ?: "{}"
            )
        } ?: emptyList()

        val finishReason = response.finishReason()?.name?.lowercase()

        return ChatCompletion(
            content = aiMessage?.text() ?: "",
            tokenUsage = usage?.let {
                TokenUsage(
                    inputTokenCount = it.inputTokenCount(),
                    outputTokenCount = it.outputTokenCount(),
                    totalTokenCount = it.totalTokenCount()
                )
            },
            model = response.modelName() ?: config.modelName,
            finishReason = finishReason,
            toolCalls = nativeToolCalls
        )
    }

    override fun close() {
        // langchain4j wraps a JDK java.net.http.HttpClient inside each
        // model; its connection pool has a 1200 s default keep-alive.
        // Without an explicit shutdown, pools linger after the session
        // ends and exhaust the provider's per-IP connection budget.
        //
        // Every client created through [httpClientBuilder] was recorded, so
        // this is a direct shutdown of known instances — no object-graph
        // walking, nothing that silently breaks when langchain4j rearranges
        // its internals.
        var closed = 0
        for (client in trackedHttpClients) {
            try {
                client.shutdownNow()
                closed++
            } catch (e: Exception) {
                logger.debug("[$providerTag] shutdownNow() failed: ${e.message}")
            }
        }
        trackedHttpClients.clear()
        if (closed > 0) {
            logger.info("[$providerTag] close(): shut down $closed underlying JDK HttpClient(s), released connection pools")
        } else if (config.debugLogging) {
            logger.debug("[$providerTag] close(): no tracked JDK HttpClient (provider may manage its own pool)")
        }
    }
}

/**
 * [HttpClientBuilder] decorator that reports every client it builds.
 *
 * langchain4j creates the concrete client internally (`JdkHttpClient` wraps a
 * `java.net.http.HttpClient`) and its SPI exposes no `close()`, so we read the
 * one field we need off the object we just built.  If a future langchain4j
 * release renames it, [delegateField] resolves to null and shutdown is simply
 * skipped — the failure mode is "pool lingers", never "close() throws".
 */
private class TrackingHttpClientBuilder(
    private val delegate: HttpClientBuilder,
    private val onBuilt: (HttpClient) -> Unit,
) : HttpClientBuilder {

    override fun connectTimeout(): Duration? = delegate.connectTimeout()

    override fun connectTimeout(timeout: Duration): HttpClientBuilder {
        delegate.connectTimeout(timeout)
        return this
    }

    override fun readTimeout(): Duration? = delegate.readTimeout()

    override fun readTimeout(timeout: Duration): HttpClientBuilder {
        delegate.readTimeout(timeout)
        return this
    }

    override fun build(): dev.langchain4j.http.client.HttpClient {
        val client = delegate.build()
        delegateField?.let { field ->
            try {
                (field.get(client) as? HttpClient)?.let(onBuilt)
            } catch (_: Throwable) {
                // Inaccessible field — skip tracking, keep the client working.
            }
        }
        return client
    }

    private companion object {
        val delegateField: java.lang.reflect.Field? by lazy {
            runCatching {
                Class.forName("dev.langchain4j.http.client.jdk.JdkHttpClient")
                    .getDeclaredField("delegate")
                    .apply { isAccessible = true }
            }.getOrNull()
        }
    }
}
