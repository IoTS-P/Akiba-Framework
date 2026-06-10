package org.iotsplab.akiba.llm.config

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.apache.logging.log4j.LogManager
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * On-disk persistent store for LLM API keys.
 *
 * Keys are saved as a JSON array in `~/.akiba/llm_keys.json`. Each entry
 * records a provider / modelNames / baseUrl / apiKey tuple so that a user can
 * keep credentials for multiple vendors at the same time. A single API key
 * may cover several models, therefore [modelNames] is a list.
 *
 * Security notes
 * --------------
 * - The file is created with POSIX 600 permissions (owner read/write only)
 *   whenever it is written.
 * - On non-POSIX systems a best-effort Windows ACL restriction is applied.
 * - This is **not** encryption at rest; any process running as the same
 *   Unix user can still read the file. For stronger protection use an
 *   external secrets manager (HashiCorp Vault, OS keychain, …).
 */
object LLMKeyFileStore {

    private val logger = LogManager.getLogger(LLMKeyFileStore::class.java)
    private val mapper = jacksonObjectMapper()

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
        val apiKey: String
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
     * Matching is done by `(provider, baseUrl)`. If an entry with the same
     * provider and baseUrl already exists, the modelNames are merged
     * (deduplicated) and the apiKey is updated; otherwise the entry is appended.
     */
    fun addOrUpdate(entry: KeyEntry) {
        val entries = load().toMutableList()
        val idx = entries.indexOfFirst {
            it.provider == entry.provider && it.baseUrl == entry.baseUrl
        }
        if (idx >= 0) {
            val existing = entries[idx]
            val mergedNames = (existing.modelNames + entry.modelNames).distinct()
            entries[idx] = existing.copy(modelNames = mergedNames, apiKey = entry.apiKey)
        } else {
            entries.add(entry)
        }
        save(entries)
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
            entries[entryIdx] = entry.copy(modelNames = newNames)
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
