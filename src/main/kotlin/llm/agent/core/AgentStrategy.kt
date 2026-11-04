package org.iotsplab.akiba.llm.agent.core

import java.util.UUID
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.delay
import org.apache.logging.log4j.LogManager
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.agent.context.ContextOverflowDetector
import org.iotsplab.akiba.llm.agent.mailbox.AgentMailboxService
import org.iotsplab.akiba.llm.agent.mailbox.applyMailboxDrain
import org.iotsplab.akiba.llm.agent.template.Lifecycle
import org.iotsplab.akiba.llm.client.AkibaLLMClient
import org.iotsplab.akiba.llm.client.ChatCompletion
import org.iotsplab.akiba.llm.memory.AgentChatMessage
import org.iotsplab.akiba.llm.memory.ChatMemory
import org.iotsplab.akiba.llm.memory.MemoryManager
import org.iotsplab.akiba.llm.memory.MemoryScope
import org.iotsplab.akiba.llm.memory.MemoryType
import org.iotsplab.akiba.llm.tool.core.ToolRegistry

// ============================================================
//  Agent Strategy — interface
// ============================================================

/**
 * Information about a failed LLM call, passed to [StrategyContext.onLLMErrorHook].
 *
 * The hook can inspect this struct to decide whether to recover (e.g. compact
 * memory on the first error), and may also read or update [stats]
 * to track recovery state. It is called once per LLM call (attempt is
 * always 1); the bounded retry loop handles subsequent failures.
 */
data class LLMErrorInfo(
    /** The exception thrown by the LLM client. */
    val exception: Throwable,
    /** 1-based attempt counter for errors within the current run. 1 = first error, 2 = second, ... */
    val attempt: Int,
    /** Total number of LLM calls attempted so far (including this failed one and all previous ones). */
    val totalCalls: Int,
    /** Current estimated token count of the conversation memory at the time of the error. */
    val currentTokens: Int,
    /** Mutable counters for the current loop run. The hook may read or update fields (e.g. `lastError`). */
    val stats: LoopStats
)

/**
 * Strategy that controls how an [AkibaAgent] iterates through the
 * LLM → tool-call → observe cycle.
 *
 * Built-in implementation: [ReActStrategy].  Custom strategies can be
 * created by implementing this interface.
 */
interface AgentStrategy {

    /** Human-readable name for logging. */
    val name: String

    /**
     * Run the agent loop to completion.
     *
     * @param ctx Fully-wired [StrategyContext] providing all dependencies.
     * @return The final [AgentResult].
     */
    suspend fun execute(ctx: StrategyContext): AgentResult
}

/**
 * Bundle of everything a strategy needs, provided by [AkibaAgent].
 * This avoids strategies needing a direct reference to the agent itself.
 */
