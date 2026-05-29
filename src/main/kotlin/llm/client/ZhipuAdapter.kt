package org.iotsplab.akiba.llm.client

import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import java.time.Duration

/**
 * [AkibaLLMClient] adapter for **Zhipu AI / ChatGLM** API.
 *
 * Supports glm-5.1 (latest flagship), glm-5, glm-5-turbo,
 * glm-4.7, glm-4.7-flash, glm-4.6, glm-4.5-air, etc.
 * Zhipu provides an OpenAI-compatible API at `https://open.bigmodel.cn/api/paas`.
 */
class ZhipuAdapter(config: LLMConfig) : AbstractLangChainAdapter(config) {

    override val providerTag = "Zhipu"
    override val chatModel: ChatModel
    override val streamingModel: StreamingChatModel

    companion object {
        const val DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
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
