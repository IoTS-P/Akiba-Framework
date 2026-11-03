package org.iotsplab.akiba.llm.client.adapter

import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.mistralai.MistralAiChatModel
import dev.langchain4j.model.mistralai.MistralAiStreamingChatModel
import org.iotsplab.akiba.llm.client.AbstractLangChainAdapter
import org.iotsplab.akiba.llm.client.LLMConfig

/**
 * [AkibaLLMClient] adapter for the **Mistral AI** API.
 *
 * Supports mistral-large-3, mistral-medium-3.5, mistral-small-3.2,
 * codestral, magistral, etc.
 * Uses langchain4j's [MistralAiChatModel] and [MistralAiStreamingChatModel].
 */
class MistralAdapter(config: LLMConfig) : AbstractLangChainAdapter(config) {

    override val providerTag = "Mistral"
    override val chatModel: ChatModel
    override val streamingModel: StreamingChatModel

    init {
        val builder = MistralAiChatModel.builder()
            .apiKey(config.apiKey)
            .modelName(config.modelName)
            .timeout(chatTimeout())
            .maxRetries(1)
            .httpClientBuilder(httpClientBuilder())

        config.baseUrl?.let { builder.baseUrl(it) }
        config.temperature?.let { builder.temperature(it) }
        config.topP?.let { builder.topP(it) }
        config.maxTokens?.let { builder.maxTokens(it) }
        config.stopSequences?.let { builder.stopSequences(it) }

        chatModel = builder.build()

        val streamBuilder = MistralAiStreamingChatModel.builder()
            .apiKey(config.apiKey)
            .modelName(config.modelName)
            .timeout(streamTimeout())
            .httpClientBuilder(httpClientBuilder())

        config.baseUrl?.let { streamBuilder.baseUrl(it) }
        config.temperature?.let { streamBuilder.temperature(it) }
        config.topP?.let { streamBuilder.topP(it) }
        config.maxTokens?.let { streamBuilder.maxTokens(it) }
        config.stopSequences?.let { streamBuilder.stopSequences(it) }

        streamingModel = streamBuilder.build()
    }
}
