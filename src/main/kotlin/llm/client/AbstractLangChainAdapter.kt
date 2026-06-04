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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import org.apache.logging.log4j.LogManager
import java.util.concurrent.CountDownLatch
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

        val response: ChatResponse = if (!toolSpecs.isNullOrEmpty() && supportsToolCalling()) {
            val request = ChatRequest.builder()
                .messages(lcMessages)
                .toolSpecifications(toolSpecs)
                .build()
            chatModel.chat(request)
        } else {
            chatModel.chat(lcMessages)
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

        val buffer = StringBuilder()
        val errorRef = AtomicReference<Throwable?>(null)
        val latch = CountDownLatch(1)
        var completionResponse: ChatResponse? = null

        if (!toolSpecs.isNullOrEmpty() && supportsToolCalling()) {
            val request = ChatRequest.builder()
                .messages(lcMessages)
                .toolSpecifications(toolSpecs)
                .build()
            streamingModel.chat(request, object : StreamingChatResponseHandler {
                override fun onPartialResponse(partial: String) {
                    synchronized(buffer) { buffer.append(partial) }
                }

                override fun onCompleteResponse(response: ChatResponse) {
                    completionResponse = response
                    latch.countDown()
                }

                override fun onError(error: Throwable) {
                    errorRef.set(error)
                    latch.countDown()
                }
            })
        } else {
            streamingModel.chat(lcMessages, object : StreamingChatResponseHandler {
                override fun onPartialResponse(partial: String) {
                    synchronized(buffer) { buffer.append(partial) }
                }

                override fun onCompleteResponse(response: ChatResponse) {
                    completionResponse = response
                    latch.countDown()
                }

                override fun onError(error: Throwable) {
                    errorRef.set(error)
                    latch.countDown()
                }
            })
        }

        withTimeout(config.timeoutSeconds.toLong() * 1000) {
            while (latch.count > 0) {
                delay(50)
            }
        }

        errorRef.get()?.let { throw RuntimeException("$providerTag streaming error", it) }

        val finishReason = completionResponse?.finishReason()?.name?.lowercase()
        val usage = completionResponse?.tokenUsage()

        emit(ChatChunk(
            delta = buffer.toString(),
            isComplete = true,
            tokenUsage = usage?.let {
                TokenUsage(
                    inputTokenCount = it.inputTokenCount(),
                    outputTokenCount = it.outputTokenCount(),
                    totalTokenCount = it.totalTokenCount()
                )
            },
            finishReason = finishReason
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
        // langchain4j models don't hold closeable resources by default
    }
}
