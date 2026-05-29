package org.iotsplab.akiba.llm.client

import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import java.time.Duration

/**
 * [AkibaLLMClient] adapter for **DeepSeek** API.
 *
 * Supports deepseek-v4-flash, deepseek-v4-pro, deepseek-chat (legacy alias
 * for deepseek-v4-flash non-thinking mode), deepseek-reasoner (legacy alias
 * for deepseek-v4-flash thinking mode), etc.
 * DeepSeek provides an OpenAI-compatible API at `https://api.deepseek.com`.
 * Also supports Anthropic-compatible format at `https://api.deepseek.com/anthropic`.
 */
class DeepSeekAdapter(config: LLMConfig) : AbstractLangChainAdapter(config) {

    override val providerTag = "DeepSeek"
    override val chatModel: ChatModel
    override val streamingModel: StreamingChatModel

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
    }

    init {
        val effectiveBaseUrl = (config.baseUrl ?: DEFAULT_BASE_URL).let {
            if (it.endsWith("/v1")) it else "$it/v1"
        }

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
