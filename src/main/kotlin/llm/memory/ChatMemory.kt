package org.iotsplab.akiba.llm.memory

import org.apache.logging.log4j.LogManager
import org.iotsplab.akiba.data.database.AgentDatabaseClient

// ============================================================
//  ChatMemory — interface
// ============================================================

/**
 * A single message in an agent conversation.
 *
 * @param role One of "user", "assistant", "tool", "system".
 * @param content The textual content of the message.
 * @param toolCallId For tool-result messages, the ID of the corresponding tool call.
 * @param toolName For tool-result messages, the name of the tool that was invoked.
 */
data class AgentChatMessage(
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val tokenCount: Int? = null,
    val inputTokenCount: Int? = null
)

/**
 * Manages conversation history for an LLM agent session.
 *
 * Implementations may store messages in memory, in a database, or use
 * a sliding-window / token-bounded strategy to keep the context within
 * the model's context window.
 *
 * Messages are stored as [AgentChatMessage] objects compatible with
 * [org.iotsplab.akiba.llm.client.AkibaLLMClient.chat].
 */
interface ChatMemory : AutoCloseable {

    /** The session ID this memory is bound to, if backed by a persistent store. */
    val sessionId: String?

    // ---- Mutation --------------------------------------------------------

    /** Add a message with the given role and content. */
    fun add(role: String, content: String)

    /** Add a user message. */
    fun addUserMessage(content: String) = add("user", content)

    /** Add an assistant message. */
    fun addAssistantMessage(content: String) = add("assistant", content)

    /** Add an assistant message with known token counts (from the LLM response). */
    fun addAssistantMessage(content: String, tokenCount: Int?, inputTokenCount: Int? = null) =
        add(AgentChatMessage(role = "assistant", content = content, tokenCount = tokenCount, inputTokenCount = inputTokenCount))

    /** Add a system message. */
    fun addSystemMessage(content: String) = add("system", content)

    /** Add a tool-result message. */
    fun addToolMessage(
        toolCallId: String,
        toolName: String,
        args: String? = null,
        result: String? = null
    ) = add(AgentChatMessage(role = "tool", content = result ?: "", toolCallId = toolCallId, toolName = toolName))

    /** Add a pre-built [AgentChatMessage]. */
    fun add(message: AgentChatMessage)

    // ---- Query -----------------------------------------------------------

    /** Retrieve all messages in order. */
    fun messages(): List<AgentChatMessage>

    /** Current message count. */
    fun size(): Int = messages().size

    /** Estimate total token count for all messages. */
    fun estimatedTokenCount(estimator: (String) -> Int): Int =
        messages().sumOf { estimator(it.content) }

    /** Clear all messages. */
    fun clear()

    override fun close() {}
}

// ============================================================
//  InMemoryChatMemory
// ============================================================

/**
 * Pure in-memory [ChatMemory] with optional sliding-window eviction.
 *
 * When [maxMessages] > 0, only the most recent *n* messages are retained;
 * older messages are silently dropped on each insertion.
 */
class InMemoryChatMemory(
    private val maxMessages: Int = 0
) : ChatMemory {

    override val sessionId: String? = null

    private val buffer: ArrayDeque<AgentChatMessage> = ArrayDeque()

    override fun add(role: String, content: String) {
        buffer.addLast(AgentChatMessage(role, content))
        if (maxMessages > 0 && buffer.size > maxMessages) {
            buffer.removeFirst()
        }
    }

    override fun add(message: AgentChatMessage) {
        buffer.addLast(message)
        if (maxMessages > 0 && buffer.size > maxMessages) {
            buffer.removeFirst()
        }
    }

    override fun messages(): List<AgentChatMessage> = buffer.toList()

    override fun clear() {
        buffer.clear()
    }
}

// ============================================================
//  PersistentChatMemory
// ============================================================

/**
 * Database-backed [ChatMemory] using [AgentDatabaseClient].
 *
 * New messages are appended to the `agent_messages` table.  On construction,
 * existing messages for the given [sessionId] are loaded from the database
 * so that a resumed session retains its full conversation history.
 *
 * Two eviction strategies are available:
 * - **Message-count window** ([maxMessages]): keeps only the last *n* messages.
 *   Excess rows are deleted from the database.
 * - **Token-budget window** ([maxTokens]): evicts the oldest messages until
 *   the estimated token count falls below the budget.
 *
 * Both strategies can be combined; the stricter limit wins.
 */
