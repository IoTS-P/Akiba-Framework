package org.iotsplab.akiba.llm.agent

import org.iotsplab.akiba.llm.client.AkibaLLMClient
import org.iotsplab.akiba.llm.memory.ChatMemory
import org.iotsplab.akiba.llm.memory.InMemoryChatMemory
import org.iotsplab.akiba.llm.memory.MemoryManager
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.agent.AgentPrompts.COMPRESSION_PROMPT
import org.iotsplab.akiba.llm.memory.AgentChatMessage
import org.iotsplab.akiba.llm.tool.ToolRegistry

// ============================================================
//  Agent result types
// ============================================================

/** The outcome of an agent run. */
data class AgentResult(
    /** The final text output from the agent. */
    val output: String,
    /** Number of LLM calls made during this run. */
    val iterations: Int,
    /** Number of tool calls made. */
    val toolCallsMade: Int,
    /** Total token usage across all calls (if reported by provider). */
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
    /** Why the agent stopped. */
    val stopReason: StopReason = StopReason.COMPLETED
)

/** Why the agent stopped iterating. */
enum class StopReason {
    /** Agent produced a final answer without wanting to call more tools. */
    COMPLETED,
    /** Agent reached the maximum iteration limit. */
    MAX_ITERATIONS,
    /** Agent encountered an error. */
    ERROR
}

// ============================================================
//  AkibaAgent — strategy-based agent
// ============================================================

/**
 * Core agent implementation with pluggable execution strategies.
 *
 * The agent delegates its execution loop to an [AgentStrategy], which
 * controls the interaction pattern with the LLM:
 *
 * - **[ReActStrategy]** — explicit Thought → Action → Observation cycle.
 *   Best for tasks requiring step-by-step reasoning and tool usage.
 *
 * - **[PlanExecuteStrategy]** — plan first, execute each step, then reflect.
 *   Best for complex, multistep tasks where upfront planning reduces
 *   meandering and wasted tool calls.
 *
 * The agent integrates with:
 * - [AkibaLLMClient] for LLM communication
 * - [ChatMemory] for conversation persistence and sliding-window eviction
 * - [MemoryManager] for long-term cognitive memory
 * - [org.iotsplab.akiba.llm.tool.ToolRegistry] for tool dispatch
 *
 * Typical construction via DSL:
 * ```kotlin
 * val agent = akibaAgent {
 *     fromGlobalConfig()
 *     system("You are a binary analysis assistant...")
 *     memory(persistentChatMemory(sessionId))
 *     tools(searchTool, decompileTool)
 *     strategy(ReActStrategy())
 *     maxIterations(15)
 * }
 * val result = agent.run("Analyze the main function")
 * ```
 */
