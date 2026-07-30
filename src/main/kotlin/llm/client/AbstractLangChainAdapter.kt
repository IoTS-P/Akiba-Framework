package org.iotsplab.akiba.llm.client

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import dev.langchain4j.model.chat.request.json.JsonStringSchema
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema
import dev.langchain4j.model.chat.request.json.JsonEnumSchema
import dev.langchain4j.model.chat.request.json.JsonSchemaElement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.apache.logging.log4j.LogManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Base class for all langchain4j-backed [AkibaLLMClient] implementations.
 *
 * Subclasses only need to:
 * 1. Initialise [chatModel] and [streamingModel] in their constructor
 * 2. Set [providerTag] for log messages
 * 3. Override [supportsToolCalling] if the provider doesn't support tools
 *
 * All chat/stream/message-conversion logic is handled here.
 *
 * **Native tool calling**: When [tools] are provided, this adapter converts
 * them into langchain4j [ToolSpecification] objects and passes them to the
 * provider via [ChatRequest]. The provider's response may contain
 * [AiMessage.toolExecutionRequests] which are surfaced as
 * [ChatCompletion.toolCalls].
 */
class LLMTimeoutException(message: String) : RuntimeException(message)

/**
 * How long the streaming chat consumer will wait for the *next* chunk
 * before declaring the stream stalled. 45 s is the chosen budget:
 *  - Long enough to tolerate provider-side resource scheduling, where
 *    a long generation can pause mid-stream for a minute or more
 *    before resuming (a pattern the user observed in production —
 *    a 30 s budget killed those recoverable streams and forced a
 *    full regeneration from scratch).
 *  - Still bounded so a genuinely dead connection is detected and
 *    the retry loop kicks in within a reasonable time.
 */
private const val PER_CHUNK_TIMEOUT_MS: Long = 45_000L

