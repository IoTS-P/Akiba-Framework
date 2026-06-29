package org.iotsplab.akiba.llm.agent

import org.apache.logging.log4j.LogManager
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.client.AkibaLLMClient
import org.iotsplab.akiba.llm.client.ChatCompletion
import org.iotsplab.akiba.llm.memory.ChatMemory
import org.iotsplab.akiba.llm.memory.MemoryManager
import org.iotsplab.akiba.llm.memory.MemoryType
import org.iotsplab.akiba.llm.memory.MemoryScope
import org.iotsplab.akiba.llm.tool.ToolCallParser
import org.iotsplab.akiba.llm.tool.ToolRegistry
import java.util.UUID
import kotlin.system.measureTimeMillis

// ============================================================
//  Agent Strategy — interface
// ============================================================

/**
 * Information about a failed LLM call, passed to [StrategyContext.onLLMErrorHook].
 *
 * The hook can inspect this struct to decide whether to retry (e.g. compact
 * memory on the first error but give up on subsequent ones), and may also
 * read or update [stats] to track recovery state.
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
 * Built-in implementations:
 * - [ReActStrategy] — explicit Thought → Action → Observation loop
 * - [PlanExecuteStrategy] — plan first, then execute each step
 *
 * Custom strategies can be created by implementing this interface.
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
    fun execute(ctx: StrategyContext): AgentResult
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
     * Optional hook invoked **on every LLM call failure** (not just the first).
     *
     * The hook may:
     * - Inspect the failure context via [LLMErrorInfo] (exception, attempt, token count, ...).
     * - Perform side effects (e.g. compact memory, lower effective context cap).
     * - Return `true` to retry the same LLM call, or `false` to propagate the error.
     *
     * The hook is invoked at most [maxLLMErrorRetries] times per failed call to
     * prevent infinite loops. Implementations can gate their own policy using
     * [LLMErrorInfo.attempt] (e.g. only retry on the first error to keep the
     * previous "single compaction attempt" behavior).
     *
     * If null (default), the first LLM error is propagated without retry,
     * matching pre-recovery behavior.
     */
    val onLLMErrorHook: ((LLMErrorInfo) -> Boolean)? = null,
    /**
     * Maximum number of times [onLLMErrorHook] may request a retry per failed
     * LLM call. Defaults to 1 so an accidentally-infinite hook cannot lock up
     * the loop. Set higher only if the hook is well-tested and uses
     * [LLMErrorInfo.attempt] to bound its own behavior.
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
     * Callback to compress [memory] in-place, persisting the
     * summary back to the underlying store (DB when
     * `agentDbClient` is set).  Provided by [AkibaAgent] via
     * `::compact` so strategies do not need a back-reference
     * to the agent instance.  Null when compact is disabled
     * (e.g. `autoCompact=false` or `compactOnStandby=false`).
     *
     * Not a suspend function: [AkibaAgent.compact] blocks
     * synchronously on the LLM call.
     */
    val compactFn: (() -> Boolean)? = null,
    /** Returns a cancellation reason when the runtime has requested this agent to stop. */
    val cancellationReasonProvider: (() -> String?)? = null,
) {
    /** Accumulated counters during the loop. Mutable, shared across the strategy. */
    @Suppress("LeakingThis")
    val stats: LoopStats = LoopStats()

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
     * Call the LLM with the current conversation history.
     *
     * If [onLLMErrorHook] is provided and the provider call fails, the hook is
     * invoked (up to [maxLLMErrorRetries] times) with full [LLMErrorInfo]
     * context. The hook decides whether to retry the same call; typical
     * implementations compact memory on the first error to recover from
     * underestimated token usage, then lower the effective context cap so the
     * next iteration compacts earlier. If the hook returns false (or is null)
     * the error is propagated as a normal LLM failure.
     *
     * @return the completion, or null on unrecoverable error (error is logged).
     */
    fun callLLM(systemPrompt: String): ChatCompletion? {
        fun invokeChat(): ChatCompletion = client.chat(
            systemPrompt = systemPrompt,
            messages = contextMessagesProvider?.invoke() ?: memory.messages(),
            tools = if (toolRegistry.isEmpty()) null else toolRegistry.toJsonSchemas()
        )

        cancellationReasonProvider?.invoke()?.let { reason ->
            stats.lastError = "Agent cancelled before LLM call: $reason"
            logger.warn(stats.lastError)
            return null
        }
        beforeChatHook?.invoke()
        cancellationReasonProvider?.invoke()?.let { reason ->
            stats.lastError = "Agent cancelled before LLM call: $reason"
            logger.warn(stats.lastError)
            return null
        }
        return try {
            invokeChat()
        } catch (initialError: Exception) {
            stats.lastError = initialError.message
            logger.warn("LLM chat failed: ${initialError.message}")

            val hook = onLLMErrorHook ?: return null

            var errorAttempt = 0
            while (errorAttempt < maxLLMErrorRetries) {
                errorAttempt++
                val currentTokens = memory.messages().sumOf { client.estimateTokenCount(it.content) }
                val info = LLMErrorInfo(
                    exception = initialError,
                    attempt = errorAttempt,
                    totalCalls = stats.iterations + 1,
                    currentTokens = currentTokens,
                    stats = stats
                )

                logger.warn(
                    "Invoking onLLMErrorHook (attempt $errorAttempt/$maxLLMErrorRetries, " +
                        "currentTokens=$currentTokens): ${initialError.message}"
                )

                val shouldRetry = try {
                    hook(info)
                } catch (hookError: Exception) {
                    stats.lastError = "${initialError.message}; recovery hook failed: ${hookError.message}"
                    logger.warn("onLLMErrorHook threw: ${hookError.message}", hookError)
                    return null
                }

                if (!shouldRetry) {
                    logger.info("onLLMErrorHook declined retry on attempt $errorAttempt; propagating error.")
                    return null
                }

                logger.info("onLLMErrorHook requested retry (attempt $errorAttempt/$maxLLMErrorRetries); retrying LLM chat.")
                try {
                    return invokeChat()
                } catch (retryError: Exception) {
                    stats.lastError = "${initialError.message}; retry #$errorAttempt failed: ${retryError.message}"
                    logger.warn("LLM chat retry #$errorAttempt failed: ${retryError.message}", retryError)
                    // Fall through to call the hook again with the same original error.
                }
            }

            logger.warn("onLLMErrorHook retry budget exhausted after $maxLLMErrorRetries retries; giving up.")
            null
        }
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
        val durationMs: Long
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
                isError = isError
            )
        )
        var result = ToolResultContext.formatCurrentResult(rawResult, resultUuid, stored)
        duplicateDetection?.toObservationPrefix(toolCall.name)?.let { prefix ->
            result = "$prefix\n\n$result"
        }

        // Audit to database and store the retrievable result snapshot.
        if (auditToolCalls && sessionId != null && agentDbClient != null) {
            try {
                agentDbClient.recordToolCall(
                    sessionId = sessionId,
                    toolCallId = toolCall.callId,
                    toolName = toolCall.name,
                    toolArgs = toolCall.argumentsJson,
                    resultUuid = resultUuid,
                    resultSummary = result.take(2000),
                    resultContent = stored.content,
                    resultOriginalBytes = stored.originalBytes,
                    resultStoredBytes = stored.storedBytes,
                    resultTruncated = stored.truncated,
                    resultSha256 = stored.sha256,
                    storagePolicy = stored.storagePolicy,
                    success = !rawResult.startsWith("Tool '${toolCall.name}' execution error"),
                    durationMs = durationMs
                )
            } catch (_: Exception) {}
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

        return ToolResult(result, durationMs)
    }

    // Backward-compatible wrapper
    fun executeTool(toolCall: ParsedToolCall): String = executeToolWithDuration(toolCall).output

    /** Update session status in the database. */
    fun updateSessionStatus(status: String) {
        if (sessionId != null && agentDbClient != null) {
            try {
                agentDbClient.updateSession(sessionId, status = status)
            } catch (_: Exception) {}
        }
    }
}

