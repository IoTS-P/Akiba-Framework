package org.iotsplab.akiba.llm.agent

import com.fasterxml.jackson.databind.DeserializationFeature
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.jackson.jackson
import kotlinx.coroutines.*
import org.apache.logging.log4j.LogManager
import org.iotsplab.akiba.llm.client.LLMProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service that fetches and caches model metadata (context length, output length)
 * from the open-source models.dev registry.
 *
 * Data is refreshed every hour in the background. Callers can query context
 * length synchronously from the in-memory cache.
 */
object ModelContextLengthService {

    private val logger = LogManager.getLogger(ModelContextLengthService::class.java)

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            jackson {
                disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            }
        }
    }

    private val cache = ConcurrentHashMap<String, ModelMetadata>()
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val MODELS_DEV_URL = "https://models.dev/models.json"
    private const val REFRESH_INTERVAL_MS = 60 * 60 * 1000L // 1 hour

    data class ModelMetadata(
        val contextLength: Int?,
        val outputLength: Int?
    )

    /** Start the background refresh loop. Safe to call multiple times. */
    fun start() {
        if (started.compareAndSet(false, true)) {
            scope.launch {
                // Initial fetch with a small delay so it doesn't block app startup
                delay(5_000L)
                while (isActive) {
                    try {
                        refresh()
                    } catch (e: Exception) {
                        logger.error("Failed to refresh model metadata: ${e.message}", e)
                    }
                    delay(REFRESH_INTERVAL_MS)
                }
            }
        }
    }

    /** Stop the background refresh loop. */
    fun stop() {
        scope.cancel()
        started.set(false)
    }

    /** Force an immediate refresh. */
    suspend fun refresh() {
        logger.info("Fetching model metadata from $MODELS_DEV_URL")
        val response: Map<String, Map<String, Any?>> = client.get(MODELS_DEV_URL).body()

        val newCache = ConcurrentHashMap<String, ModelMetadata>()
        response.forEach { (key, value) ->
            val limit = value["limit"] as? Map<*, *>
            val context = (limit?.get("context") as? Number)?.toInt()
            val output = (limit?.get("output") as? Number)?.toInt()
            newCache[key] = ModelMetadata(context, output)
        }

        cache.clear()
        cache.putAll(newCache)
        logger.info("Model metadata cache updated with ${cache.size} entries")
    }

    /**
     * Get the maximum context window (in tokens) for the given provider + model.
     *
     * Returns null when the provider is not mapped or the model is not found
     * in the cache.
     */
    fun getContextLength(provider: LLMProvider, modelName: String): Int? {
        val mapped = mapProvider(provider) ?: return null
        return lookup(mapped, modelName)?.contextLength
    }

    /**
     * Get the maximum output length (in tokens) for the given provider + model.
     */
    fun getOutputLength(provider: LLMProvider, modelName: String): Int? {
        val mapped = mapProvider(provider) ?: return null
        return lookup(mapped, modelName)?.outputLength
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private fun lookup(mappedProvider: String, modelName: String): ModelMetadata? {
        val prefix = "$mappedProvider/"

        // 1. Exact match
        cache["$prefix$modelName"]?.let { return it }

        // 2. Suffix match (model part equals modelName exactly)
        val suffixMatch = cache.keys.find { it.startsWith(prefix) && it.substringAfter("/") == modelName }
        suffixMatch?.let { cache[it] }?.let { return it }

        // 3. Contains match (key contains modelName)
        val containsMatch = cache.keys.find { it.startsWith(prefix) && it.contains(modelName, ignoreCase = true) }
        containsMatch?.let { cache[it] }?.let { return it }

        // 4. Reverse contains (modelName contains key's model part)
        val reverseMatch = cache.keys.find {
            it.startsWith(prefix) && modelName.contains(it.substringAfter("/"), ignoreCase = true)
        }
        reverseMatch?.let { cache[it] }?.let { return it }

        return null
    }

    private fun mapProvider(provider: LLMProvider): String? = when (provider) {
        LLMProvider.OPEN_AI -> "openai"
        LLMProvider.ANTHROPIC -> "anthropic"
        LLMProvider.GOOGLE_GEMINI -> "google"
        LLMProvider.MISTRAL -> "mistral"
        LLMProvider.AZURE_OPEN_AI -> "openai"
        LLMProvider.DEEP_SEEK -> "deepseek"
        LLMProvider.MOONSHOT -> "moonshot"
        LLMProvider.ZHIPU -> "zhipuai"
        LLMProvider.QWEN -> "alibaba"
        LLMProvider.OLLAMA -> null
        LLMProvider.OPEN_AI_COMPATIBLE -> null
    }
}
