package org.iotsplab.akiba.llm.client

import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.anthropic.AnthropicChatModel
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel
import java.time.Duration

/**
 * [AkibaLLMClient] adapter for the **Anthropic Claude** API.
 *
 * Supports claude-opus-4-7, claude-sonnet-4-6, claude-haiku-4-5, etc.
 */
class AnthropicAdapter(config: LLMConfig) : AbstractLangChainAdapter(config) {

    override val providerTag = "Anthropic"
    override val chatModel: ChatModel
    override val streamingModel: StreamingChatModel

    init {
        val builder = AnthropicChatModel.builder()
            .apiKey(config.apiKey)
            .modelName(config.modelName)
            .timeout(Duration.ofSeconds(config.timeoutSeconds.toLong()))

        config.baseUrl?.let { builder.baseUrl(it) }
        config.temperature?.let { builder.temperature(it) }
        config.topP?.let { builder.topP(it) }
        config.maxTokens?.let { builder.maxTokens(it) }
        config.stopSequences?.let { builder.stopSequences(it) }

        chatModel = builder.build()

        val streamBuilder = AnthropicStreamingChatModel.builder()
            .apiKey(config.apiKey)
            .modelName(config.modelName)
            .timeout(Duration.ofSeconds(config.timeoutSeconds.toLong()))

        config.baseUrl?.let { streamBuilder.baseUrl(it) }
        config.temperature?.let { streamBuilder.temperature(it) }
        config.topP?.let { streamBuilder.topP(it) }
        config.maxTokens?.let { streamBuilder.maxTokens(it) }
        config.stopSequences?.let { streamBuilder.stopSequences(it) }

        streamingModel = streamBuilder.build()
    }
}
