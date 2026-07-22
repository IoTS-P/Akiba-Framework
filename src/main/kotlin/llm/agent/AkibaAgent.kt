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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

// ============================================================
//  Agent result types
// ============================================================

/**
 * Why the agent's enclosing [AgentModule.startProcess] `finally` block
 * fired [AkibaAgent.processCompletionLatch].
 *
 * Used as the type parameter of the [AkibaAgent.processCompletionLatch]
 * `CompletableDeferred`, so any coroutine awaiting on it learns not just
 * "the run is over" but **how** it ended — important for module
 * orchestrators that have to decide what to do next (start a follow-up
 * agent vs. give up vs. escalate).
 *
 *  - [CLOSED]         — Final Answer completed normally and every cleanup
 *                       step (cascade-cancel, template unregister,
 *                       transcript close, LLM client close) ran to completion.
 *  - [ERROR]          — Agent stopped with [StopReason.ERROR] or a terminal
 *                       [StopReason.MAX_ITERATIONS]. Cleanup ran to completion;
 *                       the run itself just didn't finish cleanly.
 *  - [CLEANUP_FAILED] — The run reached a truly-terminated state, but at least
 *                       one cleanup step threw. Operators should inspect logs.
 */
enum class ProcessExitReason { CLOSED, ERROR, CLEANUP_FAILED }

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
     * Agent called `await_condition` to park to STANDBY.
     * Mapped by [AgentRuntime.runChildJob] to `runtime_state='standby'`
     * for `lifecycle=standby` children (so they keep accepting mailbox
     * messages) and to `closed` for `lifecycle=one_shot` children.
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
     * Cumulative token usage across ALL wake cycles for this agent.
     *
     * [LoopStats] is recreated fresh (zeroed) on every `executeWithStrategy()`
     * call because a new [StrategyContext] is built each time.  Without
     * carrying the cumulative count forward, the transcript's
     * "Token Usage (cumulative)" line resets to the current wake's tokens
     * only, which is misleading — the user sees a small number instead of
     * the true session total.
     *
     * These fields are initialised from the DB on the first run and
     * updated after each run.  Before the strategy executes, they are
     * copied into [LoopStats] so the transcript shows the true cumulative
     * total.  After the strategy returns, the final values are written
     * back here for the next wake.
     */
    @Volatile private var cumulativeInputTokens: Int = 0,
    @Volatile private var cumulativeOutputTokens: Int = 0,

    /**
     * Single-shot latch that fires when the parent
     * [AgentModule.startProcess] `finally` block has finished every
     * cleanup step (cascade-cancel of one_shot children, template
     * unregister, transcript close, LLM client close).
     *
     * This is the synchronisation point that makes the
     * "startProcess returned ⇒ the root agent is fully torn down"
     * contract enforceable.  In the previous design the cascade
     * cancel ran inside `GlobalScope.launch`, which was fire-and-
     * forget — startProcess would return while the children were
     * still being torn down in the background, breaking module
     * seriality (the next module's startProcess could observe a
     * half-cancelled child tree, or a still-open LLM client).
     *
     * The latch is completed only for terminal runs, after cleanup.
     * It remains open while a STANDBY agent is parked, including the
     * MAX_ITERATIONS safety fallback, so startProcess keeps waiting
     * until a later run truly exits.
     *
     * A fresh `AkibaAgent` is built on every [AgentModule.startProcess]
     * call (see `AgentModule.startProcess`'s `buildAgent(...)` line),
     * so a new agent = a new latch.  This latch is meant to fire
     * exactly once per [AgentModule.startProcess] invocation.
     */
    val processCompletionLatch: CompletableDeferred<ProcessExitReason> =
        CompletableDeferred(),

    /**
     * Termination hook — invoked once per [runWithTermination]
     * invocation whose underlying [run] produced a truly terminating
     * outcome. It is skipped for explicit STANDBY parks and for the
     * STANDBY-lifecycle MAX_ITERATIONS safety fallback so the session
     * can resume on a later mailbox message.
     *
     * The hook is the single chokepoint where every agent —
     * whether it is the root (started by [AgentModule.startProcess])
     * or a child (started by [AgentRuntime.runChildJob]) — runs
     * its cascade-cancel + resource-release + runtime_state
     * transition.  Lifting the cleanup from [AgentModule] into
     * the agent itself is what makes grandchildren cascade
     * correct: a sub-agent that itself has sub-agents now has
     * a real path to cancel them on its own termination, not
     * just relying on the [OrphanReaper] 60s scan-tick backstop.
     *
     * **Default behaviour.**  When this field is `null` after
     * construction (which is the case unless the caller
     * explicitly assigned one), the constructor's `init` block
     * installs a hook that calls [defaultTerminate] — i.e. it
     * runs [cascadeCancel] followed by [close].  In other words
     * the framework's default for *every* agent is "cancel my
     * own children, then release my own LLM client + transcript"
     * with no caller action required.
     *
     * **Overriding the default.**  Two supported entry points:
     *  1. The [akibaAgent] DSL's `onTermination { ... }` block
     *     (see [AgentBuilder.onTermination]) — the block runs
     *     with the built agent as receiver, so it can call
     *     [cascadeCancel] / [close] / [defaultTerminate] inline
     *     and add extra steps before/after.
     *  2. Direct programmatic assignment, e.g. in
     *     [AgentModule.startProcess] where the root needs
     *     extra cleanup (template-unregister, status flip)
     *     layered on top of the default.
     *
     * Suspend because the cleanup may include
     * `suspend` cascade-cancel calls which need to
     * `await` the children's terminal state with a
     * `graceMs` cap.
     */
    @Volatile var terminationHook: (suspend () -> Unit)? = null,

    /**
     * Optional cascade-canceller installed by whichever
     * orchestrator knows how to walk this agent's children.
     * [cascadeCancel] invokes it; the agent itself has no
     * knowledge of [AgentRuntime] or per-binary id resolution.
     *
     * Signature: `(sessionId, reason, graceMs) -> cancelledCount`.
     * The agent passes its own [sessionId] plus the
     * `reason` / `graceMs` it was called with.  The canceller's
     * caller is the runtime (for child agents) or the module
     * (for the root) — see [AgentRuntime.runChildJob] and
     * [AgentModule.startProcess].
     *
     * Keeping the canceller as a callback (rather than a hard
     * reference to an [AgentRuntime]) means the agent module
     * stays decoupled from the runtime's lookup mechanism —
     * tests, replay harnesses, and future orchestrators can
     * install their own canceller without dragging in the
     * per-binary registry.
     *
     * `null` is the safe default: [cascadeCancel] becomes a
     * no-op (returns 0) when no canceller is installed, so an
     * agent that was never wired up to a runtime still
     * terminates cleanly (just without cancelling any
     * children, which is the right behaviour for standalone
     * test fixtures).
     */
    @Volatile var cascadeCanceller: (suspend (String, String, Long) -> Int)? = null,

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

    /**
     * Per-conversation scratchpad registry.  Auto-created for
     * every agent — agents without [mailboxService] get a dummy
     * registry that never has any scratchpads.
     *
     * The registry provides **context isolation**: each conversation
     * gets its own message buffer, and the [ScratchpadRegistry]
     * serialises which conversation is "active" for the current
     * LLM turn.  This prevents cross-conversation context pollution
     * and supports preemption (urgent messages interrupt the
     * current conversation, then the previous one is restored with
     * a resume hint).
     *
     * The [applyMailboxDrain] helper writes drained messages into
     * the registry; the strategy reads the active conversation's
     * messages via [ScratchpadRegistry.activeConversationMessages]
     * to build the LLM context.
     */
    val scratchpadRegistry: ScratchpadRegistry = ScratchpadRegistry(),
) {

    init {
        // Install the default termination hook when the caller did
        // not supply one.  The default is "cascade-cancel my own
        // subtree, then close my LLM client + transcript" — see
        // [defaultTerminate] for the full sequence.  This is the
        // chokepoint that makes "every agent cleans up after itself"
        // work for both root and child agents without forcing every
        // factory to remember the pattern.
        if (terminationHook == null) {
            terminationHook = { defaultTerminate() }
        }
    }

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
    suspend fun run(userInput: String): AgentResult {
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
    suspend fun runStream(userInput: String): AgentResult {
        return run(userInput)
    }

    /**
     * Continue the conversation with a follow-up message, retaining
     * all previous context in memory.
     */
    suspend fun continueConversation(userInput: String): AgentResult {
        return run(userInput)
    }

    /**
     * Like [run], but additionally invokes [terminationHook] when
     * (and only when) the run produced a "truly terminating"
     * outcome — i.e. the agent is not going to receive more
     * mailbox messages.  This is the path the [AgentModule] and
     * the [AgentRuntime] use so that cascade-cancel +
     * resource-release run for every agent, not just the root.
     *
     * The hook fires for COMPLETED and ERROR. MAX_ITERATIONS fires
     * it only for ONE_SHOT agents; STANDBY and STANDBY-lifecycle
     * MAX_ITERATIONS remain resumable and do not run cleanup.
     *
     * The hook itself is `suspend` and is invoked under
     * [NonCancellable] so that a parent-side cancellation
     * cannot interrupt the cascade mid-flight (e.g. leaving
     * some grandchildren cancelled and others not).
     *
     * Errors thrown by the hook are caught and logged — they
     * must never propagate back into the run loop and corrupt
     * the [AgentResult] the strategy produced.
     */
    /**
     * Like [run], but additionally fires the
     * [processCompletionLatch] **after** [terminationHook]
     * returns — and only on a truly terminating outcome
     * ([isTerminatingRun] is true). Parked STANDBY runs leave the
     * latch open so the enclosing [AgentModule.startProcess]'s
     * `await()` keeps blocking until a later terminating turn.
     *
     * Latch-timing rationale: the hook is the single chokepoint
     * where every agent runs its cascade-cancel + resource
     * release.  Firing the latch *after* the hook returns (not
     * before) gives [startProcess]'s awaiter the guarantee
     * "startProcess returned ⇒ root fully torn down" — without
     * a half-cancelled child tree or a still-open LLM client.
     * Lifting the latch-fire from [AgentModule] into the agent
     * itself also makes grandchildren cascade correct: a
     * sub-agent that has its own sub-agents now has a real path
     * to signal its own "I am done" to its caller, not just
     * rely on the [OrphanReaper] 60s scan-tick backstop.
     *
     * The latch fires in the same cases as the termination hook:
     * COMPLETED/ERROR, plus ONE_SHOT MAX_ITERATIONS. Parked STANDBY
     * runs keep it open.
     *
     * The hook itself is `suspend` and is invoked under
     * [NonCancellable] so that a parent-side cancellation
     * cannot interrupt the cascade mid-flight (e.g. leaving
     * some grandchildren cancelled and others not).
     *
     * Errors thrown by the hook are caught and logged — they
     * must never propagate back into the run loop and corrupt
     * the [AgentResult] the strategy produced.  The latch
     * STILL fires on hook error so [startProcess] can return
     * (the orphan reaper + a warning log are the
     * observability channels for the failed cleanup step).
     */
    suspend fun runWithTermination(userInput: String): AgentResult {
        val result = run(userInput)
        val hook = terminationHook
        if (hook != null && isTerminatingRun(result.stopReason)) {
            try {
                withContext(NonCancellable) { hook() }
            } catch (e: Exception) {
                logger.error(
                    "Termination hook for agent (session=$sessionId) threw: ${e.message}",
                    e
                )
            }
            // Fire the latch AFTER the hook returns (or after it
            // throws — the caller still gets to return, the hook
            // failure is logged above).  See the matrix on the
            // method doc for which stopReasons / lifecycles
            // reach this point.
            withContext(NonCancellable) {
                processCompletionLatch.complete(deriveExitReason(result))
            }
        }
        return result
    }

    /** Map a terminal [AgentResult] onto [ProcessExitReason]. */
    private fun deriveExitReason(result: AgentResult): ProcessExitReason = when {
        result.stopReason == StopReason.ERROR ||
            result.stopReason == StopReason.MAX_ITERATIONS -> ProcessExitReason.ERROR
        else -> ProcessExitReason.CLOSED
    }

    /**
     * Per-agent resource release — close the LLM client and
     * the transcript writer.  This is the part of the
     * termination flow that belongs to the *agent* (it
     * touches fields only the agent owns); the cascade-cancel
     * and `runtime_state` transition belong to whoever
     * installed the [terminationHook].
     *
     * Wrapped in [NonCancellable] so a parent-side cancellation
     * cannot strand the LLM client open.
     */
    suspend fun close() {
        withContext(NonCancellable) {
            try {
                transcript?.close()
            } catch (e: Exception) {
                logger.warn("Transcript close on $sessionId failed: ${e.message}")
            }
            try {
                client.close()
            } catch (e: Exception) {
                logger.warn("LLM client close on $sessionId failed: ${e.message}")
            }
        }
    }

    /**
     * Cascade-cancel every non-STANDBY descendant of this agent
     * by delegating to the installed [cascadeCanceller].  STANDBY
     * children are deliberately left in place by the canceller
     * — they become orphans and the [OrphanReaper] picks them up
     * on its next scan tick.
     *
     * This is the single chokepoint that the default
     * [terminationHook] uses for the "cancel my own subtree"
     * step.  Callers that install a custom hook can call
     * [cascadeCancel] inline (alongside [close] and any extra
     * steps they need) instead of duplicating the walk-then-
     * cancel loop.
     *
     * Returns the number of children that were actually
     * cancelled.  Zero is a legitimate result — it means either
     * the agent had no live ONE_SHOT descendants (the common
     * case for ONE_SHOT roots that finished without spawning)
     * or no [cascadeCanceller] was installed (standalone test
     * fixtures, agents never wired up to an [AgentRuntime]).
     *
     * No-op (returns 0) when [cascadeCanceller] is null or
     * [sessionId] is blank.  The agent has no fallback to find
     * the runtime on its own — by design, to keep the
     * `AkibaAgent` class decoupled from the per-binary
     * [AgentRuntime] registry.
     */
    suspend fun cascadeCancel(
        reason: String = "parent_terminated",
        graceMs: Long = 30_000L,
    ): Int {
        val canceller = cascadeCanceller ?: return 0
        val sid = sessionId
        if (sid.isNullOrBlank()) return 0
        return try {
            canceller(sid, reason, graceMs)
        } catch (e: Exception) {
            logger.warn(
                "cascadeCancel(session=$sid) failed: ${e.message}",
                e
            )
            0
        }
    }

    /**
     * Default termination sequence — what the constructor's
     * `init` block installs as the [terminationHook] when the
     * caller did not supply one.  Equivalent to "first
     * [cascadeCancel], then [close]".  Each step is wrapped in
     * its own try/catch so a cascade failure cannot strand the
     * LLM client open (and vice versa).
     *
     * Override-friendly: callers that want a custom hook can
     * call [defaultTerminate] first to get the standard
     * cascade + close, then layer their own cleanup on top:
     *
     * ```kotlin
     * agent.terminationHook = {
     *     agent.defaultTerminate()          // cascade + close
     *     unregisterMyTemplates()           // module-specific
     *     setSessionStatus("closed")        // module-specific
     * }
     * ```
     *
     * Equivalently, the [akibaAgent] DSL's `onTermination` block
     * runs with the agent as receiver, so the same effect is
     * available in a fluent form:
     *
     * ```kotlin
     * akibaAgent {
     *     onTermination {
     *         defaultTerminate()             // cascade + close
     *         // ... extra steps ...
     *     }
     * }
     * ```
     */
    suspend fun defaultTerminate() {
        try {
            cascadeCancel()
        } catch (e: Exception) {
            logger.warn("defaultTerminate: cascadeCancel failed: ${e.message}", e)
        }
        try {
            close()
        } catch (e: Exception) {
            logger.warn("defaultTerminate: close failed: ${e.message}", e)
        }
    }

    /**
     * Whether the [runWithTermination] caller should fire its
     * [terminationHook] for the given [stopReason].  See the
     * matrix on [runWithTermination] for the full truth table.
     */
    private fun isTerminatingRun(stopReason: StopReason): Boolean = when (stopReason) {
        StopReason.STANDBY -> false
        StopReason.MAX_ITERATIONS -> lifecycle != Lifecycle.STANDBY
        StopReason.COMPLETED, StopReason.ERROR -> true
    }


    // ---- Process lifecycle hooks ----------------------------------------

    /**
     * Suspend until [AgentModule.startProcess]'s `finally` block
     * finishes every cleanup step and the agent's process is fully
     * terminated.  Returns the [ProcessExitReason] indicating how
     * the run ended.
     *
     * This returns only after cascade-cancel, unregister, transcript
     * close and LLM client close complete. Parked STANDBY runs keep
     * the latch open until a later terminating run.
     *
     * Note: this method is a thin wrapper over
     * [processCompletionLatch]; if you also need to wait for some
     * other coroutine (e.g. a follower module), prefer awaiting
     * the latch directly via `agent.processCompletionLatch.await()`
     * and combining with `coroutineScope` etc.
     */
    suspend fun awaitProcessExit(): ProcessExitReason =
        processCompletionLatch.await()

    /**
     * Non-blocking check — `true` when the agent's enclosing
     * [AgentModule.startProcess] has finished its `finally` block
     * and the process is fully torn down. Parked runs return false.
     * Useful for orchestrators that want to poll instead of suspending.
     */
    fun isProcessTerminated(): Boolean =
        processCompletionLatch.isCompleted

    // ---- Internal --------------------------------------------------------

    private suspend fun executeWithStrategy(): AgentResult {
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
            lastUserContent.startsWith(STANDBY_RESUME_PROMPT_PREFIX) &&
            lastUserContent.endsWith(STANDBY_RESUME_PROMPT_SUFFIX)
        if (isResumed) {
            // Strip the synthetic resume marker from memory WITHOUT
            // clearing the entire conversation history.  The previous
            // implementation called memory.clear() + re-add, which
            // deleted all rows from agent_messages and re-inserted
            // them with only role+content — losing tool_call_id,
            // tool_name, tool_call_args, tool_result, and all other
            // metadata.  This made previous wakes' messages invisible
            // after a page refresh.
            //
            // The resume marker is the last message added by
            // agent.run(userInput), so removeLast() targets exactly
            // that row.  All prior messages stay in the DB with their
            // full metadata intact.
            memory.removeLast()

            // ---- Persist a wake-event marker so the frontend ----
            // ---- can render a separator between wake cycles  ----
            //
            // We insert a lightweight "user" message with a
            // recognisable prefix.  This survives into the DB
            // (memory.clear() + re-add has already completed)
            // and is visible via GET /agent/sessions/{id}/messages.
            // The frontend matches the prefix and renders a
            // divider instead of a normal chat bubble.
            //
            // We try to include *who* triggered the wake by
            // peeking at the unread mailbox messages.  If the
            // mailbox service is unavailable or the query fails,
            // we still write the marker with a generic label.
            val wakeSenders = try {
                val mb = mailboxService
                if (mb != null && sessionId != null) {
                    val unread = mb.peek(sessionId, limit = 10, includeRead = false)
                    if (unread.isNotEmpty()) {
                        unread
                    } else null
                } else null
            } catch (_: Exception) { null }

            val wakeLabel = if (wakeSenders != null && wakeSenders.isNotEmpty()) {
                // Build a concise label: "system" for system-sent
                // messages (sender = 00000000-…), "conv#<id>" for
                // messages from other agents.  The conversation ID
                // is derived from inReplyTo ?: messageId, matching
                // the wake board's conv#<N> convention.
                val parts = wakeSenders.map { msg ->
                    val convId = msg.inReplyToMessageId ?: msg.messageId
                    if (msg.senderSessionId == SYSTEM_SESSION_UUID) "system" else "conv#$convId"
                }.distinct().take(5)
                "Woken by ${parts.joinToString(", ")}"
            } else {
                "Woken by standby resume"
            }
            memory.addUserMessage("[[AKIBA_WAKE_EVENT]] $wakeLabel")
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
            lifecycle = lifecycle,
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
            compactFn = if (compactOnStandby) {
                { compact() }
            } else null,
            cancellationReasonProvider = {
                runtimeHandle?.takeIf { it.cancelRequested }
                    ?.requestedCancelReason
                    ?: runtimeHandle?.takeIf { it.cancelRequested }?.let { "cancelled" }
            },
            pauseCheckProvider = {
                runtimeHandle?.pauseRequested == true
            },
            retryNowRequestedProvider = {
                val handle = runtimeHandle
                if (handle?.retryNowRequested == true) {
                    handle.clearRetryNowRequested()
                    true
                } else false
            },
        )
        // Initialise the fresh LoopStats with cumulative token counts
        // from previous wake cycles so the transcript's "Token Usage
        // (cumulative)" line reflects the true session total, not just
        // the current wake's tokens.
        ctx.stats.totalInputTokens = cumulativeInputTokens
        ctx.stats.totalOutputTokens = cumulativeOutputTokens

        // Wrap the strategy execution with the scratchpad
        // registry on the thread-local so [applyMailboxDrain]
        // can route messages to per-conversation scratchpads
        // and the scheduler can serialise / preempt conversations.
        return withScratchpadRegistry(scratchpadRegistry) {
            val result = strategy.execute(ctx)
            // Persist cumulative counts for the next wake cycle.
            cumulativeInputTokens = ctx.stats.totalInputTokens
            cumulativeOutputTokens = ctx.stats.totalOutputTokens
            result
        }
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

        // 6. Rebuild memory: original system messages → summary → tool call history → kept rounds
        memory.clear()
        systemMsgs.forEach { memory.add(it) }
        memory.addSystemMessage(summaryContent)

        // 6a. Build a compact tool-call history from the compacted-away
        // rounds so the LLM retains visibility into what tools it already
        // called and with what arguments.  This prevents the LLM from
        // repeating the same tool calls after compaction (e.g. re-running
        // disassemble_function on a function it already analyzed).
        val toolHistory = buildToolCallHistory(oldRounds)
        if (toolHistory != null) {
            memory.addSystemMessage(toolHistory)
        }

        keptRounds.flatten().forEach { memory.add(it) }

        logger.info(
            "Compacted context: summarised ${oldRounds.size} rounds into previous_summary, kept ${keptRounds.size} rounds." +
                if (toolHistory != null) " Injected tool_call_history." else ""
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

    /**
     * Maximum number of tool-call entries retained in the compact
     * history injected after compaction.
     */
    private val maxToolHistoryEntries: Int = 30

    /**
     * Maximum byte length of the `args` field per tool-call entry.
     * Arguments exceeding this are truncated with an ellipsis marker.
     */
    private val maxToolArgsBytes: Int = 1000

    /**
     * Build a compact system-message block listing the tool calls from
     * the [oldRounds] being compacted away.  Each entry shows the tool
     * name and its arguments (truncated to [maxToolArgsBytes] bytes).
     * At most [maxToolHistoryEntries] entries are kept; if more exist,
     * a hint is appended telling the LLM to use `read_history_tool_call`
     * for older entries.
     *
     * Returns `null` when [oldRounds] contain no tool messages.
     */
    private fun buildToolCallHistory(oldRounds: List<List<AgentChatMessage>>): String? {
        val toolMsgs = oldRounds.flatten()
            .filter { it.role == "tool" && it.toolName != null }
        if (toolMsgs.isEmpty()) return null

        val entries = toolMsgs.takeLast(maxToolHistoryEntries)
        val truncated = toolMsgs.size > maxToolHistoryEntries
        val skippedCount = if (truncated) toolMsgs.size - maxToolHistoryEntries else 0

        val lines = mutableListOf<String>()
        lines.add("<tool_call_history>")
        lines.add("The following tool calls were made before context compaction.")
        lines.add("Use this list to avoid repeating calls you have already made.")
        if (truncated) {
            lines.add("($skippedCount older calls omitted — use `read_history_tool_call`/`search_history_tool_call` to query them.)")
        }
        lines.add("")
        for (msg in entries) {
            val name = msg.toolName ?: "unknown"
            val args = msg.toolCallArgs ?: ""
            val argsDisplay = if (args.length <= maxToolArgsBytes) {
                args
            } else {
                args.substring(0, maxToolArgsBytes) + "…(truncated)"
            }
            lines.add("- $name($argsDisplay)")
        }
        lines.add("")
        lines.add("If you need to see the full result of any of these calls, use the `read_history_tool_call`/`search_history_tool_call` tool.")
        lines.add("</tool_call_history>")
        return lines.joinToString("\n")
    }
}
