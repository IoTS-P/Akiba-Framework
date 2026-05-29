package org.iotsplab.akiba.llm.client

import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import java.time.Duration

/**
 * [AkibaLLMClient] adapter for **Moonshot AI / Kimi** API.
 *
 * Supports kimi-k2.6, kimi-k2.5 (multimodal, 256K context),
 * moonshot-v1-8k, moonshot-v1-32k, moonshot-v1-128k (classic series).
 * Moonshot provides an OpenAI-compatible API at `https://api.moonshot.cn`.
 */
class MoonshotAdapter(config: LLMConfig) : AbstractLangChainAdapter(config) {

    override val providerTag = "Moonshot"
    override val chatModel: ChatModel
    override val streamingModel: StreamingChatModel

    companion object {
        const val DEFAULT_BASE_URL = "https://api.moonshot.cn/v1"
    }

    init {
        val effectiveBaseUrl = config.baseUrl ?: DEFAULT_BASE_URL

        val builder = OpenAiChatModel.builder()
            .apiKey(config.apiKey)
            .modelName(config.modelName)
            .baseUrl(effectiveBaseUrl)
            .timeout(Duration.ofSeconds(config.timeoutSeconds.toLong()))

        config.temperature?.let { builder.temperature(it) }
        config.topP?.let { builder.topP(it) }
        config.maxTokens?.let { builder.maxTokens(it) }
        config.stopSequences?.let { builder.stop(it) }

        chatModel = builder.build()

        val streamBuilder = OpenAiStreamingChatModel.builder()
            .apiKey(config.apiKey)
            .modelName(config.modelName)
            .baseUrl(effectiveBaseUrl)
            .timeout(Duration.ofSeconds(config.timeoutSeconds.toLong()))

        config.temperature?.let { streamBuilder.temperature(it) }
        config.topP?.let { streamBuilder.topP(it) }
        config.maxTokens?.let { streamBuilder.maxTokens(it) }
        config.stopSequences?.let { streamBuilder.stop(it) }

        streamingModel = streamBuilder.build()
    }
}
