package org.iotsplab.akiba.llm.client

/**
 * Supported LLM provider identifiers.
 *
 * Each entry maps to a concrete [AkibaLLMClient] implementation that knows
 * how to talk to the vendor's API.
 */
enum class LLMProvider(
    /** Human-readable display name. */
    val displayName: String,
    /** Whether this provider uses the OpenAI-compatible protocol under the hood. */
    val isOpenAICompatible: Boolean = false
) {
    /** OpenAI GPT family (gpt-5.5, gpt-5.4, gpt-5.4-mini, …) */
    OPEN_AI("OpenAI"),

    /** Anthropic Claude family (claude-opus-4-7, claude-sonnet-4-6, claude-haiku-4-5, …) */
    ANTHROPIC("Anthropic"),

    /** Google Gemini family (gemini-3.5-flash, gemini-3.1-pro, gemini-2.5-pro, …) */
    GOOGLE_GEMINI("Google Gemini"),

    /** Mistral AI (mistral-large-3, mistral-medium-3.5, mistral-small-3.2, codestral, …) */
    MISTRAL("Mistral AI"),

    /** Ollama local inference (llama4, qwen3.6, deepseek-v4, gemma4, …) */
    OLLAMA("Ollama"),

    /** Azure OpenAI Service (enterprise OpenAI deployment on Azure) */
    AZURE_OPEN_AI("Azure OpenAI"),

    /** DeepSeek (deepseek-v4-flash, deepseek-v4-pro) */
    DEEP_SEEK("DeepSeek", isOpenAICompatible = true),

    /** Moonshot AI / Kimi (kimi-k2.6, kimi-k2.5, moonshot-v1 series) */
    MOONSHOT("Moonshot / Kimi", isOpenAICompatible = true),

    /** Zhipu AI / ChatGLM (glm-5.1, glm-5, glm-4.7, glm-4.6, …) */
    ZHIPU("Zhipu AI / ChatGLM", isOpenAICompatible = true),

    /** Alibaba Qwen / DashScope (qwen3.7-max, qwen3.6-plus, qwen3.6-flash, …) */
    QWEN("Qwen / DashScope", isOpenAICompatible = true),

    /** Any OpenAI-compatible endpoint (vLLM, LocalAI, LiteLLM, etc.) */
    OPEN_AI_COMPATIBLE("OpenAI-Compatible", isOpenAICompatible = true);

    companion object {
        /** Parse from string, case-insensitive. Returns null if unknown. */
        fun fromString(value: String): LLMProvider? =
            entries.firstOrNull { it.name.equals(value.replace("-", "_"), ignoreCase = true) }
    }
}

// ============================================================
//  Configuration data classes
// ============================================================

/**
 * Immutable configuration for creating an [AkibaLLMClient].
 *
 * All optional fields default to null so that callers only specify
 * what the chosen provider actually requires.
 */
data class LLMConfig(
    /** Which provider to use – determines the adapter implementation. */
    val provider: LLMProvider,

    /** Model identifier as recognised by the provider (e.g. "gpt-5.5", "claude-opus-4-7"). */
    val modelName: String,

    /** API key.  For local / self-hosted endpoints this may be an arbitrary placeholder. */
    val apiKey: String,

    /** Base URL override.  When null the provider's default endpoint is used. */
    val baseUrl: String? = null,

    /** Temperature – 0.0 → deterministic, 1.0 → creative.  null = provider default. */
    val temperature: Double? = null,

    /** Top-P nucleus sampling.  null = provider default. */
    val topP: Double? = null,

    /** Maximum tokens the model may generate in a single response. Default 8K. */
    val maxTokens: Int? = 8192,

    /** Stop sequences.  null = no custom stop. */
    val stopSequences: List<String>? = null,

    /** Request timeout in seconds. */
    val timeoutSeconds: Int = 120,

    /** Number of retries on transient failures. */
    val maxRetries: Int = 3,

    /** Whether to log full request/response bodies for debugging. */
    val debugLogging: Boolean = false
)

// ============================================================
//  Response types
// ============================================================

/**
 * Token usage statistics returned by the provider.
 */
data class TokenUsage(
    val inputTokenCount: Int,
    val outputTokenCount: Int,
    val totalTokenCount: Int = inputTokenCount + outputTokenCount
)

/**
 * A native tool call returned by the provider (via function calling protocol).
 */
data class NativeToolCall(
    /** Provider-assigned call ID (for correlating results). */
    val id: String,
    /** Tool name. */
    val name: String,
    /** Raw JSON arguments string as returned by the provider. */
    val argumentsJson: String
)

/**
 * Result of a single chat completion call.
 */
data class ChatCompletion(
    /** The assistant's text reply. */
    val content: String,

    /** Token usage if the provider reports it. */
    val tokenUsage: TokenUsage? = null,

    /** The model name actually used (may differ from requested for aliases). */
    val model: String? = null,

    /** Finish reason (e.g. "stop", "tool_calls", "length"). */
    val finishReason: String? = null,

    /** Native tool calls returned by the provider (empty if none). */
    val toolCalls: List<NativeToolCall> = emptyList()
)

/**
 * A single chunk in a streaming response.
 */
data class ChatChunk(
    /** Incremental text delta. */
    val delta: String,

    /** True if this is the final chunk. */
    val isComplete: Boolean = false,

    /** Token usage (typically available only in the final chunk). */
    val tokenUsage: TokenUsage? = null,

    /** Finish reason – only present in the final chunk. */
    val finishReason: String? = null
)