class PersistentChatMemory(
    private val agentDbClient: AgentDatabaseClient,
    override val sessionId: String,
    private val maxMessages: Int = 0,
    private val maxTokens: Int = 0,
    private val tokenEstimator: (String) -> Int = { text ->
        // Same naive estimator as AkibaLLMClient
        val cjk = text.count { it.code > 0x2E80 }
        val ascii = text.length - cjk
        (ascii / 4 + cjk / 2).coerceAtLeast(1)
    }
) : ChatMemory {

    private val logger = LogManager.getLogger(PersistentChatMemory::class.java)

    private val buffer: MutableList<AgentChatMessage> = mutableListOf()

    init {
        // Load existing messages from DB
        loadFromDatabase()
    }

    private fun loadFromDatabase() {
        try {
            var offset = 0
            val batchSize = 200
            while (true) {
                val batch = agentDbClient.getMessages(sessionId, offset, batchSize)
                if (batch.isEmpty()) break
                for (msg in batch) {
                    val content = when (msg.role) {
                        "tool" -> msg.toolResult ?: msg.content ?: ""
                        else -> msg.content ?: ""
                    }
                    buffer.add(
                        AgentChatMessage(
                            role = msg.role,
                            content = content,
                            toolCallId = msg.toolCallId,
                            toolName = msg.toolName
                        )
                    )
                }
                offset += batch.size
                if (batch.size < batchSize) break
            }
            logger.debug("Loaded ${buffer.size} messages from DB for session $sessionId")
        } catch (e: Exception) {
            logger.warn("Failed to load messages for session $sessionId: ${e.message}")
        }
    }

    override fun add(role: String, content: String) {
        // Persist to database
        try {
            val data = if (role == "tool") {
                AgentDatabaseClient.MessageData(role = role, content = content, toolResult = content)
            } else {
                AgentDatabaseClient.MessageData(role = role, content = content)
            }
            agentDbClient.appendMessages(sessionId, listOf(data))
        } catch (e: Exception) {
            logger.error("Failed to persist message to DB for session $sessionId", e)
            throw e
        }

        // Update local buffer
        buffer.add(AgentChatMessage(role, content))

        // Apply eviction
        evictIfNeeded()
    }

    override fun add(message: AgentChatMessage) {
        // For tool messages, persist full metadata
        if (message.role == "tool") {
            try {
                agentDbClient.appendMessages(
                    sessionId,
                    listOf(
                        AgentDatabaseClient.MessageData(
                            role = "tool",
                            content = message.content,
                            toolCallId = message.toolCallId,
                            toolName = message.toolName,
                            toolResult = message.content
                        )
                    )
                )
            } catch (e: Exception) {
                logger.error("Failed to persist tool message to DB for session $sessionId", e)
                throw e
            }
        } else {
            try {
                agentDbClient.appendMessages(
                    sessionId,
                    listOf(AgentDatabaseClient.MessageData(
                        role = message.role,
                        content = message.content,
                        tokenCount = message.tokenCount,
                        inputTokenCount = message.inputTokenCount
                    ))
                )
            } catch (e: Exception) {
                logger.error("Failed to persist message to DB for session $sessionId", e)
                throw e
            }
        }

        buffer.add(message)
        evictIfNeeded()
    }

    override fun addToolMessage(
        toolCallId: String,
        toolName: String,
        args: String?,
        result: String?
    ) {
        try {
            agentDbClient.appendMessages(
                sessionId,
                listOf(
                    AgentDatabaseClient.MessageData(
                        role = "tool",
                        content = result,
                        toolCallId = toolCallId,
                        toolName = toolName,
                        toolCallArgs = args,
                        toolResult = result
                    )
                )
            )
        } catch (e: Exception) {
            logger.error("Failed to persist tool message to DB for session $sessionId", e)
            throw e
        }

        buffer.add(AgentChatMessage(role = "tool", content = result ?: "", toolCallId = toolCallId, toolName = toolName))
        evictIfNeeded()
    }

    private fun evictIfNeeded() {
        if (maxMessages <= 0 && maxTokens <= 0) return

        var evictCount = 0

        // Message-count eviction
        if (maxMessages > 0 && buffer.size > maxMessages) {
            evictCount = buffer.size - maxMessages
        }

        // Token-budget eviction
        if (maxTokens > 0) {
            var totalTokens = estimatedTokenCount(tokenEstimator)
            var tokenEvict = 0
            while (totalTokens > maxTokens && tokenEvict < buffer.size - 1) {
                totalTokens -= tokenEstimator(buffer[tokenEvict].content)
                tokenEvict++
            }
            evictCount = maxOf(evictCount, tokenEvict)
        }

        if (evictCount > 0) {
            // Remove from local buffer
            repeat(evictCount) { buffer.removeAt(0) }

            // Remove from database
            try {
                agentDbClient.deleteMessagesFrom(sessionId, 0)
                logger.debug("Evicted $evictCount messages from local buffer")
            } catch (e: Exception) {
                logger.warn("Failed to evict messages from DB: ${e.message}")
            }
        }
    }

    override fun messages(): List<AgentChatMessage> = buffer.toList()

    override fun clear() {
        buffer.clear()
        try {
            agentDbClient.deleteMessagesFrom(sessionId, 0)
        } catch (e: Exception) {
            logger.warn("Failed to clear messages from DB: ${e.message}")
        }
    }
}

// ============================================================
//  Factory functions (DSL-friendly)
// ============================================================

/** Create an in-memory chat memory with optional max-messages window. */
fun inMemoryChatMemory(maxMessages: Int = 0): InMemoryChatMemory =
    InMemoryChatMemory(maxMessages)

/** Create a database-backed chat memory with optional eviction parameters. */
fun persistentChatMemory(
    agentDbClient: AgentDatabaseClient,
    sessionId: String,
    maxMessages: Int = 0,
    maxTokens: Int = 0,
    tokenEstimator: (String) -> Int = { text ->
        val cjk = text.count { it.code > 0x2E80 }
        val ascii = text.length - cjk
        (ascii / 4 + cjk / 2).coerceAtLeast(1)
    }
): PersistentChatMemory = PersistentChatMemory(agentDbClient, sessionId, maxMessages, maxTokens, tokenEstimator)
