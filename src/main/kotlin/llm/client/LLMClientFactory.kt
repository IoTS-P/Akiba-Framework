package org.iotsplab.akiba.llm.client

import org.apache.logging.log4j.LogManager

/**
 * Factory that creates the appropriate [AkibaLLMClient] implementation
 * based on [LLMConfig.provider].
 *
 * Usage:
 * ```
 *   val client = LLMClientFactory.create(config)
 * ```
 *
 * To register a custom provider, call [register] before the first
 * `create` invocation:
 * ```
 *   LLMClientFactory.register(LLMProvider.OPEN_AI_COMPATIBLE) { config ->
 *       MyCustomAdapter(config)
 *   }
 * ```
 */
object LLMClientFactory {

    private val logger = LogManager.getLogger(LLMClientFactory::class.java)

    /**
     * Function type that constructs an [AkibaLLMClient] from a [LLMConfig].
     */
    typealias ClientFactory = (LLMConfig) -> AkibaLLMClient

    // ---- Built-in provider registry --------------------------------

    private val registry: MutableMap<LLMProvider, ClientFactory> = mutableMapOf(
        // Native langchain4j providers
        LLMProvider.OPEN_AI          to { config -> OpenAIAdapter(config) },
        LLMProvider.ANTHROPIC        to { config -> AnthropicAdapter(config) },
        LLMProvider.GOOGLE_GEMINI    to { config -> GeminiAdapter(config) },
        LLMProvider.MISTRAL          to { config -> MistralAdapter(config) },
        LLMProvider.OLLAMA           to { config -> OllamaAdapter(config) },
        LLMProvider.AZURE_OPEN_AI    to { config -> AzureOpenAIAdapter(config) },

        // OpenAI-compatible Chinese providers (pre-configured default URLs)
        LLMProvider.DEEP_SEEK        to { config -> DeepSeekAdapter(config) },
        LLMProvider.MOONSHOT         to { config -> MoonshotAdapter(config) },
        LLMProvider.ZHIPU            to { config -> ZhipuAdapter(config) },
        LLMProvider.QWEN             to { config -> QwenAdapter(config) },

        // Generic OpenAI-compatible fallback
        LLMProvider.OPEN_AI_COMPATIBLE to { config -> OpenAICompatibleAdapter(config) }
    )

    /**
     * Register (or replace) the factory function for a given provider.
     */
    fun register(provider: LLMProvider, factory: ClientFactory) {
        registry[provider] = factory
        logger.info("Registered LLM client factory for provider: $provider")
    }

    /**
     * Create an [AkibaLLMClient] from the given [config].
     *
     * @throws IllegalArgumentException if no factory is registered for the provider.
     */
    fun create(config: LLMConfig): AkibaLLMClient {
        val factory = registry[config.provider]
            ?: throw IllegalArgumentException(
                "No LLM client factory registered for provider: ${config.provider}. " +
                    "Available: ${registry.keys}"
            )
        logger.info("Creating LLM client: provider=${config.provider.displayName}, model=${config.modelName}")
        return factory(config)
    }

    /**
     * Convenience: create a client from a property map (e.g. loaded from JSON config).
     *
     * Expected keys:
     * - `provider` (string, required) – one of [LLMProvider] names
     * - `modelName` / `model` (string, required)
     * - `apiKey` / `api_key` (string, required)
     * - `baseUrl` / `base_url` (string, optional)
     * - `temperature` (number, optional)
     * - `topP` / `top_p` (number, optional)
     * - `maxTokens` / `max_tokens` (number, optional)
     * - `timeoutSeconds` / `timeout` (number, optional)
     * - `maxRetries` (number, optional)
     * - `debugLogging` (boolean, optional)
     */
    fun fromMap(props: Map<String, Any?>): AkibaLLMClient {
        val providerStr = (props["provider"] as? String)
            ?: throw IllegalArgumentException("Missing required key: 'provider'")
        val provider = LLMProvider.fromString(providerStr)
            ?: throw IllegalArgumentException("Unknown LLM provider: $providerStr")

        val modelName = (props["modelName"] ?: props["model"]) as? String
            ?: throw IllegalArgumentException("Missing required key: 'modelName' or 'model'")
        val apiKey = (props["apiKey"] ?: props["api_key"]) as? String
            ?: throw IllegalArgumentException("Missing required key: 'apiKey' or 'api_key'")

        val config = LLMConfig(
            provider = provider,
            modelName = modelName,
            apiKey = apiKey,
            baseUrl = (props["baseUrl"] ?: props["base_url"]) as? String,
            temperature = (props["temperature"] as? Number)?.toDouble(),
            topP = ((props["topP"] ?: props["top_p"]) as? Number)?.toDouble(),
            maxTokens = ((props["maxTokens"] ?: props["max_tokens"]) as? Number)?.toInt(),
            stopSequences = (props["stopSequences"] as? List<*>)?.filterIsInstance<String>(),
            timeoutSeconds = ((props["timeoutSeconds"] ?: props["timeout"]) as? Number)?.toInt() ?: 120,
            maxRetries = (props["maxRetries"] as? Number)?.toInt() ?: 3,
            debugLogging = props["debugLogging"] as? Boolean ?: false
        )
        return create(config)
    }

    /**
     * List all registered provider identifiers.
     */
    fun availableProviders(): Set<LLMProvider> = registry.keys.toSet()
}
