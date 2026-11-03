package org.iotsplab.akiba.llm.client.adapter

import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import org.iotsplab.akiba.llm.client.AbstractLangChainAdapter
import org.iotsplab.akiba.llm.client.LLMConfig
import org.iotsplab.akiba.llm.client.LLMProvider

/**
 * Per-provider defaults for the OpenAI-compatible family: one
 * `/chat/completions` protocol, differing only in base URL and `/v1` policy.
 *
 * @property tag Short name for log messages.
 * @property defaultBaseUrl Endpoint when [LLMConfig.baseUrl] is null; `null` = langchain4j default.
 * @property appendV1IfMissing Keep false for versioned non-`/v1` paths (Zhipu: `/api/paas/v4`).
 * @property supportsToolCalling Whether the provider accepts native tool specs.
 */
internal data class OpenAiFamilyDefaults(
    val tag: String,
    val defaultBaseUrl: String?,
    val appendV1IfMissing: Boolean,
    val supportsToolCalling: Boolean = true,
)

internal object OpenAiFamilyDefaultsTable {

    /**
     * @throws IllegalArgumentException when [provider] is not an
     *         OpenAI-compatible provider (use a native adapter instead).
     */
    fun of(provider: LLMProvider): OpenAiFamilyDefaults = when (provider) {
        LLMProvider.OPEN_AI ->
            OpenAiFamilyDefaults("OpenAI", null, appendV1IfMissing = false)

        LLMProvider.DEEP_SEEK ->
            OpenAiFamilyDefaults("DeepSeek", "https://api.deepseek.com", appendV1IfMissing = true)

        LLMProvider.MOONSHOT ->
            OpenAiFamilyDefaults("Moonshot / Kimi", "https://api.moonshot.cn/v1", appendV1IfMissing = true)

        // Zhipu exposes `/api/paas/v4` — appending `/v1` would 404.
        LLMProvider.ZHIPU ->
            OpenAiFamilyDefaults("Zhipu AI / ChatGLM", "https://open.bigmodel.cn/api/paas/v4", appendV1IfMissing = false)

        LLMProvider.QWEN ->
            OpenAiFamilyDefaults(
                "Qwen / DashScope",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                appendV1IfMissing = true,
            )

        // Self-hosted endpoints (vLLM, LocalAI, LiteLLM, TGI): the user's URL
        // is authoritative and tool support is not assumed.
        LLMProvider.OPEN_AI_COMPATIBLE ->
            OpenAiFamilyDefaults(
                "OpenAI-Compatible",
                null,
                appendV1IfMissing = false,
                supportsToolCalling = false,
            )

        else -> throw IllegalArgumentException(
            "$provider is not an OpenAI-compatible provider; use its native adapter"
        )
    }
}

/**
 * [AkibaLLMClient] for every provider that speaks the OpenAI chat-completions
 * protocol: OpenAI, DeepSeek, Moonshot/Kimi, Zhipu/ChatGLM, Qwen/DashScope and
 * arbitrary OpenAI-compatible endpoints (vLLM, LocalAI, LiteLLM, TGI).
 *
 * The vendor-specific bits live in [OpenAiFamilyDefaultsTable]; this class only
 * builds the two langchain4j models.
 */
class OpenAiFamilyAdapter(config: LLMConfig) : AbstractLangChainAdapter(config) {

    private val defaults = OpenAiFamilyDefaultsTable.of(config.provider)

    override val providerTag: String = defaults.tag

    override val chatModel: ChatModel
    override val streamingModel: StreamingChatModel

    init {
        val effectiveBaseUrl = resolveBaseUrl()
        val apiKey = config.apiKey.ifBlank { "placeholder" }

        val builder = OpenAiChatModel.builder()
            .apiKey(apiKey)
            .modelName(config.modelName)
            .timeout(chatTimeout())
            // Our own retry policy (1 + maxRetries attempts, 3/5/7 s backoff)
            // lives in StrategyContext.callLLM.  Pin langchain4j to a single
            // attempt so the two layers can't multiply into a retry storm.
            .maxRetries(1)
            .httpClientBuilder(httpClientBuilder())

        effectiveBaseUrl?.let { builder.baseUrl(it) }
        config.temperature?.let { builder.temperature(it) }
        config.topP?.let { builder.topP(it) }
        config.maxTokens?.let { builder.maxTokens(it) }
        config.stopSequences?.let { builder.stop(it) }

        chatModel = builder.build()

        val streamBuilder = OpenAiStreamingChatModel.builder()
            .apiKey(apiKey)
            .modelName(config.modelName)
            .timeout(streamTimeout())
            .httpClientBuilder(httpClientBuilder())

        effectiveBaseUrl?.let { streamBuilder.baseUrl(it) }
        config.temperature?.let { streamBuilder.temperature(it) }
        config.topP?.let { streamBuilder.topP(it) }
        config.maxTokens?.let { streamBuilder.maxTokens(it) }
        config.stopSequences?.let { streamBuilder.stop(it) }

        streamingModel = streamBuilder.build()
    }

    private fun resolveBaseUrl(): String? {
        val raw = config.baseUrl ?: defaults.defaultBaseUrl ?: return null
        return if (defaults.appendV1IfMissing && !raw.endsWith("/v1")) "$raw/v1" else raw
    }

    override fun supportsToolCalling(): Boolean = defaults.supportsToolCalling
}
