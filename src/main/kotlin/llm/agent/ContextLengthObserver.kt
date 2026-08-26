package org.iotsplab.akiba.llm.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.apache.logging.log4j.LogManager
import org.iotsplab.akiba.llm.client.LLMProvider
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// ============================================================
//  ContextLengthObserver — empirical context-window lower bound
// ============================================================

/**
 * Tracks the **empirically proven** context-window lower bound per
 * model: the largest prompt size (in tokens) that the provider has
 * actually accepted. For models whose context window is unknown
 * (unlisted in `ModelContextLengthService`, e.g. OpenAI-compatible
 * gateways), this is the only window information available.
 *
 * Heuristic contract:
 * - **Only successes raise the bound.** A failed call NEVER lowers it
 *   (the failure may be transient — network, throttling — not an
 *   overflow), so the value is never "pinned" by failures.
 * - The bound is a *lower* bound of the true window: a prompt of this
 *   size succeeded before, so the true limit is ≥ bound.
 * - Persisted to `~/.akiba/model_context_bounds.json` so subsequent
 *   pipeline runs (same model) start from the previous observation.
 */
object ContextLengthObserver {

    private val logger = LogManager.getLogger(ContextLengthObserver::class.java)
    private val mapper = jacksonObjectMapper()

    private val boundsFile: File by lazy {
        File(System.getProperty("user.home"), ".akiba/model_context_bounds.json")
    }

    private data class BoundEntry(
        val bound: Int,
        val updatedAt: Long,
        /**
         * Exact window size stated by the provider's overflow error
         * ("maximum context length is N tokens"), when one was seen.
         * Unlike [bound] (a proven-safe lower bound), this is the true
         * window — providers count prompt + requested output tokens
         * against it.
         */
        val providerLimit: Int? = null,
    )

    /** Key: "<provider>/<modelName>". */
    private val bounds = ConcurrentHashMap<String, BoundEntry>()

    @Volatile
    private var loaded = false

    /** Minimum increase required to trigger a file rewrite (write throttling). */
    private const val PERSIST_MIN_DELTA = 128

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!boundsFile.exists()) return
        try {
            val raw: Map<String, Map<String, Any?>> = mapper.readValue(boundsFile)
            raw.forEach { (key, value) ->
                val bound = (value["bound"] as? Number)?.toInt()
                val updatedAt = (value["updatedAt"] as? Number)?.toLong() ?: 0L
                val providerLimit = (value["providerLimit"] as? Number)?.toInt()
                if (bound != null && bound > 0) bounds[key] = BoundEntry(bound, updatedAt, providerLimit)
            }
            logger.info("Loaded observed context bounds for ${bounds.size} model(s) from ${boundsFile.name}")
        } catch (e: Exception) {
            logger.warn("Failed to parse ${boundsFile.name}: ${e.message} (starting empty)")
        }
    }

    private fun key(provider: LLMProvider, modelName: String) = "${provider.name}/$modelName"

    /**
     * Record a successful LLM call's prompt size. Raises the model's
     * observed lower bound when this prompt is the largest accepted so far.
     */
    fun recordSuccess(provider: LLMProvider, modelName: String, promptTokens: Int) {
        if (promptTokens <= 0) return
        ensureLoaded()
        val k = key(provider, modelName)
        val previous = bounds[k]
        if (previous != null && previous.bound >= promptTokens) return
        bounds[k] = BoundEntry(promptTokens, System.currentTimeMillis(), previous?.providerLimit)
        if (previous == null || promptTokens - previous.bound >= PERSIST_MIN_DELTA) {
            persist()
        }
    }

    /**
     * Record the exact window size stated by a provider's overflow
     * error. This is precise ground truth, so it is kept even when it
     * is below the current lower bound (the lower bound was recorded
     * from a prompt the provider counted differently, or the provider
     * changed its serving configuration).
     */
    fun recordProviderLimit(provider: LLMProvider, modelName: String, limit: Int) {
        if (limit <= 0) return
        ensureLoaded()
        val k = key(provider, modelName)
        val previous = bounds[k]
        if (previous?.providerLimit == limit) return
        bounds[k] = BoundEntry(
            bound = previous?.bound ?: 0,
            updatedAt = System.currentTimeMillis(),
            providerLimit = limit,
        )
        persist()
    }

    /**
     * The exact provider-stated window size for the model, or null
     * when no overflow error has revealed it yet.
     */
    fun providerLimit(provider: LLMProvider, modelName: String): Int? {
        ensureLoaded()
        return bounds[key(provider, modelName)]?.providerLimit
    }

    /**
     * The observed context-window lower bound for the model, or null
     * when nothing has been recorded yet (e.g. first pipeline run).
     */
    fun bound(provider: LLMProvider, modelName: String): Int? {
        ensureLoaded()
        return bounds[key(provider, modelName)]?.bound
    }

    @Synchronized
    private fun persist() {
        try {
            val dir = boundsFile.parentFile
            if (!dir.exists() && !dir.mkdirs()) return
            val tmp = File(dir, boundsFile.name + ".tmp")
            val out = bounds.mapValues { (_, e) ->
                mapOf(
                    "bound" to e.bound,
                    "updatedAt" to e.updatedAt,
                    "providerLimit" to e.providerLimit,
                )
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp, out)
            if (!tmp.renameTo(boundsFile)) {
                boundsFile.delete()
                tmp.renameTo(boundsFile)
            }
        } catch (e: Exception) {
            logger.warn("Failed to persist context bounds: ${e.message}")
        }
    }
}
