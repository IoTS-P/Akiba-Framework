package org.iotsplab.akiba.llm.client

import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.ollama.OllamaChatModel
import dev.langchain4j.model.ollama.OllamaStreamingChatModel
import java.time.Duration

/**
 * [AkibaLLMClient] adapter for **Ollama** local inference.
 *
 * Supports any model available through Ollama (llama4, qwen3.6, qwen3.5,
 * deepseek-v4-flash, deepseek-v4-pro, gemma4, glm-5.1, kimi-k2.6, etc.)
 * using langchain4j's native [OllamaChatModel] and [OllamaStreamingChatModel].
 *
 * The default baseUrl is `http://localhost:11434` (Ollama's default).
 */
class OllamaAdapter(config: LLMConfig) : AbstractLangChainAdapter(config) {

    override val providerTag = "Ollama"
    override val chatModel: ChatModel
    override val streamingModel: StreamingChatModel

    init {
        val effectiveBaseUrl = config.baseUrl ?: "http://localhost:11434"

        val builder = OllamaChatModel.builder()
            .baseUrl(effectiveBaseUrl)
            .modelName(config.modelName)
            .timeout(Duration.ofSeconds(config.timeoutSeconds.toLong()))

        config.temperature?.let { builder.temperature(it) }
        config.topP?.let { builder.topP(it) }
        config.maxTokens?.let { builder.numPredict(it) }

        chatModel = builder.build()

        val streamBuilder = OllamaStreamingChatModel.builder()
            .baseUrl(effectiveBaseUrl)
            .modelName(config.modelName)
            .timeout(Duration.ofSeconds(config.timeoutSeconds.toLong()))

        config.temperature?.let { streamBuilder.temperature(it) }
        config.topP?.let { streamBuilder.topP(it) }
        config.maxTokens?.let { streamBuilder.numPredict(it) }

        streamingModel = streamBuilder.build()
    }

    override fun supportsToolCalling(): Boolean = false
}
