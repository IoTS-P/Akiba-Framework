package org.iotsplab.akiba.llm.memory

import org.apache.logging.log4j.LogManager
import org.iotsplab.akiba.data.database.AgentDatabaseClient

// ============================================================
//  Memory types & scopes
// ============================================================

/** Types of long-term cognitive memories an agent can store. */
enum class MemoryType(val value: String) {
    /** A factual finding discovered during analysis. */
    FINDING("finding"),
    /** A planned action or strategy. */
    PLAN("plan"),
    /** An insight or conclusion derived from findings. */
    INSIGHT("insight"),
    /** An error or failure encountered. */
    ERROR("error"),
    /** User-defined type. */
    CUSTOM("custom");

    companion object {
        fun fromString(value: String): MemoryType =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) } ?: CUSTOM
    }
}

/** Scope of a memory — determines its visibility and lifetime. */
enum class MemoryScope(val value: String) {
    /** Visible only within the current session. */
    SESSION("session"),
    /** Visible across all sessions for the same binary. */
    BINARY("binary"),
    /** Visible globally across all sessions and binaries. */
    GLOBAL("global");

    companion object {
        fun fromString(value: String): MemoryScope =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) } ?: SESSION
    }
}

/** A single memory entry. */
data class MemoryEntry(
    val memoryId: Long,
    val memoryType: MemoryType,
    val scope: MemoryScope,
    val key: String?,
    val content: String,
    val importance: Double?,
    val metadata: String?
)

// ============================================================
//  MemoryManager
// ============================================================

/**
 * Manages long-term cognitive memories for an agent.
 *
 * Memories are persisted via [AgentDatabaseClient] and can be queried
 * across sessions and binaries.  The manager provides convenience methods
 * for the common memory operations and can inject relevant memories into
 * the system prompt for context enrichment.
 *
 * Typical usage inside an agent:
 * ```kotlin
 * memoryManager.remember(
 *     type = MemoryType.FINDING,
 *     scope = MemoryScope.BINARY,
 *     key = "entry_point",
 *     content = "main() at 0x00401234"
 * )
 * val findings = memoryManager.recall(
 *     type = MemoryType.FINDING,
 *     scope = MemoryScope.BINARY
 * )
 * ```
 */
class MemoryManager(
    private val sessionId: String? = null,
    private val binaryId: Int? = null
) {

    private val logger = LogManager.getLogger(MemoryManager::class.java)

    // ---- Store -----------------------------------------------------------

    /**
     * Store a new memory.
     *
     * @return the generated memory ID, or -1 on failure.
     */
    fun remember(
        content: String,
        type: MemoryType = MemoryType.FINDING,
        scope: MemoryScope = MemoryScope.SESSION,
        key: String? = null,
        importance: Double? = null,
        metadata: String? = null
    ): Long {
        return try {
            AgentDatabaseClient.storeMemory(
                sessionId = sessionId,
                binaryId = binaryId,
                memoryType = type.value,
                scope = scope.value,
                key = key,
                content = content,
                importance = importance,
                metadata = metadata
            )
        } catch (e: Exception) {
            logger.warn("Failed to store memory: ${e.message}")
            -1L
        }
    }

    /** Shortcut for storing a finding. */
    fun finding(content: String, scope: MemoryScope = MemoryScope.SESSION, key: String? = null): Long =
        remember(content, MemoryType.FINDING, scope, key)

    /** Shortcut for storing a plan. */
    fun plan(content: String, scope: MemoryScope = MemoryScope.SESSION, key: String? = null): Long =
        remember(content, MemoryType.PLAN, scope, key)

    /** Shortcut for storing an insight. */
    fun insight(content: String, scope: MemoryScope = MemoryScope.SESSION, key: String? = null): Long =
        remember(content, MemoryType.INSIGHT, scope, key)

    /** Shortcut for storing an error. */
    fun error(content: String, scope: MemoryScope = MemoryScope.SESSION, key: String? = null): Long =
        remember(content, MemoryType.ERROR, scope, key)

    // ---- Query -----------------------------------------------------------

    /**
     * Recall memories matching the given filters.
     */
    fun recall(
        type: MemoryType? = null,
        scope: MemoryScope? = null,
        key: String? = null,
        minImportance: Double? = null,
        limit: Int = 50
    ): List<MemoryEntry> {
        return try {
            AgentDatabaseClient.queryMemories(
                sessionId = sessionId,
                binaryId = binaryId,
                memoryType = type?.value,
                scope = scope?.value,
                key = key,
                minImportance = minImportance,
                limit = limit
            ).map { MemoryEntry(
                memoryId = it.memoryId,
                memoryType = MemoryType.fromString(it.memoryType),
                scope = MemoryScope.fromString(it.scope),
                key = it.key,
                content = it.content,
                importance = it.importance,
                metadata = it.metadata
            ) }
        } catch (e: Exception) {
            logger.warn("Failed to recall memories: ${e.message}")
            emptyList()
        }
    }

    /** Recall all findings. */
    fun findings(scope: MemoryScope? = null): List<MemoryEntry> =
        recall(type = MemoryType.FINDING, scope = scope)

    /** Recall all plans. */
    fun plans(scope: MemoryScope? = null): List<MemoryEntry> =
        recall(type = MemoryType.PLAN, scope = scope)

    /** Recall all insights. */
    fun insights(scope: MemoryScope? = null): List<MemoryEntry> =
        recall(type = MemoryType.INSIGHT, scope = scope)

    // ---- Context enrichment ----------------------------------------------

    /**
     * Generate a formatted memory summary for injection into a system prompt.
     *
     * The summary groups memories by type and presents them in a compact format
     * suitable for LLM context enrichment.
     *
     * @param maxItems maximum number of items per memory type
     * @param separator line separator between sections
     * @return formatted memory string, or empty string if no memories found
     */
    fun contextSummary(
        maxItems: Int = 10,
        separator: String = "\n"
    ): String {
        val sections = mutableListOf<String>()

        for (type in listOf(MemoryType.FINDING, MemoryType.PLAN, MemoryType.INSIGHT)) {
            val items = recall(type = type, limit = maxItems)
            if (items.isEmpty()) continue

            val header = when (type) {
                MemoryType.FINDING -> "### Findings"
                MemoryType.PLAN -> "### Plans"
                MemoryType.INSIGHT -> "### Insights"
                else -> "### ${type.value}"
            }

            val body = items.joinToString("\n") { mem ->
                val keyStr = mem.key?.let { "[$it] " } ?: ""
                val impStr = mem.importance?.let { " (importance: ${"%.2f".format(it)})" } ?: ""
                "- $keyStr${mem.content}$impStr"
            }

            sections.add("$header\n$body")
        }

        return if (sections.isEmpty()) "" else sections.joinToString(separator, prefix = separator)
    }
}
