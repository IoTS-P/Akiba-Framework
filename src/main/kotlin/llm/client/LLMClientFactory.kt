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

    // ---- Live-client registry (for force-close on shutdown) ------------

    /**
     * Weak set of every [AkibaLLMClient] currently alive in this JVM.
     *
     * Motivation: `AbstractLangChainAdapter.close()` is what actually
     * releases the underlying JDK `HttpClient` connection pool (default
     * keep-alive 1200 s, exactly the "20-30 minute freeze" the user
     * reported).  But `close()` is only invoked if the agent that owns
     * the client reaches its cleanup path — and on JVM shutdown / a
     * crash-loop restart, that path is skipped.  Without a registry,
     * there is no way to force-close every active client at once
     * before exit, and the lingering keep-alive connections hold the
     * provider's per-IP connection budget hostage across restarts.
     *
     * Uses weak references so a leaked client doesn't prevent GC
     * (which would itself leak connections).
     */
    private val liveClients: MutableSet<AkibaLLMClient> =
        java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(java.util.WeakHashMap())
        )

    init {
        Runtime.getRuntime().addShutdownHook(Thread({
            val size = liveClients.size
            if (size > 0) {
                closeAllLiveClients("JVM shutdown hook")
            }
        }, "akiba-llm-client-shutdown"))
    }

    /**
     * Number of clients currently tracked.  Exposed for diagnostics
     * (e.g. an admin endpoint that reports how many LLM connections
     * are open) and for the `/agent/internal/llm/close-all` route.
     */
    fun liveClientCount(): Int = liveClients.size

    /**
     * Force-close every client currently in [liveClients].
     *
     * Safe to call from arbitrary threads; each client's `close()` is
     * expected to be idempotent.  Individual failures are logged at
     * WARN and do not prevent the remaining clients from being closed.
     *
     * Called automatically from a JVM shutdown hook; can also be
     * invoked manually before a rolling restart via the internal
     * route `POST /agent/internal/llm/close-all`.
     *
     * @return the number of clients that were closed successfully.
     */
    fun closeAllLiveClients(reason: String = "manual"): Int {
        // Snapshot to avoid ConcurrentModificationException — clients
        // may be added/removed while we're iterating.
        val snapshot = synchronized(liveClients) { liveClients.toList() }
        if (snapshot.isEmpty()) {
            logger.info("closeAllLiveClients($reason): no live clients to close")
            return 0
        }
        logger.warn("closeAllLiveClients($reason): force-closing ${snapshot.size} live LLM client(s)")
        var closed = 0
        for (client in snapshot) {
            try {
                client.close()
                closed++
            } catch (e: Exception) {
                logger.warn("closeAllLiveClients: failed to close ${client.javaClass.simpleName}: ${e.message}")
            }
        }
        logger.warn("closeAllLiveClients($reason): closed $closed/${snapshot.size} clients")
        return closed
    }

    /**
     * Create an [AkibaLLMClient] from the given [config].
     *
     * The returned client is registered in [liveClients] so it can be
     * force-closed on JVM shutdown / via [closeAllLiveClients].
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
        val client = factory(config)
        liveClients.add(client)
        return client
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
