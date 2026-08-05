package org.iotsplab.akiba.llm.agent

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.jackson.jackson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.LogManager
import org.iotsplab.akiba.llm.client.LLMProvider
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Service that fetches and caches model metadata (context length, output length)
 * from the open-source models.dev registry.
 *
 * Data is saved to `~/.akiba/open_model_info_<datetime>.json` where datetime
 * is the fetch timestamp in `yyyyMMddHHmmss` format. On lookup, if the file is
 * older than 1 hour or the model is not found, a fresh fetch is triggered.
 */
object ModelContextLengthService {

    private val logger = LogManager.getLogger(ModelContextLengthService::class.java)
    private val mapper = jacksonObjectMapper()

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            jackson {
                disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            }
        }
        // Hard timeouts: the registry may be unreachable (blocked
        // egress / poisoned DNS) — a hanging fetch must not stall the
        // caller (this service is queried on the messages poll path).
        install(HttpTimeout) {
            connectTimeoutMillis = 3_000
            requestTimeoutMillis = 5_000
            socketTimeoutMillis = 5_000
        }
    }

    private val akibaDir: File by lazy {
        File(System.getProperty("user.home"), ".akiba").also {
            if (!it.exists() && !it.mkdirs()) {
                logger.warn("Failed to create ~/.akiba directory")
            }
        }
    }

    private val modelInfoPrefix = "open_model_info_"

    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    private const val MODELS_DEV_URL = "https://models.dev/models.json"

    private const val STALE_THRESHOLD_HOURS = 1L

    /**
     * Last fetch ATTEMPT time (epoch millis), regardless of outcome.
     * A failed/unreachable registry must not be retried on every call
     * (this service sits on the messages poll path) — attempts are
     * spaced at least [STALE_THRESHOLD_HOURS] apart.
     */
    @Volatile
    private var lastFetchAttemptMs: Long = 0L

    data class ModelMetadata(
        val contextLength: Int?,
        val outputLength: Int?
    )

    /**
     * Get the maximum context window (in tokens) for the given provider + model.
     *
     * Mapped providers search only their own catalog section; unmapped
     * providers (OPEN_AI_COMPATIBLE, OLLAMA) fall back to matching the
     * model name across the WHOLE catalog — the provider prefix is only
     * a key namespace, it does not affect the fetched data.
     *
     * Returns null when the model is not found (or data is unavailable).
     */
    fun getContextLength(provider: LLMProvider, modelName: String): Int? {
        val mapped = mapProvider(provider)
        return lookupWithRefresh(mapped, modelName)?.contextLength
    }

    /**
     * Get the maximum output length (in tokens) for the given provider + model.
     */
    fun getOutputLength(provider: LLMProvider, modelName: String): Int? {
        val mapped = mapProvider(provider)
        return lookupWithRefresh(mapped, modelName)?.outputLength
    }

    // ------------------------------------------------------------------
    // Internal implementation
    // ------------------------------------------------------------------

    /**
     * Look up model metadata, refreshing the local cache if the file is
     * stale or the model is not found.
     *
     * @param mappedProvider Catalog key prefix (e.g. "openai"), or null
     *        to search the whole catalog by model name alone.
     */
    private fun lookupWithRefresh(mappedProvider: String?, modelName: String): ModelMetadata? {
        val (data, fileDatetime, stale) = loadModelInfoFile()

        // Try searching with current data
        val found = lookupIn(data, mappedProvider, modelName)
        if (found != null) return found

        // Not found — re-fetch when the file is missing or stale, but
        // at most once per STALE_THRESHOLD (a failed attempt counts too,
        // so an unreachable registry can't stall every lookup).
        val needsFetch = (fileDatetime == null || stale) &&
            (System.currentTimeMillis() - lastFetchAttemptMs) >= STALE_THRESHOLD_HOURS * 3600_000L
        if (needsFetch) {
            lastFetchAttemptMs = System.currentTimeMillis()
            logger.info("Model metadata stale or missing for ${mappedProvider ?: "*"}/$modelName, fetching fresh data")
            val freshData = try {
                runBlocking { fetchAndSave() }
            } catch (e: Exception) {
                logger.warn("Failed to fetch model metadata: ${e.message}")
                return null
            }
            return lookupIn(freshData, mappedProvider, modelName)
        }

        // File is recent but model not in it
        logger.debug("Model ${mappedProvider ?: "*"}/$modelName not found in fresh metadata")
        return null
    }

    /**
     * Fetch model metadata from the remote URL and save to
     * `~/.akiba/open_model_info_<now>.json`, cleaning up old files.
     */
    private suspend fun fetchAndSave(): Map<String, ModelMetadata> {
        logger.info("Fetching model metadata from $MODELS_DEV_URL")
        val response: Map<String, Map<String, Any?>> = client.get(MODELS_DEV_URL).body()

        val result = mutableMapOf<String, ModelMetadata>()
        response.forEach { (key, value) ->
            val limit = value["limit"] as? Map<*, *>
            val context = (limit?.get("context") as? Number)?.toInt()
            val output = (limit?.get("output") as? Number)?.toInt()
            result[key] = ModelMetadata(context, output)
        }

        // Save to file with current datetime
        val now = LocalDateTime.now().format(dateTimeFormatter)
        val file = File(akibaDir, "${modelInfoPrefix}$now.json")
        mapper.writerWithDefaultPrettyPrinter().writeValue(file, result)
        logger.info("Model metadata saved to ${file.name} (${result.size} models)")

        // Clean up old model info files (keep only the newest)
        cleanupOldFiles(file)

        return result
    }

    /** Delete all `open_model_info_*` files except [keep]. */
    private fun cleanupOldFiles(keep: File) {
        akibaDir.listFiles { f -> f.name.startsWith(modelInfoPrefix) }
            ?.filter { it != keep }
            ?.forEach { it.delete() }
    }

    /**
     * Load the most recent model info file from disk.
     *
     * Returns (data map, datetime string, isStale)
     */
    private fun loadModelInfoFile(): Triple<Map<String, ModelMetadata>, String?, Boolean> {
        val files = akibaDir.listFiles { f -> f.name.startsWith(modelInfoPrefix) }
            ?.sortedByDescending { it.name }
            ?.takeIf { it.isNotEmpty() }
            ?: return Triple(emptyMap(), null, false)

        val latest = files.first()
        val datetime = latest.name.removePrefix(modelInfoPrefix).removeSuffix(".json")
        val stale = isOlderThan(datetime, STALE_THRESHOLD_HOURS)

        return try {
            val raw: Map<String, Map<String, Any?>> = mapper.readValue(latest)
            val parsed = mutableMapOf<String, ModelMetadata>()
            raw.forEach { (key, value) ->
                parsed[key] = ModelMetadata(
                    contextLength = (value["contextLength"] as? Number)?.toInt(),
                    outputLength = (value["outputLength"] as? Number)?.toInt()
                )
            }
            Triple(parsed, datetime, stale)
        } catch (e: Exception) {
            logger.warn("Failed to parse model info file ${latest.name}: ${e.message}")
            Triple(emptyMap(), datetime, true) // treat parse failure as stale
        }
    }

    /** Check if the datetime string is older than [hours]. */
    private fun isOlderThan(datetime: String, hours: Long): Boolean {
        return try {
            val fileTime = LocalDateTime.parse(datetime, dateTimeFormatter)
            Duration.between(fileTime, LocalDateTime.now()).toHours() >= hours
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Search for a model in the given data map. When [mappedProvider]
     * is null, matches by model name across ALL provider prefixes
     * (for OpenAI-compatible gateways whose upstream is unknown).
     */
    private fun lookupIn(
        data: Map<String, ModelMetadata>,
        mappedProvider: String?,
        modelName: String
    ): ModelMetadata? {
        if (mappedProvider == null) return lookupByModelName(data, modelName)
        val prefix = "$mappedProvider/"

        // 1. Exact match
        data["$prefix$modelName"]?.let { return it }

        // 2. Suffix match (the part after "/" equals modelName exactly)
        val suffixMatch = data.keys.find { it.startsWith(prefix) && it.substringAfter("/") == modelName }
        suffixMatch?.let { return data[it] }

        // 3. Contains match (key contains modelName, case-insensitive)
        val containsMatch = data.keys.find { it.startsWith(prefix) && it.contains(modelName, ignoreCase = true) }
        containsMatch?.let { return data[it] }

        // 4. Reverse contains (modelName contains key's model part)
        val reverseMatch = data.keys.find {
            it.startsWith(prefix) && modelName.contains(it.substringAfter("/"), ignoreCase = true)
        }
        reverseMatch?.let { return data[it] }

        return null
    }

    /**
     * Catalog-wide lookup by model name alone, for providers without a
     * mapped prefix (OPEN_AI_COMPATIBLE gateways, OLLAMA). Prefers the
     * strongest match: exact model-part match, then contains, then
     * reverse-contains.
     */
    private fun lookupByModelName(data: Map<String, ModelMetadata>, modelName: String): ModelMetadata? {
        // 1. Exact match on the part after "<provider>/"
        data.entries.firstOrNull { it.key.substringAfter("/") == modelName }
            ?.let { return it.value }
        // 2. Key contains modelName (case-insensitive)
        data.entries.firstOrNull { it.key.contains(modelName, ignoreCase = true) }
            ?.let { return it.value }
        // 3. modelName contains the key's model part
        data.entries.firstOrNull {
            val modelPart = it.key.substringAfter("/")
            modelPart.isNotEmpty() && modelName.contains(modelPart, ignoreCase = true)
        }?.let { return it.value }
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
