package org.iotsplab.akiba.llm.client

import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import java.time.Duration

/**
 * [AkibaLLMClient] adapter for any **OpenAI-compatible** API endpoint.
 *
 * This covers self-hosted inference servers that expose the same
 * `/v1/chat/completions` interface as OpenAI, such as:
 * - **vLLM**
 * - **LocalAI**
 * - **LiteLLM** proxy
 * - **Text Generation Inference** (TGI)
 * - Any custom server implementing the OpenAI chat format
 *
 * The key difference from [OpenAIAdapter] is that [baseUrl][LLMConfig.baseUrl]
 * is **required** and the API key may be a placeholder.
 *
 * For specific providers with known endpoints, prefer using their dedicated
 * adapters (e.g. [DeepSeekAdapter], [MoonshotAdapter], [QwenAdapter], [ZhipuAdapter]).
 */
class OpenAICompatibleAdapter(override val config: LLMConfig) : AbstractLangChainAdapter(config) {

    override val providerTag = "OpenAI-Compatible"
    override val chatModel: ChatModel
    override val streamingModel: StreamingChatModel

    init {
        val effectiveBaseUrl = config.baseUrl
            ?: throw IllegalArgumentException(
                "OpenAI-compatible adapter requires a baseUrl (e.g. http://localhost:11434/v1)"
            )

        val builder = OpenAiChatModel.builder()
            .apiKey(config.apiKey.ifBlank { "placeholder" })
            .modelName(config.modelName)
            .baseUrl(effectiveBaseUrl)
            .timeout(Duration.ofSeconds(config.timeoutSeconds.toLong()))

        config.temperature?.let { builder.temperature(it) }
        config.topP?.let { builder.topP(it) }
        config.maxTokens?.let { builder.maxTokens(it) }
        config.stopSequences?.let { builder.stop(it) }

        chatModel = builder.build()

        val streamBuilder = OpenAiStreamingChatModel.builder()
            .apiKey(config.apiKey.ifBlank { "placeholder" })
            .modelName(config.modelName)
            .baseUrl(effectiveBaseUrl)
            .timeout(Duration.ofSeconds(config.timeoutSeconds.toLong()))

        config.temperature?.let { streamBuilder.temperature(it) }
        config.topP?.let { streamBuilder.topP(it) }
        config.maxTokens?.let { streamBuilder.maxTokens(it) }
        config.stopSequences?.let { streamBuilder.stop(it) }

        streamingModel = streamBuilder.build()
    }

    /**
     * OpenAI-compatible endpoints may not support tool calling.
     * Default to false; callers can override via subclassing or
     * [LLMClientFactory.register].
     */
    override fun supportsToolCalling(): Boolean = false
}
