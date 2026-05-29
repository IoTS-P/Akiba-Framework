package org.iotsplab.akiba.llm.client

import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel
import java.time.Duration

/**
 * [AkibaLLMClient] adapter for the **Google Gemini** API.
 *
 * Supports gemini-3.5-flash, gemini-3.1-pro, gemini-3-flash,
 * gemini-2.5-pro, gemini-2.5-flash, etc.
 * Uses langchain4j's [GoogleAiGeminiChatModel] and [GoogleAiGeminiStreamingChatModel].
 */
class GeminiAdapter(config: LLMConfig) : AbstractLangChainAdapter(config) {

    override val providerTag = "Gemini"
    override val chatModel: ChatModel
    override val streamingModel: StreamingChatModel

    init {
        val builder = GoogleAiGeminiChatModel.builder()
            .apiKey(config.apiKey)
            .modelName(config.modelName)
            .timeout(Duration.ofSeconds(config.timeoutSeconds.toLong()))

        config.baseUrl?.let { builder.baseUrl(it) }
        config.temperature?.let { builder.temperature(it) }
        config.topP?.let { builder.topP(it) }
        config.maxTokens?.let { builder.maxOutputTokens(it) }
        config.stopSequences?.let { builder.stopSequences(it) }

        chatModel = builder.build()

        val streamBuilder = GoogleAiGeminiStreamingChatModel.builder()
            .apiKey(config.apiKey)
            .modelName(config.modelName)
            .timeout(Duration.ofSeconds(config.timeoutSeconds.toLong()))

        config.baseUrl?.let { streamBuilder.baseUrl(it) }
        config.temperature?.let { streamBuilder.temperature(it) }
        config.topP?.let { streamBuilder.topP(it) }
        config.maxTokens?.let { streamBuilder.maxOutputTokens(it) }
        config.stopSequences?.let { streamBuilder.stopSequences(it) }

        streamingModel = streamBuilder.build()
    }
}
