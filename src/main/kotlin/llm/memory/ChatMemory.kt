package org.iotsplab.akiba.llm.memory

import org.apache.logging.log4j.LogManager
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.agent.LLM_RETRY_STATUS_PREFIX
import org.iotsplab.akiba.llm.agent.LLM_PROGRESS_PREFIX

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
/**
 * A single message in an agent conversation.
 *
 * @param role One of "user", "assistant", "tool", "system".
 * @param content The textual content of the message.
 * @param toolCallId For tool-result messages, the ID of the corresponding tool call.
 * @param toolName For tool-result messages, the name of the tool that was invoked.
 * @param messageIndex Per-session monotonically increasing row index in
 *        `agent_messages`.  Populated by [PersistentChatMemory] so that
 *        operations such as [ChatMemory.removeLast] can target the exact
 *        DB row by its index even when the local buffer is a sub-range
 *        of the full DB transcript (e.g. after a compaction).  `null`
 *        means the index is not known to the buffer (the row was added
 *        outside [PersistentChatMemory] or has not been persisted yet).
 */
data class AgentChatMessage(
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolCallArgs: String? = null,
    val tokenCount: Int? = null,
    val inputTokenCount: Int? = null,
    val messageIndex: Int? = null,
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
    ) = add(AgentChatMessage(role = "tool", content = result ?: "", toolCallId = toolCallId, toolName = toolName, toolCallArgs = args))

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

    /**
     * Remove and return the last message, or null if empty.
     *
     * Used by [AkibaAgent.executeWithStrategy] to strip the synthetic
     * STANDBY-resume marker from memory WITHOUT clearing the entire
     * conversation history (which would lose tool-call metadata and
     * all previous messages from the DB).
     */
    fun removeLast(): AgentChatMessage? = null

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

    override fun removeLast(): AgentChatMessage? =
        if (buffer.isEmpty()) null else buffer.removeLast()
}
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

    /**
     * The next `message_index` value the daemon will assign to our next
     * `appendMessages` call.  We track this locally so each buffered
     * [AgentChatMessage] knows its real DB row index — that index is the
     * only reliable handle for [removeLast] once the local buffer has
     * been compacted into a summary and no longer maps 1:1 to DB rows.
     *
     * It is initialised from `MAX(messageIndex) + 1` after a full DB scan
     * (see [loadFromDatabase]).  [clear] does NOT reset it, because the
     * DB rows we skipped during a compaction still exist and continue to
     * occupy their original indices.
     */
    private var nextMessageIndex: Int = 1

    init {
        // Load existing messages from DB
        loadFromDatabase()
    }

    private fun loadFromDatabase() {
        try {
            // Phase 1: load ALL messages from DB into a temporary list so we
            // can scan for the latest compaction boundary and the highest
            // message_index we have already consumed.
            val all = mutableListOf<AgentDatabaseClient.MessageInfo>()
            var offset = 0
            val batchSize = 200
            while (true) {
                val batch = agentDbClient.getMessages(sessionId, offset, batchSize)
                if (batch.isEmpty()) break
                all.addAll(batch)
                offset += batch.size
                if (batch.size < batchSize) break
            }

            // Phase 2: find the last <previous_summary> system message — this
            // is the compaction boundary.  Only messages from that point
            // onward belong in the LLM context buffer.  Messages before the
            // boundary remain in the DB for frontend display but are NOT
            // sent to the LLM, keeping the context window small after a
            // compaction without losing history.
            val summaryIdx = all.indexOfLast { msg ->
                msg.role == "system" && (msg.content ?: "").contains("<previous_summary>")
            }
            val startIdx = if (summaryIdx >= 0) summaryIdx else 0
            val compactedCount = if (summaryIdx >= 0) summaryIdx else 0

            for (i in startIdx until all.size) {
                val msg = all[i]
                // Skip LLM retry-status and progress-heartbeat messages —
                // they are UI-only notifications and must never enter the
                // LLM context.
                if (msg.role == "system") {
                    val c = msg.content ?: ""
                    if (c.startsWith(LLM_RETRY_STATUS_PREFIX) ||
                        c.startsWith(LLM_PROGRESS_PREFIX)) {
                        continue
                    }
                }
                val content = when (msg.role) {
                    "tool" -> msg.toolResult ?: msg.content ?: ""
                    else -> msg.content ?: ""
                }
                buffer.add(
                    AgentChatMessage(
                        role = msg.role,
                        content = content,
                        toolCallId = msg.toolCallId,
                        toolName = msg.toolName,
                        toolCallArgs = msg.toolCallArgs,
                        messageIndex = msg.messageIndex,
                    )
                )
            }

            // Phase 3: figure out the next index the daemon will assign.
            // We pick MAX over the full DB scan (not just the buffer) so
            // that rows skipped during compaction still count.
            val maxIndex = all.maxOfOrNull { it.messageIndex } ?: 0
            nextMessageIndex = maxIndex + 1

            logger.debug(
                "Loaded ${buffer.size} messages from DB for session $sessionId" +
                    (if (compactedCount > 0) " (skipped $compactedCount compacted messages before <previous_summary>)" else "") +
                    "; nextMessageIndex=$nextMessageIndex"
            )
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

        // Update local buffer, recording the message_index the daemon just
        // assigned to this row so that removeLast() can target it later.
        buffer.add(AgentChatMessage(role, content, messageIndex = nextMessageIndex))
        nextMessageIndex += 1

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

        // Stamp the assigned DB index on the buffered copy and advance
        // the local counter for the next append.  We always overwrite
        // whatever the caller passed in: PersistentChatMemory is the
        // single owner of message_index assignment for this session.
        val indexed = message.copy(messageIndex = nextMessageIndex)
        nextMessageIndex += 1
        buffer.add(indexed)
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

        buffer.add(
            AgentChatMessage(
                role = "tool",
                content = result ?: "",
                toolCallId = toolCallId,
                toolName = toolName,
                toolCallArgs = args,
                messageIndex = nextMessageIndex,
            )
        )
        nextMessageIndex += 1
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
            // Remove from local buffer only.  Do NOT delete from the
            // database — the frontend reads agent_messages directly and
            // deleting rows would make evicted messages disappear from
            // the UI.  The local buffer is the LLM's context window;
            // the DB is the permanent transcript.
            repeat(evictCount) { buffer.removeAt(0) }
            logger.debug("Evicted $evictCount messages from local buffer (DB rows retained)")
        }
    }

    override fun messages(): List<AgentChatMessage> = buffer.toList()

    override fun clear() {
        // IMPORTANT: do NOT delete messages from the database here.
        //
        // [AkibaAgent.compact] calls clear() and then re-adds the system
        // prompt + <previous_summary> + kept rounds.  If we deleted the
        // old rows, the frontend (which reads agent_messages directly)
        // would lose all pre-compaction history — every previous user /
        // assistant / tool message would vanish from the UI.
        //
        // Instead, we only clear the in-memory buffer.  The summary
        // message that compact() writes next carries a <previous_summary>
        // marker; loadFromDatabase() uses that marker as a compaction
        // boundary so the LLM only sees post-compaction messages, while
        // the DB retains the full history for frontend display.
        buffer.clear()
    }

    override fun removeLast(): AgentChatMessage? {
        if (buffer.isEmpty()) return null
        val last = buffer.removeAt(buffer.size - 1)
        // Delete only the very last DB row, identified by its real
        // message_index.  Using `buffer.size` here would be wrong after
        // a compaction: the buffer then holds only a post-summary tail
        // (e.g. 5 messages) while the DB still has the full transcript
        // (e.g. 50 messages), so `buffer.size` no longer maps to the
        // row we want to drop — passing it as `fromIndex` would wipe
        // out legitimate transcript history.
        val lastIndex = last.messageIndex
        if (lastIndex == null) {
            logger.warn(
                "removeLast: last buffered message has no messageIndex for session $sessionId; " +
                    "skipping DB delete to avoid wiping history."
            )
            return last
        }
        try {
            agentDbClient.deleteMessagesFrom(sessionId, lastIndex)
        } catch (e: Exception) {
            logger.warn("Failed to delete last message from DB: ${e.message}")
        }
        return last
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
