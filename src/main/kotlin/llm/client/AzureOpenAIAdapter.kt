package org.iotsplab.akiba.llm.client

import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.azure.AzureOpenAiChatModel
import dev.langchain4j.model.azure.AzureOpenAiStreamingChatModel
import java.time.Duration

/**
 * [AkibaLLMClient] adapter for **Azure OpenAI Service**.
 *
 * Supports gpt-5.5, gpt-5.4, gpt-5.4-mini and other OpenAI models deployed on Azure.
 * Uses langchain4j's [AzureOpenAiChatModel] and [AzureOpenAiStreamingChatModel].
 *
 * Azure OpenAI uses a different authentication model (endpoint + key per deployment)
 * rather than a single API key. The [LLMConfig] fields are mapped as follows:
 * - `apiKey` → Azure OpenAI subscription key
 * - `baseUrl` → Azure OpenAI endpoint (e.g. `https://your-resource.openai.azure.com/`)
 * - `modelName` → deployment name
 */
class AzureOpenAIAdapter(config: LLMConfig) : AbstractLangChainAdapter(config) {

    override val providerTag = "Azure-OpenAI"
    override val chatModel: ChatModel
    override val streamingModel: StreamingChatModel

    init {
        val endpoint = config.baseUrl
            ?: throw IllegalArgumentException(
                "Azure OpenAI adapter requires baseUrl (the Azure endpoint URL)"
            )

        val builder = AzureOpenAiChatModel.builder()
            .endpoint(endpoint)
            .apiKey(config.apiKey)
            .deploymentName(config.modelName)
            .timeout(Duration.ofSeconds(config.timeoutSeconds.toLong()))

        config.temperature?.let { builder.temperature(it) }
        config.topP?.let { builder.topP(it) }
        config.maxTokens?.let { builder.maxTokens(it) }
        config.stopSequences?.let { builder.stop(it) }

        chatModel = builder.build()

        val streamBuilder = AzureOpenAiStreamingChatModel.builder()
            .endpoint(endpoint)
            .apiKey(config.apiKey)
            .deploymentName(config.modelName)
            .timeout(Duration.ofSeconds(config.timeoutSeconds.toLong()))

        config.temperature?.let { streamBuilder.temperature(it) }
        config.topP?.let { streamBuilder.topP(it) }
        config.maxTokens?.let { streamBuilder.maxTokens(it) }
        config.stopSequences?.let { streamBuilder.stop(it) }

        streamingModel = streamBuilder.build()
    }
}
