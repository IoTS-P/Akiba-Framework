package org.iotsplab.akiba.llm.client

import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import java.time.Duration

/**
 * [AkibaLLMClient] adapter for **Alibaba Qwen / DashScope** API.
 *
 * Supports qwen3.7-max, qwen3.6-plus, qwen3.6-flash, qwen3.5-omni-plus,
 * qwen3-coder, qwen3-coder-next, etc.
 * DashScope provides an OpenAI-compatible API at
 * `https://dashscope.aliyuncs.com/compatible-mode`.
 *
 * Note: The DashScope OpenAI-compatible mode uses the API key
 * prefixed with `sk-` format (DashScope API key).
 */
class QwenAdapter(config: LLMConfig) : AbstractLangChainAdapter(config) {

    override val providerTag = "Qwen"
    override val chatModel: ChatModel
    override val streamingModel: StreamingChatModel

    companion object {
        const val DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
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