/** Mutable counters shared across a strategy execution. */
class LoopStats {
    var iterations: Int = 0
    var toolCallsMade: Int = 0
    var totalInputTokens: Int = 0
    var totalOutputTokens: Int = 0
    var lastError: String? = null
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

/**
 * ReAct (Reasoning + Acting) strategy.
 *
 * The agent follows an explicit **Thought → Action → Observation** cycle:
 *
 * ```
 * ┌──────────────────────────────────────────────┐
 * │  THOUGHT: I need to find the entry point.    │
 * │  ACTION:  list_functions()                   │
 * │  OBSERVATION: main @ 0x401234, foo @ 0x401256│
 * │  THOUGHT: The entry point is main...         │
 * │  ACTION:  decompile(address="0x401234")      │
 * │  OBSERVATION: int main() { return 0; }       │
 * │  THOUGHT: I now have the answer.             │
 * │  FINAL ANSWER: The binary's entry point...   │
 * └──────────────────────────────────────────────┘
 * ```
 *
 * The LLM is instructed to follow this structured format.  On each
 * iteration:
 *
 * 1. The LLM reasons about the current state (THOUGHT)
 * 2. It either calls a tool (ACTION) or gives the final answer
 * 3. If a tool was called, the result is injected as OBSERVATION
 * 4. The loop repeats until a final answer or [maxIterations]
 *
 * This strategy is ideal for tasks requiring step-by-step reasoning
 * and tool usage, such as binary analysis, debugging, or research.
 */
class ReActStrategy : AgentStrategy {

    override val name: String = "ReAct"

    companion object {
        /**
         * Maximum number of tool calls executed sequentially in a single
         * iteration before requesting another LLM round-trip. Defined in
         * [AgentPrompts] (it is also referenced inside the ReAct instruction).
         */
        const val MAX_BATCH_TOOL_CALLS: Int = AgentPrompts.MAX_BATCH_TOOL_CALLS

        /** The system prompt supplement that instructs the LLM to follow ReAct. */
        val REACT_INSTRUCTION: String get() = AgentPrompts.REACT_INSTRUCTION
    }

    /**
     * Built-in ReAct harness for generic recovery behaviours that used to live
     * inline in [execute]. Domain harnesses supplied by modules are still run
     * first; this one only handles ReAct's default no-action recovery.
     */
    private object DefaultReActHarness : AgentHarness {
        override val name: String = "DefaultReActHarness"

        override fun afterNoAction(
            ctx: StrategyContext,
            assistantText: String,
            completion: ChatCompletion?
        ): AgentHarnessDirective {
            if (completion?.finishReason == "length") {
                ctx.logger.warn("[ReAct] LLM response truncated (finishReason=length). Requesting continuation instead of ending loop.")
                return AgentHarnessDirective.userMessage(
                    "Your previous response was truncated because it exceeded the output length limit. " +
                        "Continue from where you left off. If you had started a tool call, please repeat it now."
                )
            }

            ctx.logger.info(
                "[ReAct] No tool call or final answer detected. Sending format reminder. " +
                    "Text preview: ${assistantText.take(300)}"
            )
            val toolNames = ctx.toolRegistry.names().joinToString(", ")
            return AgentHarnessDirective.userMessage(AgentPrompts.formatReminder(toolNames))
        }
    }

