package org.iotsplab.akiba.llm.agent

import org.iotsplab.akiba.llm.client.AkibaLLMClient
import org.iotsplab.akiba.llm.memory.ChatMemory
import org.iotsplab.akiba.llm.memory.InMemoryChatMemory
import org.iotsplab.akiba.llm.memory.MemoryManager
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

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
 * - [ToolRegistry] for tool dispatch
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
    val transcript: AgentTranscriptWriter? = null
) {

    // ---- Public API ------------------------------------------------------

    /**
     * Run the agent with the given user input.
     *
     * This is the main entry point: it adds the user message to the
     * conversation, then delegates to the configured [strategy].
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
            transcript = transcript
        )
        return strategy.execute(ctx)
    }

    companion object {
        /** Maximum length of a tool result before truncation. */
        const val MAX_TOOL_RESULT_LENGTH = 8000
    }
}