class StrategyContext(
    val client: AkibaLLMClient,
    val memory: ChatMemory,
    val memoryManager: MemoryManager?,
    val toolRegistry: ToolRegistry,
    val sessionId: String?,
    val systemPrompt: String?,
    val maxIterations: Int,
    val enrichSystemPromptWithMemory: Boolean,
    val auditToolCalls: Boolean,
    val logger: org.apache.logging.log4j.Logger = LogManager.getLogger(StrategyContext::class.java),
    val transcript: AgentTranscriptWriter? = null,
    /** Optional hook invoked before every LLM chat call. Used for threshold-based context compaction. */
    val beforeChatHook: (() -> Unit)? = null,
    /**
     * Optional hook invoked **once** on the first LLM call failure — it may
     * compact context or lower the effective context cap, and is absent by
     * default.
     *
     * Its return value is **informational only**: [StrategyContext.callLLM]
     * always spends its retry budget (`1 + LLMConfig.maxRetries` attempts,
     * 3/5/7 s backoff) regardless of the hook's decision, so a transient
     * provider outage does not end the run immediately, and later retries do
     * not re-invoke it.
     */
    val onLLMErrorHook: ((LLMErrorInfo) -> Boolean)? = null,
    /**
     * Optional hook invoked when the provider rejects the request with a
     * context-length overflow [CONTEXT_OVERFLOW_COMPACT_THRESHOLD] times in a
     * row (see [ContextOverflowDetector]) — the escape hatch for a context
     * that cannot be shrunk enough, where every retry would otherwise resend a
     * request the provider can never accept.
     *
     * Implementations should compact memory — ideally with a fallback that
     * shrinks the context WITHOUT an LLM call, since the summarising call may
     * itself be over the limit — and return true when the context was reduced.
     *
     * May fire repeatedly; after [MAX_FAILED_OVERFLOW_COMPACTIONS] consecutive
     * `false` reports the retry loop aborts (returns null).
     */
    val onContextOverflowHook: ((consecutiveOverflows: Int) -> Boolean)? = null,
    /**
     * Unused.  The retry budget is [org.iotsplab.akiba.llm.client.LLMConfig.maxRetries]
     * (see [LLM_RETRY_BACKOFF_MS]); this parameter is kept only so existing
     * constructor call sites keep compiling.
     */
    val maxLLMErrorRetries: Int = 1,
    /** Agent database client for audit / session updates. */
    val agentDbClient: AgentDatabaseClient? = null,
    /** Optional provider for the compacted context view sent to the model. */
    val contextMessagesProvider: (() -> List<org.iotsplab.akiba.llm.memory.AgentChatMessage>)? = null,
    /** Advisory detector for repeated tool outputs within the same session. */
    val toolResultDuplicateDetector: ToolResultDuplicateDetector? = null,
    /** Optional domain-specific workflow harness. Defaults to no-op. */
    val harness: AgentHarness = DefaultAgentHarness,
    /**
     * Optional mailbox service threaded into [StrategyContext] so the
     * default [AgentHarness.beforeIteration] can drain unread messages
     * before each LLM call. Null disables the drain.
     */
    val mailboxService: AgentMailboxService? = null,
    /**
     * True when this run was triggered by a STANDBY resume
     * (i.e. the user input matches the
     * `[[AKIBA_INTERNAL:STANDBY_RESUME:<uuid>]]` marker produced
     * by [AgentRuntime.newStandbyResumePrompt]).  Strategies can
     * read this to inject a system-prompt hint so the LLM
     * knows "I am being woken by mail, not starting fresh"
     * and skips re-introductions.  False on a cold start.
     */
    val resumedFromStandby: Boolean = false,
    /**
     * Lifecycle the agent was constructed with.  Strategies
     * use this to decide whether to compact-on-park: when
     * [Lifecycle.STANDBY] the agent is going to park, and a
     * smaller memory footprint means a cheaper next resume.
     * When [Lifecycle.ONE_SHOT] the session is terminal so
     * compacting would be wasted work.
     */
    val lifecycle: Lifecycle = Lifecycle.ONE_SHOT,
    /**
     * Callback to compress [memory] in-place, persisting the summary back to
     * the underlying store (DB when `agentDbClient` is set); [AkibaAgent]
     * supplies it via `::compact` so strategies need no back-reference to the
     * agent instance.
     *
     * Null when compact is disabled (e.g. `autoCompact=false` or
     * `compactOnStandby=false`).
     *
     * Not a suspend function: [AkibaAgent.compact] blocks synchronously.
     */
    val compactFn: (() -> Boolean)? = null,
    /** Returns a cancellation reason when the runtime has requested this agent to stop. */
    val cancellationReasonProvider: (() -> String?)? = null,

    /**
     * Returns `true` when the user has requested this agent to be paused.
     *
     * The strategy loop checks this at the top of each iteration (after the
     * current LLM response has been processed) and blocks until the provider
     * returns `false`.
     *
     * Unlike [cancellationReasonProvider], pausing does NOT abort the loop: the
     * agent finishes the in-flight LLM call, processes its response, then
     * blocks before the next call.
     */
    val pauseCheckProvider: (() -> Boolean)? = null,

    /**
     * Returns `true` when the user has requested an immediate LLM retry
     * (skipping the remaining backoff).  The [callLLM] retry loop polls
     * this every 500ms during the backoff delay and breaks out early
     * when it returns `true`.
     */
    val retryNowRequestedProvider: (() -> Boolean)? = null,
) {
    /** Accumulated counters during the loop. Mutable, shared across the strategy. */
    @Suppress("LeakingThis")
    val stats: LoopStats = LoopStats()

    /**
     * Transient user messages for the **current LLM call only**: wake boards
     * must be visible to the immediate call but NOT persisted into chat history
     * or compacted summaries, or old boards linger and confuse later turns.
     *
     * [applyHarnessDirective] writes them here instead of [memory]; [callLLM]
     * appends them to the outgoing message list and clears them in `finally`.
     */
    private val transport = LlmTransport(
        client = client,
        memory = memory,
        toolRegistry = toolRegistry,
        stats = stats,
        sessionId = sessionId,
        logger = logger,
        contextMessagesProvider = contextMessagesProvider,
        agentDbClient = agentDbClient,
        beforeChatHook = beforeChatHook,
        onLLMErrorHook = onLLMErrorHook,
        onContextOverflowHook = onContextOverflowHook,
        cancellationReasonProvider = cancellationReasonProvider,
        retryNowRequestedProvider = retryNowRequestedProvider,
    )

    /** Queue a user message that is visible to the next LLM call but never stored in [memory]. */
    fun addTransientUserMessage(content: String) = transport.addTransientUserMessage(content)

    /**
     * Send the conversation to the provider, returning the completion or null
     * when the run was cancelled or the retry budget ran out.
     *
     * See [LlmTransport.callLLM] for the retry and recovery rules.
     */
    suspend fun callLLM(systemPrompt: String): ChatCompletion? = transport.callLLM(systemPrompt)

    /** Build the effective system prompt with optional memory enrichment. */
    fun buildEffectiveSystemPrompt(extraInject: String? = null): String {
        var base = systemPrompt ?: ""

        if (enrichSystemPromptWithMemory && memoryManager != null) {
            val memoryContext = memoryManager.contextSummary()
            if (memoryContext.isNotBlank()) {
                base = "$base\n\n## Relevant Memories\n$memoryContext"
            }
        }

        if (!extraInject.isNullOrBlank()) {
            base = "$base\n\n$extraInject"
        }

        return base
    }






    /**
     * Execute a tool call and return the result string.
     *
     * Tool calls are NOT cached or deduplicated: many tools are not
     * idempotent (e.g. anything that mutates program state, queries
     * timestamps, or depends on prior side-effects of other tools), so
     * repeating the "same" call at a later point may legitimately yield
     * a different result. We always re-execute.
     */
    /** Result of executing a single tool call. */
    data class ToolResult(
        val output: String,
        val durationMs: Long,
        val rawOutput: String = output,
        /** Duplicate detection result for this tool call, if a
         *  detector is configured. Null when no detector or when
         *  the result was exempted (short output, etc.). */
        val duplicateDetection: ToolResultDuplicateDetection? = null,
    )

    fun executeToolWithDuration(toolCall: ParsedToolCall): ToolResult {
        val tool = toolRegistry.get(toolCall.name)
        if (tool == null) {
            val errMsg = "Unknown tool: ${toolCall.name}. Available: ${toolRegistry.names()}"
            transcript?.writeToolResult(toolCall.name, errMsg)
            return ToolResult(errMsg, 0L)
        }

        // Write tool call to transcript
        transcript?.writeToolCall(
            toolCall.name, toolCall.argumentsJson, toolCall.arguments, stats.iterations
        )

        var rawResult: String
        val durationMs = measureTimeMillis {
            rawResult = tool.safeExecute(toolCall.arguments)
        }

        stats.toolCallsMade++

        // Write tool result to transcript
        transcript?.writeToolResult(toolCall.name, rawResult, durationMs)

        val resultUuid = UUID.randomUUID().toString()
        val stored = ToolResultContext.prepareForStorage(rawResult)

        val isError = rawResult.startsWith("Tool '${toolCall.name}' execution error")
        val duplicateDetection = toolResultDuplicateDetector?.inspect(
            ToolResultInspectionRequest(
                sessionId = sessionId,
                iteration = stats.iterations,
                toolCall = toolCall,
                resultUuid = resultUuid,
                stored = stored,
                isError = isError,
                dedupStrategy = tool.dedupStrategyResolver?.invoke(toolCall.arguments) ?: tool.dedupStrategy,
            )
        )

        // Audit to database and store the retrievable result snapshot.
        // Record FIRST — if this fails, we must NOT include result_uuid
        // in the observation, otherwise the LLM would try
        // read_history_tool_call and get a 404 (the UUID was never stored).
        var resultStored = false
        if (auditToolCalls && sessionId != null && agentDbClient != null) {
            try {
                agentDbClient.recordToolCall(
                    sessionId = sessionId,
                    toolCallId = toolCall.callId,
                    toolName = toolCall.name,
                    toolArgs = toolCall.argumentsJson,
                    resultUuid = resultUuid,
                    resultSummary = rawResult.take(2000),
                    resultContent = stored.content,
                    resultOriginalBytes = stored.originalBytes,
                    resultStoredBytes = stored.storedBytes,
                    resultTruncated = stored.truncated,
                    resultSha256 = stored.sha256,
                    storagePolicy = stored.storagePolicy,
                    success = !isError,
                    durationMs = durationMs
                )
                resultStored = true
            } catch (_: Exception) {}
        }

        // Format the observation — only include result_uuid if the
        // result was successfully stored and can be retrieved later.
        var result = ToolResultContext.formatCurrentResult(
            rawResult,
            if (resultStored) resultUuid else null,
            stored
        )
        duplicateDetection?.toObservationPrefix(toolCall.name)?.let { prefix ->
            result = "$prefix\n\n$result"
        }

        // Auto-remember significant tool results
        if (memoryManager != null && auditToolCalls &&
            result.length >= 100 &&
            !rawResult.startsWith("Tool '${toolCall.name}' execution error")
        ) {
            memoryManager.remember(
                content = "[${toolCall.name}] ${result.take(500)}",
                type = MemoryType.FINDING,
                scope = MemoryScope.SESSION,
                key = "tool:${toolCall.name}",
                importance = 0.6
            )
        }

        return ToolResult(result, durationMs, rawResult, duplicateDetection)
    }

    // Backward-compatible wrapper
    fun executeTool(toolCall: ParsedToolCall): String = executeToolWithDuration(toolCall).output

    /**
     * Update session status in the database.
     *
     * Mirrors the new value to BOTH the legacy `status` column and
     * the canonical `runtime_state` column.  Without the second
     * write, an agent that reaches the "Enter standby mode."
     * marker only flips `status`, leaving `runtime_state` on
     * `running`; the frontend's
     * `GET /agent/sessions/{id}` route reports
     * `runtime_state` as the source of truth, so the status pill
     * in the chat header would stay on "Running" for the rest of
     * the session's lifetime even though the agent has parked.
     *
     * `runtime_state` is the schema's authoritative column —
     * `status` is kept for legacy clients that still read it.
     * The two columns must agree at every observer-visible
     * point; this helper is the single chokepoint that the
     * strategies use, so doing both writes here is sufficient.
     */
    fun updateSessionStatus(status: String) {
        updateSessionStatus(status, reason = null)
    }

    /**
     * Same as [updateSessionStatus] but lets the caller override the
     * `closing_reason` stored alongside the runtime_state. Use this
     * on error / failure paths so the DB row carries the real error
     * detail (which the frontend surfaces in its error banner) rather
     * than the generic `"llm_status:<status>"` placeholder.
     *
     * Pass `null` (or use the no-arg overload) for non-error
     * transitions where the placeholder is fine.
     */
    fun updateSessionStatus(status: String, reason: String?) {
        if (sessionId != null && agentDbClient != null) {
            try {
                agentDbClient.updateSession(sessionId, status = status)
            } catch (_: Exception) {}
            // Mirror onto runtime_state.  Map the strategy's
            // status vocabulary onto the runtime_state enum
            // (closed vs cancelled, error vs failed) so the
            // canonical column is never left holding an
            // out-of-vocabulary value.
            val rs = mapStatusToRuntimeState(status)
            if (rs != null) {
                val closingReason = reason ?: "llm_status:$status"
                try {
                    agentDbClient.setRuntimeState(
                        sessionId = sessionId,
                        runtimeState = rs,
                        closingReason = closingReason,
                    )
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Map the strategy's status vocabulary (which still carries
     * legacy terms like "completed" and "failed") onto the
     * canonical runtime_state enum.  Returns null when the
     * value is not a runtime state and should be left to the
     * legacy `status` column only.
     */
    private fun mapStatusToRuntimeState(status: String): String? = when (status.lowercase()) {
        "running" -> "running"
        "standby" -> "standby"
        "msghandle" -> "msghandle"
        "cancelling" -> "cancelling"
        "closed", "completed" -> "closed"
        "cancelled" -> "closed"
        "error", "failed" -> "error"
        else -> null
    }
}

/** Mutable counters shared across a strategy execution. */
class LoopStats {
    var iterations: Int = 0
    var toolCallsMade: Int = 0
    var totalInputTokens: Int = 0
    var totalOutputTokens: Int = 0
    var lastError: String? = null
    /**
     * Set to true when the LLM calls `await_condition` during this
     * run.  When the LLM then produces an answer, the
     * strategy checks this flag: if true, the run ends with
     * [StopReason.STANDBY] (the agent parks and waits for the
     * registered wake condition); if false, the run ends with
     * [StopReason.COMPLETED] (the agent is truly done).
     *
     * The LLM expresses "I want to park" by calling `await_condition`.
     */
    var awaitConditionRegistered: Boolean = false

    /**
     * Consecutive tool calls that returned a WARNING-level
     * duplicate detection. Reset to 0 whenever a non-duplicate
     * (or only NOTICE-level) tool call is observed.
     *
     * When this counter reaches
     * [DUPLICATE_LOOP_FORCE_COMPACT_THRESHOLD], the strategy
     * forces a context compaction and injects a hard break
     * instruction to disrupt the loop.
     */
    var consecutiveDuplicateWarnings: Int = 0
    /**
     * Total times a forced compaction was triggered by the
     * duplicate-loop guard. Capped at
     * [MAX_FORCED_COMPACTIONS] to prevent infinite
     * compaction loops.
     */
    var forcedCompactions: Int = 0

    /**
     * Consecutive iterations whose reply neither called a tool nor was
     * accepted as an answer.
     *
     * Reset whenever a tool call is executed.  For providers that use the
     * **text** tool protocol (no native function calling), reaching
     * [MAX_NO_ACTION_TURNS] means the model answered in prose without the
     * expected marker — the run then accepts that prose as the final answer
     * instead of looping until [StrategyContext.maxIterations].
     */
    var consecutiveNoActionTurns: Int = 0

    /**
     * Set once the wake board has AGED its messages during THIS
     * execution (see applyMailboxDrain).  A strategy execution == one
     * wake: aging must happen exactly once per wake, otherwise the
     * "seen N wakes" escalation counter actually counts ReAct
     * *iterations* and long-running requests get falsely escalated
     * while the agent is actively working on them (observed: a
     * native-analysis request "escalated" 11 times during a single
     * continuous analysis, pressuring premature replies).
     */
    var wakeBoardAged: Boolean = false
}

// ============================================================
//  ParsedToolCall — unified tool-call representation
// ============================================================

/**
 * A tool call parsed from an LLM response, regardless of how the
 * provider encodes it (native function_call, JSON in text, etc.).
 */
data class ParsedToolCall(
    val callId: String,
    val name: String,
    val arguments: Map<String, Any?>,
    val argumentsJson: String
)

// ============================================================
//  ReAct Strategy
// ============================================================