    override fun execute(ctx: StrategyContext): AgentResult {
        val logger = ctx.logger

        logger.info("[ReAct] Harness: ${ctx.harness.name}")
        ctx.applyHarnessDirective(ctx.harness.beforeRun(ctx), "harness.beforeRun")

        // Write system prompt to transcript (only on first iteration)
        ctx.transcript?.writeSystemPrompt(ctx.buildEffectiveSystemPrompt(REACT_INSTRUCTION))

        while (ctx.stats.iterations < ctx.maxIterations) {
            ctx.stats.iterations++
            logger.debug("[ReAct] iteration ${ctx.stats.iterations}/${ctx.maxIterations}")

            val beforeIteration = ctx.harness.beforeIteration(ctx)
            ctx.applyHarnessDirective(beforeIteration, "harness.beforeIteration")
            val beforeChat = ctx.harness.beforeChat(ctx)
            ctx.applyHarnessDirective(beforeChat, "harness.beforeChat")
            val resumeHint = if (ctx.resumedFromStandby) {
                "[system: you are being woken from standby by new mailbox messages; " +
                    "the default harness has drained the unread messages into this turn " +
                    "as user messages. Continue from where you parked — do NOT re-introduce " +
                    "yourself or restart the task.]"
            } else null
            val systemPrompt = ctx.buildEffectiveSystemPrompt(
                joinPromptParts(
                    REACT_INSTRUCTION,
                    beforeIteration.systemPromptAppend,
                    beforeChat.systemPromptAppend,
                    resumeHint,
                )
            )

            val completion = ctx.callLLM(systemPrompt) ?: run {
                ctx.updateSessionStatus("error")
                return ctx.stats.toResult(
                    output = buildErrorOutput(
                        errorLabel = "Agent error",
                        errorDetail = ctx.stats.lastError,
                        memory = ctx.memory,
                    ),
                    stopReason = StopReason.ERROR
                )
            }

            completion.tokenUsage?.let { usage ->
                ctx.stats.totalInputTokens += usage.inputTokenCount
                ctx.stats.totalOutputTokens += usage.outputTokenCount
            }

            // When the provider uses native function calling, completion.content is often
            // empty (no visible Thought/Action text). We synthesise a ReAct-formatted
            // assistant message so the conversation history remains complete for the LLM.
            val assistantText = when {
                completion.toolCalls.isNotEmpty() && completion.content.isBlank() -> {
                    val actions = completion.toolCalls.joinToString("\n") { tc ->
                        "```json\n{\"tool_call\": {\"name\": \"${tc.name}\", \"arguments\": ${tc.argumentsJson}}}\n```"
                    }
                    "**Thought:** Invoking ${completion.toolCalls.size} tool call(s).\n\n**Action:**\n$actions"
                }
                else -> ToolCallParser.stripThinking(completion.content)
            }
            ctx.memory.addAssistantMessage(
                assistantText,
                tokenCount = completion.tokenUsage?.outputTokenCount,
                inputTokenCount = completion.tokenUsage?.inputTokenCount
            )

            logger.info("[ReAct] Assistant (${assistantText.length} chars): ${assistantText.take(300)}...")
            ctx.transcript?.writeAssistantMessage(
                assistantText, ctx.stats.iterations,
                ctx.stats.totalInputTokens, ctx.stats.totalOutputTokens
            )
            ctx.applyHarnessDirective(
                ctx.harness.afterAssistantMessage(ctx, assistantText, completion),
                "harness.afterAssistantMessage"
            )

            // Parse tool calls.  Final-Answer check MUST come first
            // because the LLM's Final Answer payload is itself JSON and
            // can contain nested objects with `name` / `tool` keys that
            // would otherwise be misclassified as tool calls.  Without
            // this precedence fix, sub-agents that emit a structured JSON
            // final answer (e.g. linear_checker's
            // `{ "suspiciousFunctions": [ { "name": "...", ... } ] }`)
            // trigger spurious tool calls, the agent thinks the LLM has
            // not finished, and the loop runs until maxIterations.
            //
            // Native provider tool calls (`completion.toolCalls`) are
            // untouched by this fix: providers that support native
            // function calling do not interleave tool calls with
            // Final-Answer text in a single completion, so the parser's
            // native path is the source of truth in that case.
            val finalAnswerText = extractFinalAnswer(assistantText)

            // Standby marker takes precedence over tool-call parsing
            // and over `afterNoAction` (Final Answer already wins
            // over standby marker per [SharedStandbyExtractor]'s
            // contract).  When the LLM ends its reply with
            // "Enter standby mode." and did not emit a Final
            // Answer, we exit the run() loop immediately with
            // [StopReason.STANDBY].  The runtime translates that
            // to `runtime_state=standby` for `lifecycle=standby`
            // children and to `closed` for one-shot children, so
            // a standby agent parks and waits for mailbox; a
            // one-shot agent terminates normally.
            if (finalAnswerText == null && SharedStandbyExtractor.endsWithMarker(assistantText)) {
                logger.info(
                    "[ReAct] Standby marker detected at end of assistant text; " +
                        "exiting run() with StopReason.STANDBY (lifecycle=${ctx.lifecycle})."
                )
                ctx.updateSessionStatus("standby")
                val result = ctx.stats.toResult(
                    output = SharedStandbyExtractor.MARKER,
                    stopReason = StopReason.STANDBY,
                )
                ctx.transcript?.writeSessionEnd(result)
                return compactAndReturn(ctx, result)
            }

            val allToolCalls = when {
                completion.toolCalls.isNotEmpty() ->
                    ToolCallParser.parseAllFromCompletion(completion)
                finalAnswerText != null ->
                    // Final Answer marker present — skip text-based tool
                    // call parsing to avoid false positives from JSON
                    // snippets in the answer payload.
                    emptyList()
                else ->
                    ToolCallParser.parseAllFromCompletion(completion).ifEmpty {
                        ToolCallParser.parseAll(assistantText)
                    }
            }

            if (allToolCalls.isNotEmpty()) {
                val toolCallsDirective = ctx.harness.beforeToolCalls(ctx, allToolCalls)
                ctx.applyHarnessDirective(toolCallsDirective, "harness.beforeToolCalls")
                if (toolCallsDirective.blockCurrentAction) {
                    logger.warn("[ReAct] Harness blocked current tool-call batch")
                    continue
                }

                // Cap the batch size so a single response can't blow through the
                // iteration budget or overwhelm downstream tools.
                val batch = allToolCalls.take(MAX_BATCH_TOOL_CALLS)
                if (allToolCalls.size > MAX_BATCH_TOOL_CALLS) {
                    logger.warn("[ReAct] LLM emitted ${allToolCalls.size} tool calls in one response, " +
                        "capping at $MAX_BATCH_TOOL_CALLS")
                }

                logger.info("[ReAct] Executing ${batch.size} tool call(s) in this iteration")

                for ((idx, toolCall) in batch.withIndex()) {
                    logger.info("[ReAct] Action ${idx + 1}/${batch.size}: " +
                        "${toolCall.name}(${toolCall.argumentsJson.take(200)})")
                    val beforeTool = ctx.harness.beforeToolExecution(ctx, toolCall)
                    if (beforeTool.blockCurrentAction) {
                        ctx.applyHarnessDirective(beforeTool, "harness.beforeToolExecution")
                        logger.warn("[ReAct] Harness blocked tool call: ${toolCall.name}")
                        continue
                    }

                    val toolResult = if (beforeTool.skipCurrentAction) {
                        ctx.applyHarnessDirective(beforeTool, "harness.beforeToolExecution")
                        val synthetic = beforeTool.userMessages.firstOrNull() ?: "[harness synthetic] handled: ${toolCall.name}"
                        StrategyContext.ToolResult(synthetic, 0L)
                    } else {
                        ctx.executeToolWithDuration(toolCall)
                    }
                    val observation = toolResult.output
                    val durationMs = toolResult.durationMs
                    val durationStr = if (durationMs > 0) ", duration=${durationMs}ms" else ""

                    val obsMessage = if (batch.size == 1) {
                        "**Observation (${toolCall.name}, args=${toolCall.argumentsJson}$durationStr):** $observation"
                    } else {
                        "**Observation (call ${idx + 1}/${batch.size}, ${toolCall.name}, args=${toolCall.argumentsJson}$durationStr):** $observation"
                    }
                    ctx.memory.addToolMessage(
                        toolCallId = toolCall.callId,
                        toolName = toolCall.name,
                        args = toolCall.argumentsJson,
                        result = obsMessage
                    )
                    if (!beforeTool.skipCurrentAction) {
                        ctx.applyHarnessDirective(beforeTool, "harness.beforeToolExecution")
                    }
                    ctx.applyHarnessDirective(
                        ctx.harness.afterToolExecution(ctx, toolCall, toolResult),
                        "harness.afterToolExecution"
                    )

                    logger.debug("[ReAct] Observation: ${observation.take(200)}...")
                }

                // If we capped the batch, hint to the LLM that some calls were dropped
                if (allToolCalls.size > MAX_BATCH_TOOL_CALLS) {
                    ctx.memory.addUserMessage(
                        AgentPrompts.batchTruncatedNote(allToolCalls.size, MAX_BATCH_TOOL_CALLS)
                    )
                }
            } else {
                // No tool call detected — `finalAnswerText` was computed
                // earlier (it short-circuited the tool-call parser), so
                // reuse it here instead of re-scanning `assistantText`.
                val finalAnswer = finalAnswerText

                if (finalAnswer != null) {
                    val finalAnswerDirective = ctx.harness.validateFinalAnswer(ctx, assistantText, finalAnswer)
                    ctx.applyHarnessDirective(finalAnswerDirective, "harness.validateFinalAnswer")
                    if (!finalAnswerDirective.rejectFinalAnswer) {
                        // LLM explicitly signaled it's done and harness accepted it.
                        ctx.updateSessionStatus("closed")
                        val result = ctx.stats.toResult(
                            output = finalAnswer,
                            stopReason = StopReason.COMPLETED
                        )
                        ctx.transcript?.writeSessionEnd(result)
                        return compactAndReturn(ctx, result)
                    }
                    logger.warn("[ReAct] Harness rejected Final Answer; continuing loop")
                    continue
                }

                ctx.applyHarnessDirective(
                    ctx.harness.afterNoAction(ctx, assistantText, completion),
                    "harness.afterNoAction"
                )
                ctx.applyHarnessDirective(
                    DefaultReActHarness.afterNoAction(ctx, assistantText, completion),
                    "react.afterNoAction"
                )
            }
        }

        // Max iterations
        logger.warn("[ReAct] reached max iterations (${ctx.maxIterations})")
        ctx.updateSessionStatus("error")
        val result = ctx.stats.toResult(
            output = extractLastAnswer(ctx.memory)
                ?: "Agent reached maximum iterations without producing a final answer.",
            stopReason = StopReason.MAX_ITERATIONS
        )
        ctx.transcript?.writeSessionEnd(result)
        return compactAndReturn(ctx, result)
    }