abstract class AbstractLangChainAdapter(
    override val config: LLMConfig
) : AkibaLLMClient {

    /** The langchain4j synchronous chat model – must be initialized by subclass. */
    protected abstract val chatModel: ChatModel

    /** The langchain4j streaming chat model – must be initialized by subclass. */
    protected abstract val streamingModel: StreamingChatModel

    /** Short tag used in log messages (e.g. "OpenAI", "Gemini"). */
    protected abstract val providerTag: String

    private val logger = LogManager.getLogger(this::class.java)

    private fun <T> runWithHardTimeout(label: String, block: () -> T): T {
        val timeoutSeconds = config.timeoutSeconds.coerceAtLeast(1)
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "akiba-llm-$providerTag-$label-timeout").apply { isDaemon = true }
        }
        val future = executor.submit<T> { block() }
        return try {
            future.get(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw LLMTimeoutException(
                "$providerTag $label timed out after ${timeoutSeconds}s " +
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
        tools: List<String>?
    ): ChatCompletion {
        val lcMessages = toLangChainMessages(systemPrompt, messages)
        if (config.debugLogging) {
            logger.info("[$providerTag] chat request: ${lcMessages.size} messages, model=${config.modelName}")
        }

        val toolSpecs = tools?.mapNotNull { parseToolSpec(it) }

        val response: ChatResponse = runWithHardTimeout("chat") {
            if (!toolSpecs.isNullOrEmpty() && supportsToolCalling()) {
                val request = ChatRequest.builder()
                    .messages(lcMessages)
                    .toolSpecifications(toolSpecs)
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
        tools: List<String>?
    ): Flow<ChatChunk> = flow {
        val lcMessages = toLangChainMessages(systemPrompt, messages)
        val toolSpecs = tools?.mapNotNull { parseToolSpec(it) }

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

        val handler = object : StreamingChatResponseHandler {
            override fun onPartialResponse(partial: String) {
                // trySend is non-blocking; if the channel is full we drop
                // the chunk (which is OK for UI feedback but bad for the
                // final accumulation).  We therefore also append to a
                // local buffer inside the synchronized block.  Caller
                // uses both — flow for UI, buffer for the final result.
                chunkChannel.trySend(partial)
            }

            override fun onCompleteResponse(response: ChatResponse) {
                completionRef.set(response)
                doneLatch.countDown()
            }

            override fun onError(error: Throwable) {
                errorRef.set(error)
                doneLatch.countDown()
            }
        }

        // Kick off the streaming call in a hard-timeout wrapper so a
        // stuck *initial* connection (no first chunk ever) still times
        // out within `config.timeoutSeconds`.
        runWithHardTimeout("stream-start") {
            if (!toolSpecs.isNullOrEmpty() && supportsToolCalling()) {
                val request = ChatRequest.builder()
                    .messages(lcMessages)
                    .toolSpecifications(toolSpecs)
                    .build()
                streamingModel.chat(request, handler)
            } else {
                streamingModel.chat(lcMessages, handler)
            }
        }

        // Per-chunk timeout: if no new chunk arrives for this long, the
        // stream is stalled (provider throttling / network half-open) —
        // treat it as a timeout so the retry loop kicks in.
        val perChunkTimeoutMs = PER_CHUNK_TIMEOUT_MS
        // Total cap: a single streaming call must complete within this
        // budget, otherwise it's effectively a hang.
        val totalTimeoutMs = config.timeoutSeconds.coerceAtLeast(1) * 1000L
        val overallDeadline = System.currentTimeMillis() + totalTimeoutMs

        var sawCompletion = false
        try {
            while (true) {
                val remainingTotal = overallDeadline - System.currentTimeMillis()
                if (remainingTotal <= 0) {
                    throw LLMTimeoutException(
                        "$providerTag streaming chat total timeout after ${totalTimeoutMs}ms " +
                            "(model=${config.modelName})"
                    )
                }
                val chunk = kotlinx.coroutines.withTimeoutOrNull(
                    minOf(perChunkTimeoutMs, remainingTotal)
                ) {
                    chunkChannel.receive()
                }
                if (chunk == null) {
                    // Either per-chunk timeout fired or the total
                    // deadline hit.  If the completion latch already
                    // fired we're done; otherwise it's a real timeout.
                    if (doneLatch.count == 0L) {
                        sawCompletion = true
                        break
                    }
                    throw LLMTimeoutException(
                        "$providerTag streaming chat stalled: no chunk for ${perChunkTimeoutMs}ms " +
                            "(model=${config.modelName})"
                    )
                }
                // Got a chunk — emit it as an incremental delta.
                emit(ChatChunk(delta = chunk, isComplete = false))
                // Check if the producer signalled completion while we
                // were emitting.  If so, drain any remaining queued
                // chunks and exit.
                if (doneLatch.count == 0L) {
                    // Drain any chunks that arrived between our last
                    // receive and the completion signal.
                    while (true) {
                        val rest = chunkChannel.tryReceive().getOrNull() ?: break
                        emit(ChatChunk(delta = rest, isComplete = false))
                    }
                    sawCompletion = true
                    break
                }
            }
        } finally {
            chunkChannel.close()
        }

        if (!sawCompletion) {
            throw LLMTimeoutException(
                "$providerTag streaming chat did not complete within ${totalTimeoutMs}ms " +
                    "(model=${config.modelName})"
            )
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
                "tool" -> result.add(
                    ToolExecutionResultMessage.from(
                        msg.toolCallId ?: "",
                        msg.toolName ?: "",
                        msg.content
                    )
                )
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

    // ============================================================
    //  Tool specification parsing
    // ============================================================

    /**
     * Parse a tool JSON schema string (as produced by [org.iotsplab.akiba.llm.tool.Tool.toJsonSchema])
     * into a langchain4j [ToolSpecification].
     *
     * Expected input format:
     * ```json
     * {"type":"function","function":{"name":"...","description":"...","parameters":{...}}}
     * ```
     */
    private fun parseToolSpec(jsonSchema: String): ToolSpecification? {
        return try {
            val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            val root = mapper.readTree(jsonSchema)
            val fn = root["function"] ?: return null

            val name = fn["name"]?.asText() ?: return null
            val description = fn["description"]?.asText() ?: ""
            val paramsNode = fn["parameters"]

            val builder = ToolSpecification.builder()
                .name(name)
                .description(description)

            if (paramsNode != null && paramsNode.has("properties")) {
                val schemaBuilder = JsonObjectSchema.builder()
                val properties = paramsNode["properties"]
                val requiredList = paramsNode["required"]?.map { it.asText() } ?: emptyList()

                properties.properties().forEach { (propName, propDef) ->
                    val type = propDef["type"]?.asText() ?: "string"
                    val desc = propDef["description"]?.asText() ?: ""
                    val enumValues = propDef["enum"]?.map { it.asText() }

                    val element: JsonSchemaElement = when {
                        enumValues != null -> JsonEnumSchema.builder()
                            .description(desc)
                            .enumValues(enumValues)
                            .build()
                        type == "integer" || type == "number" -> JsonIntegerSchema.builder()
                            .description(desc)
                            .build()
                        type == "boolean" -> JsonBooleanSchema.builder()
                            .description(desc)
                            .build()
                        else -> JsonStringSchema.builder()
                            .description(desc)
                            .build()
                    }
                    schemaBuilder.addProperty(propName, element)
                }

                if (requiredList.isNotEmpty()) {
                    schemaBuilder.required(requiredList)
                }

                builder.parameters(schemaBuilder.build())
            }

            builder.build()
        } catch (e: Exception) {
            logger.debug("[$providerTag] Failed to parse tool spec: ${e.message}")
            null
        }
    }

    override fun close() {
        // langchain4j 1.15.0 wraps JDK's java.net.http.HttpClient inside each
        // model (chatModel + streamingModel).  The JDK HttpClient keeps its
        // own connection pool with a default keep-alive of **1200 seconds**
        // — exactly the "20-30 minute freeze" the user observed.  Each time
        // `LLMClientFactory.create()` ran we built a new model + new
        // HttpClient + new connection pool, but `close()` was a no-op, so
        // every pool lingered for 20 minutes after the session ended.  The
        // accumulated keep-alive connections eventually throttled the LLM
        // provider's per-IP connection budget, making it respond
        // *extremely* slowly — which in turn looked like a hang.
        //
        // Walk both models reflectively and call `shutdownNow()` on every
        // java.net.http.HttpClient we find.  `shutdownNow()` (JDK 21+)
        // forcibly closes all idle connections in the pool, releasing
        // both local FDs and the provider's per-client connection slots.
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        var closed = 0
        closed += shutdownHttpClients(chatModel, visited)
        closed += shutdownHttpClients(streamingModel, visited)
        if (closed > 0) {
            logger.info("[$providerTag] close(): shut down $closed underlying JDK HttpClient(s), released connection pools")
        }
    }

    /**
     * Recursively walk [root]'s object graph looking for
     * `java.net.http.HttpClient` instances and call
     * [java.net.http.HttpClient.shutdownNow] on each.  Tracks visited
     * objects in [visited] (identity-based) so cyclic references
     * (very common in langchain4j builders) don't loop forever.
     *
     * Returns the number of distinct HttpClient instances shut down.
     *
     * Depth is capped at 6 — langchain4j nests HttpClient roughly
     * 3 levels deep (model → client → httpClient → delegate), so
     * 6 gives us plenty of slack without risking runaway recursion
     * on weird object graphs.
     */
    private fun shutdownHttpClients(root: Any?, visited: MutableSet<Any>, depth: Int = 0): Int {
        if (root == null || depth > 6) return 0
        if (!visited.add(root)) return 0  // already traversed

        // Direct hit
        if (root is java.net.http.HttpClient) {
            return try {
                root.shutdownNow()
                1
            } catch (e: Exception) {
                logger.debug("[$providerTag] shutdownNow() failed: ${e.message}")
                0
            }
        }

        // Don't descend into JDK / Kotlin / Scala / common immutable
        // types — they can't hold an HttpClient and scanning them is
        // slow + can trigger IllegalAccessException on JDK internals.
        val cls = root.javaClass
        val pkg = cls.packageName
        if (pkg.startsWith("java.") || pkg.startsWith("javax.") ||
            pkg.startsWith("kotlin.") || pkg.startsWith("scala.") ||
            cls.isEnum || cls.isPrimitive || cls.isArray && cls.componentType.isPrimitive) {
            return 0
        }

        var count = 0
        // Walk the class hierarchy (langchain4j sometimes puts the
        // field on a package-private abstract super class).
        var k: Class<*>? = cls
        while (k != null && k != Any::class.java) {
            for (field in k.declaredFields) {
                // Skip statics — we must NOT close a shared pool.
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                // Skip synthetic fields (Kotlin's `this$0` etc.) — they
                // point at the outer class and we already visited it.
                if (field.isSynthetic) continue
                try {
                    field.isAccessible = true
                    val value = field.get(root)
                    count += shutdownHttpClients(value, visited, depth + 1)
                } catch (_: IllegalAccessException) {
                    // JDK module system blocks reflection on some fields.
                    // Not fatal — just skip.
                } catch (_: java.lang.reflect.InaccessibleObjectException) {
                    // Same, on JDK 17+.  Skip.
                } catch (_: Exception) {
                    // Field.get can throw for various reasons; keep going.
                }
            }
            k = k.superclass
        }
        return count
    }
}
