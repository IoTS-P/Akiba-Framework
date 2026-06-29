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
    /**
     * Agent explicitly asked to park to STANDBY (i.e. the LLM
     * emitted the `Enter standby mode.` marker recognised by
     * [SharedStandbyExtractor]).  Mapped by [AgentRuntime.runChildJob]
     * to `runtime_state='standby'` for `lifecycle=standby` children
     * (so they keep accepting mailbox messages) and to `closed` for
     * `lifecycle=one_shot` children (so the marker is treated as a
     * no-op-final-answer and the session is terminated).
     */
    STANDBY,
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

    /**
     * Maximum number of iterations before forcing a stop. Declared
     * `@Volatile var` so the runtime can override it (e.g. lower
     * the cap when the surrounding context is tight). The strategy
     * reads it through the freshly-built [StrategyContext] on every
     * `run()` so updates are picked up without restarting the agent.
     */
    @Volatile var maxIterations: Int = 10,

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

    /**
     * When true and [lifecycle] is [Lifecycle.STANDBY], the default
     * strategies (ReAct / PlanExecute) call [compact] just before
     * parking the session.  The compacted memory is persisted (when
     * `agentDbClient` is set), so the next resume runs against a
     * much smaller context — cheaper to send and cheaper to reason
     * over.  Default true.  Has no effect on [Lifecycle.ONE_SHOT]
     * sessions (they are terminal; compacting is wasted work).
     */
    val compactOnStandby: Boolean = true,

    /** Fraction of [contextLength] that triggers compaction (default 0.75). */
    val compactThreshold: Double = 0.75,

    /** Number of recent user-assistant rounds to retain during compaction. */
    val compactKeepRounds: Int = 2,

    /**
     * Safety factor applied when adjusting the effective context cap after a
     * successful [onLLMErrorHook] recovery. The new cap is set to
     * `currentPostCompactionTokens * errorRecoverySafetyFactor`, giving the
     * next iteration a margin of `1 - errorRecoverySafetyFactor` against the
     * post-compaction size that the provider actually accepted. Lower values
     * compact more aggressively (less risk of re-hitting the limit, at the
     * cost of more frequent compactions); 0.85 is a balanced default.
     */
    val errorRecoverySafetyFactor: Double = 0.85,

    /** Agent database client for audit logging and session management.
     *  Null means audits are silently skipped. */
    val agentDbClient: AgentDatabaseClient? = null,

    /** Optional tool result duplicate detector. Defaults to session-level exact-hash detection. */
    val toolResultDuplicateDetector: ToolResultDuplicateDetector? = null,

    /** Optional domain-specific workflow harness. Defaults to no-op. */
    val agentHarness: AgentHarness = DefaultAgentHarness,

    /**
     * Agent lifecycle policy. Default [Lifecycle.ONE_SHOT] makes the
     * session terminal after `run()`; [Lifecycle.STANDBY] parks it
     * so it keeps accepting mailbox messages. The dispatcher that
     * wakes a parked session is wired separately (see
     * AgentMailboxDispatcher).
     */
    val lifecycle: Lifecycle = Lifecycle.ONE_SHOT,

    /**
     * Mailbox service used by the default [AgentHarness.beforeIteration]
     * to drain incoming messages before each LLM call. Null disables
     * the drain.
     */
    val mailboxService: AgentMailboxService? = null,

    /** Runtime handle for sub-agents. Set by [AgentRuntime] so the strategy
     * can stop before the next LLM call after cooperative cancellation. */
    @Volatile var runtimeHandle: JobHandle? = null,

    /**
     * Declarative list of child agents to spawn at startup.  Empty
     * by default.  Populated via the [akibaAgent] DSL's
     * `subAgent { ... }` blocks; consumed by
     * [AgentModule.spawnConfiguredSubAgents] to call
     * [spawnChildFromAgentProgrammatically] for each entry before
     * the parent's first `run()`.  This is the
     * "fixed-orchestration" path: the children are constructed by
     * code, no [AgentTemplate] / `agent_builder_alternatives` look-up
     * is involved.
     */
    val subAgents: List<ProgrammaticSubAgentSpec> = emptyList(),
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
        // Detect a STANDBY-resume run: the caller passed a
        // runtime-generated [AgentRuntime.STANDBY_RESUME_PROMPT_*]
        // marker as the user input.  We recognise the marker by
        // prefix+suffix match, NOT by exact equality, so a user
        // that happens to type the old fixed prompt will not be
        // mis-classified as a resume.  The strategy then knows to
        // skip its initial greeting and the harness injects the
        // drained mailbox messages as the real input on the
        // first beforeIteration tick.  We strip the synthetic
        // marker so the LLM's input is purely the drained
        // mailbox messages.
        val msgs = memory.messages()
        val lastUserIdx = msgs.indexOfLast { it.role == "user" }
        val lastUserContent = if (lastUserIdx >= 0) msgs[lastUserIdx].content else null
        val isResumed = lastUserContent != null &&
            lastUserContent.startsWith(AgentRuntime.STANDBY_RESUME_PROMPT_PREFIX) &&
            lastUserContent.endsWith(AgentRuntime.STANDBY_RESUME_PROMPT_SUFFIX)
        if (isResumed) {
            // Strip the synthetic marker; the real input is
            // the mailbox messages that the harness will
            // inject on the first beforeIteration tick.
            memory.clear()
            msgs.forEachIndexed { i, m ->
                if (i != lastUserIdx) memory.add(m.role, m.content)
            }
        }

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
            onLLMErrorHook = { info ->
                // Keep the previous single-recovery semantics: only react to the
                // first error in a run. Subsequent errors propagate as normal
                // failures to avoid compounding compactions or another cap drop.
                if (info.attempt > 1) {
                    logger.warn(
                        "onLLMErrorHook: declining retry on attempt ${info.attempt} " +
                            "(only the first error triggers recovery; currentTokens=${info.currentTokens})."
                    )
                    return@StrategyContext false
                }
                val compacted = autoCompact && compact()
                if (compacted) adjustEffectiveContextCap()
                compacted
            },
            agentDbClient = agentDbClient,
            contextMessagesProvider = { contextMessages() },
            toolResultDuplicateDetector = toolResultDuplicateDetector
                ?: DefaultToolResultDuplicateDetector(sessionId, agentDbClient, logger = logger),
            harness = agentHarness,
            mailboxService = mailboxService,
            resumedFromStandby = isResumed,
            lifecycle = lifecycle,
            compactFn = if (compactOnStandby) {
                { compact() }
            } else null,
            cancellationReasonProvider = {
                runtimeHandle?.takeIf { it.cancelRequested }
                    ?.requestedCancelReason
                    ?: runtimeHandle?.takeIf { it.cancelRequested }?.let { "cancelled" }
            },
        )
        return strategy.execute(ctx)
    }

    // ---- Context compaction ----------------------------------------------

    /**
     * Effective compaction cap in tokens. When non-null, overrides the default
     * `contextLength * compactThreshold` ceiling used by [maybeCompact]. The
     * value is only ever lowered (monotonic) and is set when an LLM call
     * fails and the [onLLMErrorHook] recovery retry succeeds: we now know the
     * post-compaction size is what the provider accepts, so we use a fraction
     * of that as the next compaction trigger to avoid hitting the limit again.
     */
    private var effectiveMaxTokens: Int? = null

    /**
     * Lower [effectiveMaxTokens] based on the current post-compaction size.
     * Called only when an [onLLMErrorHook] recovery successfully unblocks an LLM call.
     * The new cap is `currentTokens * errorRecoverySafetyFactor`, capped below
     * the previous effective cap (so repeated failures keep tightening the
     * budget monotonically).
     */
    private fun adjustEffectiveContextCap() {
        val postSize = memory.messages().sumOf { client.estimateTokenCount(it.content) }
        val candidate = (postSize.toDouble() * errorRecoverySafetyFactor).toInt().coerceAtLeast(1)
        val previous = effectiveMaxTokens
        val next = if (previous == null || candidate < previous) candidate else previous
        if (next != previous) {
            effectiveMaxTokens = next
            logger.warn(
                "Lowered effective context cap after a successful error-recovery compaction: " +
                    "${previous ?: "<unset>"} -> $next tokens " +
                    "(post-compaction size=$postSize, safetyFactor=$errorRecoverySafetyFactor). " +
                    "Subsequent iterations will compact earlier to avoid re-hitting the provider limit."
            )
        }
    }

    // ---- Context compaction ----------------------------------------------

    private fun contextMessages(): List<AgentChatMessage> =
        ToolResultContext.compactHistoricalToolMessages(memory.messages())

    /**
     * Compact the conversation history if the estimated token count exceeds
     * the effective cap. The default cap is
     * `contextLength * compactThreshold`; after a successful [onLLMErrorHook]
     * recovery the cap may be lowered via [adjustEffectiveContextCap] so
     * the threshold is hit earlier next time, avoiding repeat LLM rejections.
     *
     * @return true if compaction was performed.
     */
    fun maybeCompact(): Boolean {
        val limit = contextLength ?: return false
        val defaultMax = (limit * compactThreshold).toInt()
        val maxTokens = effectiveMaxTokens ?: defaultMax
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