    /** Extract the Final Answer portion from a ReAct response. */
    private fun extractFinalAnswer(text: String): String? =
        SharedFinalAnswerExtractor.extract(text)

    private fun extractLastAnswer(memory: ChatMemory): String? {
        return memory.messages().lastOrNull { it.role == "assistant" }?.content
    }
}

/**
 * Compact-on-park helper for the default strategies.
 *
 * When the session is going to park (i.e. [StrategyContext.lifecycle]
 * is [Lifecycle.STANDBY] and the run is producing a terminal
 * result), call [StrategyContext.compactFn] to shrink the
 * in-memory history into a single summary message + the
 * trailing rounds.  The compact writes back to the persistent
 * store (when `agentDbClient` is set), so the next
 * `runtime.resumeStandby` runs against a small, summarisable
 * context — cheaper to send and cheaper to reason over.
 *
 * Inactive when:
 *  - lifecycle is ONE_SHOT (session is terminal; compacting is
 *    wasted work and the persistent memory is not consulted
 *    again),
 *  - the strategy was constructed with `compactOnStandby=false`,
 *  - `compactFn` is null,
 *  - the underlying [AkibaAgent.compact] call returns false
 *    (nothing to compact).
 *
 * Errors are caught and logged: a compact failure must not
 * prevent the agent from returning its result and parking.
 *
 * Not a suspend function: the inner LLM call inside
 * [AkibaAgent.compact] is synchronous from the strategy's
 * point of view (the LLM client blocks on the HTTP exchange).
 * The strategy's execute() therefore does not need to grow
 * a `suspend` modifier.
 */
internal fun compactAndReturn(
    ctx: StrategyContext,
    result: AgentResult,
): AgentResult {
    val compactFn = ctx.compactFn ?: return result
    if (ctx.lifecycle != Lifecycle.STANDBY) return result
    try {
        val did = compactFn()
        if (did) {
            ctx.logger.info(
                "[strategy] compacted on park (lifecycle=STANDBY, " +
                    "iterations=${ctx.stats.iterations}, toolCalls=${ctx.stats.toolCallsMade})"
            )
        }
    } catch (e: Exception) {
        ctx.logger.warn(
            "[strategy] compact-on-park failed: ${e.javaClass.simpleName}: ${e.message}",
            e,
        )
    }
    return result
}

// ============================================================
//  Plan-Execute Strategy
// ============================================================

/**
 * Plan-Execute strategy.
 *
 * The agent works in two distinct phases:
 *
 * **Phase 1 — Planning:**
 * The LLM is asked to produce a numbered plan of steps. Each step
 * describes a goal and optionally a tool to use. The plan is stored
 * in [MemoryManager] as type [MemoryType.PLAN].
 *
 * **Phase 2 — Execution:**
 * The agent iterates through each planned step, executing tools and
 * reasoning about observations. After completing all steps (or if
 * execution fails), the agent reflects and optionally **re-plans**.
 *
 * ```
 * ┌───────────────────────────────────────┐
 * │  PLAN Phase                           │
 * │  Step 1: List all functions           │
 * │  Step 2: Decompile the entry point    │
 * │  Step 3: Identify crypto patterns     │
 * ├───────────────────────────────────────┤
 * │  EXECUTE Phase                        │
 * │  ▶ Step 1: list_functions()           │
 * │    → Observation: main, foo, bar...   │
 * │  ▶ Step 2: decompile("0x401234")      │
 * │    → Observation: int main(){...}     │
 * │  ▶ Step 3: search_strings("encrypt")  │
 * │    → Observation: AES_encrypt found   │
 * ├───────────────────────────────────────┤
 * │  REFLECT Phase                        │
 * │  Final Answer: This binary is...      │
 * └───────────────────────────────────────┘
 * ```
 *
 * This strategy excels at complex, multistep tasks where upfront
 * planning reduces meandering and wasted tool calls.
 */
class PlanExecuteStrategy(
    /** Maximum number of re-planning cycles before forcing completion. */
    val maxReplanCycles: Int = 1,

    /** Whether to include step numbers in the execution prompt. */
    val includeStepNumbers: Boolean = true
) : AgentStrategy {

    override val name: String = "Plan-Execute"

    companion object {
        val PLANNING_INSTRUCTION: String get() = AgentPrompts.PLANNING_INSTRUCTION
        val EXECUTION_INSTRUCTION: String get() = AgentPrompts.EXECUTION_INSTRUCTION
        val REFLECTION_INSTRUCTION: String get() = AgentPrompts.REFLECTION_INSTRUCTION
    }

    /** Represents a parsed plan step. */
    data class PlanStep(
        val index: Int,
        val description: String,
        val tool: String?,
        val expected: String?
    )

    override fun execute(ctx: StrategyContext): AgentResult {
        val logger = ctx.logger
        var replanCycle = 0

        logger.info("[PlanExec] Harness: ${ctx.harness.name}")
        ctx.applyHarnessDirective(ctx.harness.beforeRun(ctx), "harness.beforeRun")

        // Write system prompt to transcript at start
        ctx.transcript?.writeSystemPrompt(ctx.buildEffectiveSystemPrompt(PLANNING_INSTRUCTION))

        while (replanCycle <= maxReplanCycles) {
            // ── Phase 1: Planning ──────────────────────────────────────
            val plan = if (replanCycle == 0) {
                logger.info("[PlanExec] Phase 1: Planning (cycle $replanCycle)")
                createPlan(ctx)
            } else {
                logger.info("[PlanExec] Re-planning (cycle $replanCycle)")
                replan(ctx)
            }

            if (plan.isEmpty()) {
                logger.warn("[PlanExec] Failed to create a plan, falling back to direct answer")
                ctx.updateSessionStatus("error")
                val result = ctx.stats.toResult(
                    output = "Agent could not formulate a plan for this task.",
                    stopReason = StopReason.ERROR
                )
                ctx.transcript?.writeSessionEnd(result)
                return compactAndReturn(ctx, result)
            }

            // Store plan in memory
            if (ctx.memoryManager != null) {
                val planText = plan.mapIndexed { i, s ->
                    "${i + 1}. ${s.description}" + (s.tool?.let { " [Tool: $it]" } ?: "")
                }.joinToString("\n")
                ctx.memoryManager.remember(
                    content = planText,
                    type = MemoryType.PLAN,
                    scope = MemoryScope.SESSION,
                    key = "plan:cycle$replanCycle",
                    importance = 0.8
                )
            }

            logger.info("[PlanExec] Plan created with ${plan.size} steps: " +
                plan.joinToString("; ") { it.description })

            // ── Phase 2: Execution ─────────────────────────────────────
            when (val executionResult = executePlan(ctx, plan)) {
                is ExecResult.Completed -> {
                    // ── Phase 3: Reflection ────────────────────────────
                    val finalAnswer = reflect(ctx)
                    ctx.updateSessionStatus("closed")
                    val result = ctx.stats.toResult(
                        output = finalAnswer,
                        stopReason = StopReason.COMPLETED
                    )
                    ctx.transcript?.writeSessionEnd(result)
                    return compactAndReturn(ctx, result)
                }
                is ExecResult.ReplanNeeded -> {
                    replanCycle++
                    logger.info("[PlanExec] Replan requested: ${executionResult.reason}")
                    continue
                }
                is ExecResult.MaxIterations -> {
                    ctx.updateSessionStatus("error")
                    val result = ctx.stats.toResult(
                        output = extractLastAnswer(ctx.memory)
                            ?: "Agent reached maximum iterations during plan execution.",
                        stopReason = StopReason.MAX_ITERATIONS
                    )
                    ctx.transcript?.writeSessionEnd(result)
                    return compactAndReturn(ctx, result)
                }
                is ExecResult.Error -> {
                    ctx.updateSessionStatus("error")
                    val result = ctx.stats.toResult(
                        output = buildErrorOutput(
                            errorLabel = "Agent error",
                            errorDetail = executionResult.message,
                            memory = ctx.memory,
                        ),
                        stopReason = StopReason.ERROR
                    )
                    ctx.transcript?.writeSessionEnd(result)
                    return compactAndReturn(ctx, result)
                }
                is ExecResult.StandbyRequested -> {
                    ctx.updateSessionStatus("standby")
                    val result = ctx.stats.toResult(
                        output = SharedStandbyExtractor.MARKER,
                        stopReason = StopReason.STANDBY,
                    )
                    ctx.transcript?.writeSessionEnd(result)
                    return compactAndReturn(ctx, result)
                }
            }
        }

        // Exceeded max replan cycles
        logger.warn("[PlanExec] Exceeded max replan cycles ($maxReplanCycles)")
        val finalAnswer = reflect(ctx)
        ctx.updateSessionStatus("closed")
        val result = ctx.stats.toResult(
            output = finalAnswer,
            stopReason = StopReason.COMPLETED
        )
        ctx.transcript?.writeSessionEnd(result)
        return compactAndReturn(ctx, result)
    }

    // ---- Phase 1: Planning ────────────────────────────────────────────

    private fun createPlan(ctx: StrategyContext): List<PlanStep> {
        return requestPlan(ctx, PLANNING_INSTRUCTION)
    }

    private fun replan(ctx: StrategyContext): List<PlanStep> {
        return requestPlan(ctx, AgentPrompts.replanPrompt())
    }

    private fun requestPlan(ctx: StrategyContext, instruction: String): List<PlanStep> {
        val beforeChat = ctx.harness.beforeChat(ctx)
        ctx.applyHarnessDirective(beforeChat, "harness.beforeChat")
        val systemPrompt = ctx.buildEffectiveSystemPrompt(
            joinPromptParts(instruction, beforeChat.systemPromptAppend)
        )

        val completion = ctx.callLLM(systemPrompt) ?: return emptyList()
        ctx.stats.iterations++
        completion.tokenUsage?.let { usage ->
            ctx.stats.totalInputTokens += usage.inputTokenCount
            ctx.stats.totalOutputTokens += usage.outputTokenCount
        }

        val planText = ToolCallParser.stripThinking(completion.content)
        ctx.memory.addAssistantMessage(
            planText,
            tokenCount = completion.tokenUsage?.outputTokenCount,
            inputTokenCount = completion.tokenUsage?.inputTokenCount
        )

        ctx.transcript?.writeAssistantMessage(
            planText, ctx.stats.iterations,
            ctx.stats.totalInputTokens, ctx.stats.totalOutputTokens
        )
        ctx.applyHarnessDirective(
            ctx.harness.afterAssistantMessage(ctx, planText, completion),
            "harness.afterAssistantMessage"
        )

        return parsePlan(planText)
    }

    /** Parse a numbered plan from the LLM's text. */
    internal fun parsePlan(text: String): List<PlanStep> {
        val steps = mutableListOf<PlanStep>()
        // Match patterns like "1. description — Tool: name — Expected: info"
        // or simpler "1. description"
        val stepRegex = Regex(
            """^\s*(\d+)\.\s*(.+?)(?:\s*[—–-]\s*Tool:\s*(\S+))?(?:\s*[—–-]\s*Expected:\s*(.+))?\s*$""",
            RegexOption.MULTILINE
        )
        for (match in stepRegex.findAll(text)) {
            val index = match.groupValues[1].toIntOrNull() ?: continue
            val description = match.groupValues[2].trim()
            if (description.isBlank()) continue
            steps.add(PlanStep(
                index = index,
                description = description,
                tool = match.groupValues[3].trim().ifBlank { null },
                expected = match.groupValues[4].trim().ifBlank { null }
            ))
        }
        return steps
    }

    // ---- Phase 2: Execution ───────────────────────────────────────────

    private sealed class ExecResult {
        object Completed : ExecResult()
        class ReplanNeeded(val reason: String) : ExecResult()
        object MaxIterations : ExecResult()
        class Error(val message: String) : ExecResult()
        object StandbyRequested : ExecResult()
    }

    private fun executePlan(ctx: StrategyContext, plan: List<PlanStep>): ExecResult {
        val logger = ctx.logger

        for ((stepIdx, step) in plan.withIndex()) {
            if (ctx.stats.iterations >= ctx.maxIterations) {
                return ExecResult.MaxIterations
            }

            logger.info("[PlanExec] Executing step ${stepIdx + 1}/${plan.size}: ${step.description}")

            // Build the step-specific prompt
            val stepContext = buildString {
                append("## Plan\n")
                plan.forEachIndexed { i, s ->
                    val marker = when {
                        i < stepIdx -> "✓"
                        i == stepIdx -> "▶"
                        else -> "○"
                    }
                    append("$marker ${i + 1}. ${s.description}")
                    s.tool?.let { append(" [Tool: $it]") }
                    append("\n")
                }
                append("\n")
            }

            val execInstruction = AgentPrompts.executionStepInstruction(
                "Step ${step.index}: ${step.description}" +
                    (step.tool?.let { " (suggested tool: $it)" } ?: "")
            )

            // Execute this step (may need multiple LLM calls for one step)
            var stepIterations = 0
            val maxStepIterations = 3  // safety limit per step

            while (stepIterations < maxStepIterations && ctx.stats.iterations < ctx.maxIterations) {
                stepIterations++
                ctx.stats.iterations++

                val beforeIteration = ctx.harness.beforeIteration(ctx)
                ctx.applyHarnessDirective(beforeIteration, "harness.beforeIteration")
                val beforeChat = ctx.harness.beforeChat(ctx)
                ctx.applyHarnessDirective(beforeChat, "harness.beforeChat")
                val effectiveSystemPrompt = ctx.buildEffectiveSystemPrompt(
                    joinPromptParts("$stepContext\n$execInstruction", beforeIteration.systemPromptAppend, beforeChat.systemPromptAppend)
                )

                val completion = ctx.callLLM(effectiveSystemPrompt) ?: return ExecResult.Error(
                    ctx.stats.lastError ?: "LLM call failed"
                )
                completion.tokenUsage?.let { usage ->
                    ctx.stats.totalInputTokens += usage.inputTokenCount
                    ctx.stats.totalOutputTokens += usage.outputTokenCount
                }

                val assistantText = ToolCallParser.stripThinking(completion.content)
                ctx.memory.addAssistantMessage(
                    assistantText,
                    tokenCount = completion.tokenUsage?.outputTokenCount,
                    inputTokenCount = completion.tokenUsage?.inputTokenCount
                )
                ctx.transcript?.writeAssistantMessage(
                    assistantText, ctx.stats.iterations,
                    ctx.stats.totalInputTokens, ctx.stats.totalOutputTokens
                )
                ctx.applyHarnessDirective(
                    ctx.harness.afterAssistantMessage(ctx, assistantText, completion),
                    "harness.afterAssistantMessage"
                )

                // Check for replan request
                if (assistantText.contains("Replan Needed:", ignoreCase = true)) {
                    val reason = assistantText.substringAfter("Replan Needed:").trim().take(500)
                    return ExecResult.ReplanNeeded(reason)
                }

                // Standby marker short-circuits the step loop.  See
                // [SharedStandbyExtractor] for the precedence rule
                // (Final Answer beats standby).  Returned as a
                // dedicated [ExecResult.StandbyRequested] so the
                // outer execute() function can map it to
                // [StopReason.STANDBY].
                if (SharedStandbyExtractor.endsWithMarker(assistantText)) {
                    logger.info(
                        "[PlanExec] Standby marker detected at end of step " +
                            "${step.index} assistant text; exiting with " +
                            "StopReason.STANDBY (lifecycle=${ctx.lifecycle})."
                    )
                    return ExecResult.StandbyRequested
                }

                // Parse tool call.  Final-Answer check MUST come first for the
                // same reason as in [ReActStrategy]: the LLM's Final
                // Answer payload is itself JSON and may contain nested
                // objects with `name` / `tool` keys that would otherwise
                // be misclassified as tool calls and put the agent into
                // an infinite loop.
                val stepFinalAnswer = extractFinalAnswer(assistantText)
                val toolCall = when {
                    completion.toolCalls.isNotEmpty() ->
                        ToolCallParser.parseFromCompletion(completion)
                    stepFinalAnswer != null ->
                        // Final Answer marker present — skip text-based
                        // tool-call parsing to avoid false positives.
                        null
                    else ->
                        ToolCallParser.parseFromCompletion(completion)
                            ?: ToolCallParser.parse(assistantText)
                }

                if (toolCall != null) {
                    val toolCallsDirective = ctx.harness.beforeToolCalls(ctx, listOf(toolCall))
                    ctx.applyHarnessDirective(toolCallsDirective, "harness.beforeToolCalls")
                    if (toolCallsDirective.blockCurrentAction) {
                        logger.warn("[PlanExec] Harness blocked tool-call action")
                        continue
                    }

                    logger.info("[PlanExec]   Tool: ${toolCall.name}(${toolCall.arguments})")
                    val beforeTool = ctx.harness.beforeToolExecution(ctx, toolCall)
                    if (beforeTool.blockCurrentAction) {
                        ctx.applyHarnessDirective(beforeTool, "harness.beforeToolExecution")
                        logger.warn("[PlanExec] Harness blocked tool call: ${toolCall.name}")
                        continue
                    }

                    val toolResult = if (beforeTool.skipCurrentAction) {
                        ctx.applyHarnessDirective(beforeTool, "harness.beforeToolExecution")
                        val synthetic = beforeTool.userMessages.firstOrNull() ?: "[harness synthetic] handled: ${toolCall.name}"
                        StrategyContext.ToolResult(synthetic, 0L)
                    } else {
                        ctx.executeToolWithDuration(toolCall)
                    }
                    val observation = toolResult.output
                    ctx.memory.addToolMessage(
                        toolCallId = toolCall.callId,
                        toolName = toolCall.name,
                        args = toolCall.argumentsJson,
                        result = "**Observation (Step ${step.index}):** $observation"
                    )
                    if (!beforeTool.skipCurrentAction) {
                        ctx.applyHarnessDirective(beforeTool, "harness.beforeToolExecution")
                    }
                    ctx.applyHarnessDirective(
                        ctx.harness.afterToolExecution(ctx, toolCall, toolResult),
                        "harness.afterToolExecution"
                    )
                    // Continue loop to let the agent process the observation
                } else {
                    // No tool call — reuse the Final Answer already detected
                    // during the tool-call parse step.
                    val finalAnswer = stepFinalAnswer
                    if (finalAnswer != null) {
                        val finalAnswerDirective = ctx.harness.validateFinalAnswer(ctx, assistantText, finalAnswer)
                        ctx.applyHarnessDirective(finalAnswerDirective, "harness.validateFinalAnswer")
                        if (!finalAnswerDirective.rejectFinalAnswer) {
                            return ExecResult.Completed
                        }
                        logger.warn("[PlanExec] Harness rejected Final Answer; continuing step loop")
                        continue
                    }
                    ctx.applyHarnessDirective(
                        ctx.harness.afterNoAction(ctx, assistantText, completion),
                        "harness.afterNoAction"
                    )
                    // Otherwise, the step is done without a tool call; move on
                    break
                }
            }
        }

        // All steps executed
        return ExecResult.Completed
    }

    // ---- Phase 3: Reflection ──────────────────────────────────────────

    private fun reflect(ctx: StrategyContext): String {
        val beforeChat = ctx.harness.beforeChat(ctx)
        ctx.applyHarnessDirective(beforeChat, "harness.beforeChat")
        val systemPrompt = ctx.buildEffectiveSystemPrompt(
            joinPromptParts(REFLECTION_INSTRUCTION, beforeChat.systemPromptAppend)
        )
        ctx.stats.iterations++

        val completion = ctx.callLLM(systemPrompt) ?: return extractLastAnswer(ctx.memory)
            ?: "Agent completed but reflection failed."

        completion.tokenUsage?.let { usage ->
            ctx.stats.totalInputTokens += usage.inputTokenCount
            ctx.stats.totalOutputTokens += usage.outputTokenCount
        }

        val reflection = ToolCallParser.stripThinking(completion.content)
        ctx.memory.addAssistantMessage(
            reflection,
            tokenCount = completion.tokenUsage?.outputTokenCount,
            inputTokenCount = completion.tokenUsage?.inputTokenCount
        )

        ctx.transcript?.writeAssistantMessage(
            reflection, ctx.stats.iterations,
            ctx.stats.totalInputTokens, ctx.stats.totalOutputTokens
        )
        ctx.applyHarnessDirective(
            ctx.harness.afterAssistantMessage(ctx, reflection, completion),
            "harness.afterAssistantMessage"
        )

        // Store reflection as insight
        ctx.memoryManager?.remember(
            content = reflection.take(1000),
            type = MemoryType.INSIGHT,
            scope = MemoryScope.SESSION,
            key = "reflection",
            importance = 0.9
        )

        val finalAnswer = extractFinalAnswer(reflection)
        if (finalAnswer != null) {
            val finalAnswerDirective = ctx.harness.validateFinalAnswer(ctx, reflection, finalAnswer)
            ctx.applyHarnessDirective(finalAnswerDirective, "harness.validateFinalAnswer")
            if (finalAnswerDirective.rejectFinalAnswer) {
                return "Final answer rejected by harness; additional analysis is required before completion."
            }
            return finalAnswer
        }
        return reflection
    }

    // ---- Helpers ──────────────────────────────────────────────────────

    private fun extractFinalAnswer(text: String): String? =
        SharedFinalAnswerExtractor.extract(text)

    private fun extractLastAnswer(memory: ChatMemory): String? {
        return memory.messages().lastOrNull { it.role == "assistant" }?.content
    }
}

/**
 * Shared helper for Final-Answer detection. Used by both [ReActStrategy]
 * and [PlanExecuteStrategy] so the two strategies agree on what counts
 * as a Final Answer marker (and so the precedence fix in
 * `parseToolCallsAfterFinalAnswerCheck` has a single source of truth).
 *
 * IMPORTANT: this is also the boundary used to STOP text-based tool
 * call parsing.  When a Final Answer marker is detected, the strategies
 * MUST NOT scan the text after the marker for tool calls — JSON
 * snippets inside the answer payload (e.g. `{ "name": "process_packet",
 * "address": "0x401234" }` inside a `suspiciousFunctions` array)
 * otherwise get misclassified as actual tool calls, the agent thinks
 * the LLM has not finished, and the loop runs until maxIterations.
 */
internal object SharedFinalAnswerExtractor {
    /**
     * Patterns that locate the START of a Final Answer marker (group 0
     * is the marker text itself).  Order matters: the canonical
     * `**Final Answer:**` form is checked first; the loose
     * `Final Answer:` form (preceded only by non-alphanumeric chars,
     * so `MyFinal Answer:` is excluded) is the fallback.
     */
    private val MARKER_PATTERNS: List<Regex> = listOf(
        Regex("""(?i)\*\*Final Answer:\*\*"""),
        Regex("""(?i)(?<![A-Za-z0-9])Final Answer:"""),
    )