class AkibaAgent(
    /** The LLM client to use for chat completions. */
    val client: AkibaLLMClient,

    /** System prompt prepended to every conversation. */
    val systemPrompt: String? = null,

    /** Conversation memory (message history). */
    val memory: ChatMemory = InMemoryChatMemory(),

    /** Long-term cognitive memory manager. */
    val memoryManager: MemoryManager? = null,

    /** Tools available to this agent. */
    val toolRegistry: ToolRegistry = ToolRegistry(),

    /** Maximum number of iterations before forcing a stop. */
    val maxIterations: Int = 10,

    /** Session ID for database persistence (optional). */
    val sessionId: String? = null,

    /** Whether to inject memory context into the system prompt. */
    val enrichSystemPromptWithMemory: Boolean = true,

    /** Whether to audit tool calls to the database. */
    val auditToolCalls: Boolean = true,

    /** The execution strategy. Defaults to [ReActStrategy]. */
    val strategy: AgentStrategy = ReActStrategy(),

    /** Logger for strategy execution. When provided (e.g. from AgentModule),
     *  logs go to the module's per-binary log file, making multi-threaded
     *  debugging possible. Falls back to a class-level logger. */
    val logger: Logger = LogManager.getLogger(AkibaAgent::class.java),

    /** Transcript writer for detailed Markdown-formatted interaction log.
     *  When provided, all LLM interactions, tool calls, and results are
     *  written to a readable file at `<logDir>/agent_transcript.md`. */
    val transcript: AgentTranscriptWriter? = null,

    /** Maximum context length (in tokens) for the current model.
     *  When null, context compression is disabled. */
    val contextLength: Int? = null,

    /** Whether to automatically compact conversation history before each run. */
    val autoCompact: Boolean = true,

    /** Fraction of [contextLength] that triggers compaction (default 0.75). */
    val compactThreshold: Double = 0.75,

    /** Number of recent user-assistant rounds to retain during compaction. */
    val compactKeepRounds: Int = 2,

    /** Agent database client for audit logging and session management.
     *  Null means audits are silently skipped. */
    val agentDbClient: AgentDatabaseClient? = null
) {

    // ---- Public API ------------------------------------------------------

    /**
     * Run the agent with the given user input.
     *
     * This is the main entry point: it adds the user message to the
     * conversation, optionally compacts the context if it exceeds the
     * model's context window, then delegates to the configured [strategy].
     *
     * @param userInput The user's request / question.
     * @return The [AgentResult] containing the agent's final output.
     */
    fun run(userInput: String): AgentResult {
        memory.addUserMessage(userInput)
        return executeWithStrategy()
    }

    /**
     * Run the agent with streaming support.
     *
     * Currently delegates to [run]. True token-by-token streaming in
     * the strategy loop requires detecting tool-call chunks from the
     * stream and will be added in a future iteration.
     */
    fun runStream(userInput: String): AgentResult {
        return run(userInput)
    }

    /**
     * Continue the conversation with a follow-up message, retaining
     * all previous context in memory.
     */
    fun continueConversation(userInput: String): AgentResult {
        return run(userInput)
    }

    // ---- Internal --------------------------------------------------------

    private fun executeWithStrategy(): AgentResult {
        val ctx = StrategyContext(
            client = client,
            memory = memory,
            memoryManager = memoryManager,
            toolRegistry = toolRegistry,
            sessionId = sessionId,
            systemPrompt = systemPrompt,
            maxIterations = maxIterations,
            enrichSystemPromptWithMemory = enrichSystemPromptWithMemory,
            auditToolCalls = auditToolCalls,
            logger = logger,
            transcript = transcript,
            beforeChatHook = {
                if (autoCompact) maybeCompact()
            },
            agentDbClient = agentDbClient
        )
        return strategy.execute(ctx)
    }

    // ---- Context compaction ----------------------------------------------

    /**
     * Compact the conversation history if the estimated token count exceeds
     * [compactThreshold] of [contextLength].
     *
     * @return true if compaction was performed.
     */
    fun maybeCompact(): Boolean {
        val limit = contextLength ?: return false
        val maxTokens = (limit * compactThreshold).toInt()
        val currentMsgs = memory.messages()
        val currentTokens = currentMsgs.sumOf { client.estimateTokenCount(it.content) }
        if (currentTokens <= maxTokens) return false
        return compact()
    }

    /**
     * Compact conversation history by summarising older rounds (all except the
     * most recent [compactKeepRounds]) via a single LLM call. The summary is
     * stored as a system message wrapped in `<previous_summary>` tags so that
     * subsequent compactions can incrementally update it.
     *
     * @return true if compaction was performed.
     */
    fun compact(): Boolean {
        val allMsgs = memory.messages()
        if (allMsgs.isEmpty()) return false

        // 1. Identify an existing summary message (system msg containing <previous_summary>)
        val summaryIdx = allMsgs.indexOfFirst {
            it.role == "system" && it.content.contains("<previous_summary>")
        }
        val existingSummary = if (summaryIdx >= 0) {
            extractSummaryContent(allMsgs[summaryIdx].content)
        } else null

        // Messages that are NOT the incremental summary
        val conversationMsgs = if (summaryIdx >= 0) {
            allMsgs.filterIndexed { i, _ -> i != summaryIdx }
        } else allMsgs

        // 2. Separate leading system messages (original system prompt etc.)
        val systemMsgs = conversationMsgs.takeWhile { it.role == "system" }
        val nonSystem = conversationMsgs.drop(systemMsgs.size)

        // 3. Group non-system messages into rounds (each round starts with a user message)
        val rounds = groupIntoRounds(nonSystem)
        if (rounds.size <= compactKeepRounds) return false

        val oldRounds = rounds.dropLast(compactKeepRounds)
        val keptRounds = rounds.takeLast(compactKeepRounds)

        // 4. Build messages for the compression LLM call
        val compressMessages = mutableListOf<AgentChatMessage>()
        if (existingSummary != null) {
            compressMessages.add(
                AgentChatMessage(
                    role = "user",
                    content = "Previous summary:\n<previous_summary>\n$existingSummary\n</previous_summary>\n\nPlease integrate this with the following new conversation history."
                )
            )
        }
        oldRounds.flatten().forEach { compressMessages.add(it) }

        // 5. Call LLM to generate the summary
        val completion = client.chat(
            systemPrompt = COMPRESSION_PROMPT,
            messages = compressMessages,
            tools = null
        )
        var summaryContent = completion.content.trim()

        // Ensure <previous_summary> wrapper exists
        if (!summaryContent.contains("<previous_summary>")) {
            summaryContent = "<previous_summary>\n$summaryContent\n</previous_summary>"
        }

        // 6. Rebuild memory: original system messages → summary → kept rounds
        memory.clear()
        systemMsgs.forEach { memory.add(it) }
        memory.addSystemMessage(summaryContent)
        keptRounds.flatten().forEach { memory.add(it) }

        logger.info(
            "Compacted context: summarised ${oldRounds.size} rounds into previous_summary, kept ${keptRounds.size} rounds."
        )
        return true
    }

    // ---- Compaction helpers ----------------------------------------------

    private fun groupIntoRounds(msgs: List<AgentChatMessage>): List<List<AgentChatMessage>> {
        val rounds = mutableListOf<MutableList<AgentChatMessage>>()
        var currentRound = mutableListOf<AgentChatMessage>()
        for (msg in msgs) {
            if (msg.role == "user" && currentRound.isNotEmpty()) {
                rounds.add(currentRound)
                currentRound = mutableListOf()
            }
            currentRound.add(msg)
        }
        if (currentRound.isNotEmpty()) rounds.add(currentRound)
        return rounds
    }

    private fun extractSummaryContent(text: String): String {
        val startTag = "<previous_summary>"
        val endTag = "</previous_summary>"
        val start = text.indexOf(startTag)
        val end = text.indexOf(endTag)
        if (start < 0 || end < 0 || end <= start) return text
        return text.substring(start + startTag.length, end).trim()
    }

    companion object {
        /** Maximum length of a tool result before truncation. */
        const val MAX_TOOL_RESULT_LENGTH = 8000
    }
}
