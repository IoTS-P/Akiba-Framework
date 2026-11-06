package org.iotsplab.akiba.llm.config

import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.apache.logging.log4j.LogManager
import org.iotsplab.akiba.llm.agent.context.ModelContextWindows
import org.iotsplab.akiba.utils.AkibaJson

/**
 * On-disk store for LLM API keys in `~/.akiba/llm_keys.json`.
 *
 * Each entry holds a provider / modelNames / baseUrl / apiKey tuple, so several
 * vendors can coexist; one key may cover many models, so `modelNames` is a list,
 * and per-model windows ride along in [KeyEntry.contextLengths].
 *
 * Written with POSIX 600 permissions (best-effort ACL elsewhere); it is **not**
 * encryption at rest — any process running as the same Unix user can read it.
 */
object LLMKeyFileStore {

    private val logger = LogManager.getLogger(LLMKeyFileStore::class.java)
    private val mapper = AkibaJson.mapper

    private val akibaDir: File by lazy {
        File(System.getProperty("user.home"), ".akiba").also {
            if (!it.exists() && !it.mkdirs()) {
                logger.warn("Failed to create ~/.akiba directory")
            }
        }
    }

    /** The JSON file that holds the key entries. */
    val keyFile: File by lazy { File(akibaDir, "llm_keys.json") }

    /** A single entry in the on-disk key file. */
    data class KeyEntry(
        val id: String = java.util.UUID.randomUUID().toString(),
        val provider: String,
        val modelNames: List<String>,
        val baseUrl: String?,
        val apiKey: String,

        /**
         * Explicit context window per model name, in tokens.
         *
         * Absent (or unknown model) keeps the built-in table in charge; see
         * [ModelContextWindows.resolve] for the order.
         */
        val contextLengths: Map<String, Int> = emptyMap()
    )

    /** Load all entries from disk. Returns an empty list if the file does not exist yet. */
    fun load(): List<KeyEntry> {
        if (!keyFile.exists()) {
            return emptyList()
        }
        return try {
            mapper.readValue<List<KeyEntry>>(keyFile)
        } catch (e: Exception) {
            logger.error("Failed to load LLM keys from ${keyFile.absolutePath}: ${e.message}")
            emptyList()
        }
    }

    /** Overwrite the file with [entries] and restrict permissions to 600. */
    fun save(entries: List<KeyEntry>) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(keyFile, entries)
            restrictFilePermissions()
            logger.info("Saved ${entries.size} LLM key(s) to ${keyFile.absolutePath}")
        } catch (e: Exception) {
            logger.error("Failed to save LLM keys: ${e.message}")
            throw e
        }
    }

    /**
     * Add or update a single entry.
     *
     * Matching is done by `(provider, baseUrl)`. An existing entry keeps its
     * stored apiKey when the incoming one is blank.
     *
     * Context lengths are authoritative for the models in `entry.modelNames`:
     * an absent key means "use the built-in table", so clearing a field resets
     * it. Overrides of models this call does not mention are left alone.
     *
     * @throws IllegalArgumentException on a missing apiKey for a new entry, or a
     *   context length outside `1..MAX_CONTEXT_WINDOW`.
     */
    fun addOrUpdate(entry: KeyEntry) {
        val incoming = entry.copy(
            contextLengths = entry.contextLengths
                .filterKeys { it in entry.modelNames }
                .mapValues { (model, tokens) -> validateContextLength(model, tokens) }
        )
        val entries = load().toMutableList()
        val idx = entries.indexOfFirst {
            it.provider == entry.provider && it.baseUrl == entry.baseUrl
        }
        if (idx >= 0) {
            val existing = entries[idx]
            val mergedNames = (existing.modelNames + incoming.modelNames).distinct()
            val mergedKey = incoming.apiKey.ifBlank { existing.apiKey }
            require(mergedKey.isNotBlank()) { "apiKey is required to update '${entry.provider}'" }
            val untouched = existing.contextLengths.filterKeys { it !in incoming.modelNames }
            entries[idx] = existing.copy(
                modelNames = mergedNames,
                apiKey = mergedKey,
                contextLengths = untouched + incoming.contextLengths
            )
        } else {
            require(incoming.apiKey.isNotBlank()) {
                "apiKey is required for a new '${entry.provider}' configuration"
            }
            entries.add(incoming)
        }
        save(entries)
    }

    /**
     * Explicit context window saved for [modelName], or null when unset.
     *
     * [provider] disambiguates when two vendors expose the same model name; with
     * no match there, any entry that lists the model answers.
     */
    fun configuredContextLength(modelName: String, provider: String? = null): Int? {
        val entries = load()
        val exact = provider?.let { p ->
            entries.find { it.provider == p && it.modelNames.contains(modelName) }
        }
        val owner = exact ?: entries.find { it.modelNames.contains(modelName) }
        return owner?.contextLengths?.get(modelName)?.takeIf { it > 0 }
    }

    /**
     * Reject a context length outside `1..MAX_CONTEXT_WINDOW`.
     *
     * The message names the model and the bound so the settings UI can surface it
     * verbatim.
     */
    fun validateContextLength(modelName: String, tokens: Int): Int {
        require(tokens > 0) {
            "Context length for '$modelName' must be positive, got $tokens tokens"
        }
        require(tokens <= ModelContextWindows.MAX_CONTEXT_WINDOW) {
            "Context length for '$modelName' must be at most " +
                "${ModelContextWindows.MAX_CONTEXT_WINDOW} tokens, got $tokens"
        }
        return tokens
    }

    /**
     * Remove [modelName] from the entry identified by [provider].
     *
     * The first entry that belongs to [provider] and contains [modelName]
     * is located; the model is removed from that entry's [modelNames].
     * If the entry's model list becomes empty, the entire entry is deleted.
     *
     * Returns true if something was removed.
     */
    fun remove(provider: String, modelName: String): Boolean {
        val entries = load().toMutableList()
        val entryIdx = entries.indexOfFirst {
            it.provider == provider && it.modelNames.contains(modelName)
        }
        if (entryIdx < 0) return false

        val entry = entries[entryIdx]
        val newNames = entry.modelNames - modelName
        if (newNames.isEmpty()) {
            entries.removeAt(entryIdx)
        } else {
            entries[entryIdx] = entry.copy(
                modelNames = newNames,
                contextLengths = entry.contextLengths.filterKeys { it != modelName }
            )
        }
        save(entries)
        return true
    }

    /** Find a single entry by exact match on provider and model name. */
    fun find(provider: String, modelName: String): KeyEntry? {
        return load().find {
            it.provider == provider && it.modelNames.contains(modelName)
        }
    }

    /** Find a single entry by its unique UUID. */
    fun findById(id: String): KeyEntry? {
        return load().find { it.id == id }
    }

    /** Restrict file access to the owner only (600 on POSIX). */
    private fun restrictFilePermissions() {
        try {
            val path = keyFile.toPath()
            val fileSystem = path.fileSystem
            if (fileSystem.supportedFileAttributeViews().contains("posix")) {
                val perms = PosixFilePermissions.fromString("rw-------")
                Files.setPosixFilePermissions(path, perms)
                logger.debug("Set POSIX permissions to 600 on ${keyFile.absolutePath}")
            } else {
                // Best-effort on Windows
                keyFile.setReadable(false, false)
                keyFile.setWritable(false, false)
                keyFile.setExecutable(false, false)
                keyFile.setReadable(true, true)
                keyFile.setWritable(true, true)
            }
        } catch (e: Exception) {
            logger.warn("Could not restrict file permissions on ${keyFile.absolutePath}: ${e.message}")
        }
    }
}