    /**
     * Patterns that extract the full Final Answer payload.  Group 1 is
     * the answer body (everything after `Final Answer:`).  Same
     * precedence as [MARKER_PATTERNS].
     */
    private val ANSWER_PATTERNS: List<Regex> = listOf(
        Regex("""(?i)\*\*Final Answer:\*\*\s*(.*)""", RegexOption.DOT_MATCHES_ALL),
        Regex("""(?i)(?<![A-Za-z0-9])Final Answer:\s*(.*)""", RegexOption.DOT_MATCHES_ALL),
    )

    /** Returns the Final Answer payload, or null if no marker is present. */
    fun extract(text: String): String? {
        for (pattern in ANSWER_PATTERNS) {
            val match = pattern.find(text) ?: continue
            val answer = match.groupValues[1].trim()
            if (answer.isNotBlank()) return answer
        }
        return null
    }

    /**
     * Returns the index of the first Final Answer marker in [text],
     * or null if none.  Used by strategies to slice the assistant text
     * into "before the marker" (where tool calls live) and "after the
     * marker" (where the answer payload lives, which must not be
     * parsed for tool calls).
     */
    fun markerIndex(text: String): Int? {
        for (pattern in MARKER_PATTERNS) {
            val match = pattern.find(text) ?: continue
            return match.range.first
        }
        return null
    }
}

// ============================================================
//  SharedStandbyExtractor — recognise the "park to standby" signal
// ============================================================
//
// Companion to [SharedFinalAnswerExtractor].  The two markers
// share the same "marker → strategy exits" semantics; they differ
// only in the resulting [StopReason] (FINAL_ANSWER → COMPLETED,
// STANDBY_MARKER → STANDBY).  Detection precedence matters:
//
//   1. If a Final Answer marker is present, the agent has finished —
//      the Final Answer wins and the standby marker is ignored
//      (otherwise an LLM that accidentally writes both would flip
//      the run into a STANDBY session even when it meant to wrap up).
//   2. If only a standby marker is present at the END of the
//      response, the strategy returns [StopReason.STANDBY] so the
//      runtime parks the session to `runtime_state=standby`
//      (lifecycle=standby) and the dispatcher can later wake it
//      on a new mailbox message.
//
// The marker must be the LAST non-blank line of the assistant
// message.  We do not require the entire response to be a single
// line — the LLM can output reasoning, a `Tool decision: ...`
// sentence, an `Action:` block, etc., and then a final
// "Enter standby mode." line that signals "I'm done for now,
// park me".  Matching against the last line keeps the marker
// cheap to detect and avoids false positives on a stray "enter
// standby mode" word inside a long Thought block.
//
// The canonical text is exactly `Enter standby mode.` (with the
// trailing period and capital `E` / lowercase `s/m`); the
// case-insensitive fallback accepts any casing and trims
// surrounding whitespace.  The trailing period is REQUIRED in
// the canonical form to make accidental matches on
// "I will enter standby mode shortly" impossible.

internal object SharedStandbyExtractor {
    /** Canonical marker text.  Kept in one place so the agent
     *  prompt and the extractor stay in sync. */
    const val MARKER: String = "Enter standby mode."

    /**
     * True when [text] ends (after trimming) with the standby
     * marker.  Used by the strategies to short-circuit the run()
     * loop and return [StopReason.STANDBY] before the next
     * iteration.
     */
    fun endsWithMarker(text: String): Boolean {
        val trimmed = text.trimEnd()
        // Canonical form first (case-sensitive), so "ENTER STANDBY MODE."
        // does NOT match — the LLM is taught the exact lowercase form.
        if (trimmed.endsWith(MARKER)) return true
        // Case-insensitive fallback: trim the trailing line and
        // accept any casing as long as the line equals the marker.
        val lastLine = trimmed.lineSequence()
            .lastOrNull { it.isNotBlank() }
            ?.trim()
            ?: return false
        return lastLine.equals(MARKER, ignoreCase = true)
    }
}

// ============================================================
//  LoopStats → AgentResult convenience
// ============================================================

private fun LoopStats.toResult(output: String, stopReason: StopReason): AgentResult = AgentResult(
    output = output,
    iterations = iterations,
    toolCallsMade = toolCallsMade,
    totalInputTokens = totalInputTokens,
    totalOutputTokens = totalOutputTokens,
    stopReason = stopReason
)

// ============================================================
//  Error envelopes — surface partial progress when the loop dies
// ============================================================
//
// When the strategy loop is interrupted by an LLM error (model busy, network
// timeout, rate limit, context overflow that the recovery hook couldn't fix,
// etc.), the parent's downstream agent previously saw only a one-line error
// string. That discarded whatever progress the child had already made —
// burned tokens, burned tool calls, gone. The helpers below let the
// strategies and the `spawn_sub_agent` tool compose a structured error
// envelope that pairs the error with the child's last successful assistant
// turn (and the tool observations that fed into it), so the parent can:
//
//   1. read the partial output and decide whether the work is salvageable,
//   2. spawn a follow-up sub-agent for the un-finished portion without
//      re-doing everything from scratch,
//   3. cite the partial findings when reporting the dispatch failure.
//
// The snapshot is bounded by `maxChars`; the full conversation is still
// available in the child's persistent memory / transcript for forensic
// detail (use `read_history_tool_call` against `sessionId`).

/**
 * Build a single string suitable for [AgentResult.output] when the loop was
 * interrupted by an LLM error. The envelope contains the error label and a
 * bounded snapshot of the last successful assistant turn (paired with the
 * most recent tool observations that fed into it).
 *
 * @param errorLabel short human-readable label (e.g. "Agent error",
 *   "Child agent crashed").
 * @param errorDetail the actual error message; falls back to a generic
 *   placeholder if null/blank.
 * @param memory the child's [ChatMemory] — the last assistant turn and any
 *   preceding tool results are extracted from here.
 * @param maxChars hard cap on the output size (default 8000, leaving room
 *   for the surrounding envelope text and the parent's own context).
 * @param stopReason the resulting [StopReason]; included for downstream
 *   consumers that switch on it.
 */
internal fun buildErrorOutput(
    errorLabel: String,
    errorDetail: String?,
    memory: ChatMemory,
    maxChars: Int = 8_000,
    stopReason: StopReason = StopReason.ERROR,
): String {
    val detail = errorDetail?.takeIf { it.isNotBlank() } ?: "LLM call failed (no detail)"
    // Reserve ~600 chars for the envelope framing; the snapshot fills the rest.
    val snapshotBudget = (maxChars - 600).coerceAtLeast(512)
    val snapshot = lastAssistantSnapshot(memory, snapshotBudget)
    val sessionHint = memory.sessionId?.let { " (child sessionId=$it; full transcript readable via read_history_tool_call)" }
        ?: ""
    return buildString {
        appendLine("$errorLabel: $detail")
        appendLine("stopReason: ${stopReason.name}")
        if (sessionHint.isNotEmpty()) appendLine(sessionHint)
        appendLine()
        appendLine("Last successful assistant turn before the failure " +
            "(so the parent can salvage partial progress):")
        appendLine("--- begin last-turn snapshot ---")
        if (snapshot == null) {
            appendLine("<no assistant turn recorded before the failure — " +
                "the child crashed before the first model response completed>")
        } else {
            appendLine(snapshot)
        }
        appendLine("--- end last-turn snapshot ---")
    }
}

/**
 * Extract a bounded snapshot of the child's most recent assistant turn and
 * the tool observations that immediately preceded it. Returns null when no
 * assistant turn has been recorded yet.
 *
 * The walk stops at the first non-tool message before the last assistant
 * turn, so the snapshot contains exactly the observations the assistant was
 * reacting to when it produced its last message — not the entire preceding
 * conversation. That keeps the envelope small while preserving the chain
 * of evidence the parent most likely needs.
 */
internal fun lastAssistantSnapshot(
    memory: ChatMemory,
    maxChars: Int = 8_000,
): String? {
    val msgs = try {
        memory.messages()
    } catch (_: Throwable) {
        return null
    }
    if (msgs.isEmpty()) return null

    val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
    if (lastAssistantIdx < 0) return null
    val lastAssistant = msgs[lastAssistantIdx].content

    // Walk back from the last assistant message to gather the tool
    // observations that fed into it. Stop at the previous user/assistant
    // boundary so we capture only the most recent tool batch.
    val preceding = mutableListOf<org.iotsplab.akiba.llm.memory.AgentChatMessage>()
    for (i in (lastAssistantIdx - 1) downTo 0) {
        val m = msgs[i]
        if (m.role != "tool") break
        preceding.add(0, m)
    }

    val snapshot = buildString {
        if (preceding.isNotEmpty()) {
            appendLine("Most recent observation(s) the child had processed:")
            for (m in preceding) {
                val toolName = m.toolName ?: "tool"
                appendLine("- $toolName:")
                appendLine(m.content)
                appendLine()
            }
        }
        appendLine("Last assistant turn before the failure:")
        appendLine(lastAssistant)
    }

    return if (snapshot.length > maxChars) {
        snapshot.substring(0, maxChars) +
            "\n... (truncated; full text in child transcript" +
            (memory.sessionId?.let { ", sessionId=$it" } ?: "") + ")"
    } else {
        snapshot
    }
}
