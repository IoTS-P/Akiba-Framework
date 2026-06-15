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
    /** Optional hook invoked before every LLM chat call. Used for context compaction. */
    val beforeChatHook: (() -> Unit)? = null,
    /** Agent database client for audit / session updates. */
    val agentDbClient: AgentDatabaseClient? = null,
    /** Optional provider for the compacted context view sent to the model. */
    val contextMessagesProvider: (() -> List<org.iotsplab.akiba.llm.memory.AgentChatMessage>)? = null,
    /** Advisory detector for repeated tool outputs within the same session. */
    val toolResultDuplicateDetector: ToolResultDuplicateDetector? = null
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
     * @return the completion, or null on error (error is logged).
     */
    fun callLLM(systemPrompt: String): ChatCompletion? {
        beforeChatHook?.invoke()
        return try {
            client.chat(
                systemPrompt = systemPrompt,
                messages = contextMessagesProvider?.invoke() ?: memory.messages(),
                tools = if (toolRegistry.isEmpty()) null else toolRegistry.toJsonSchemas()
            )
        } catch (e: Exception) {
            stats.lastError = e.message
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
        val duplicateDetection = toolResultDuplicateDetector?.inspect(
            ToolResultInspectionRequest(
                sessionId = sessionId,
                iteration = stats.iterations,
                toolCall = toolCall,
                resultUuid = resultUuid,
                stored = stored
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

    override fun execute(ctx: StrategyContext): AgentResult {
        val logger = ctx.logger

        // Write system prompt to transcript (only on first iteration)
        ctx.transcript?.writeSystemPrompt(ctx.buildEffectiveSystemPrompt(REACT_INSTRUCTION))

        while (ctx.stats.iterations < ctx.maxIterations) {
            ctx.stats.iterations++
            logger.debug("[ReAct] iteration ${ctx.stats.iterations}/${ctx.maxIterations}")

            val systemPrompt = ctx.buildEffectiveSystemPrompt(REACT_INSTRUCTION)

            val completion = ctx.callLLM(systemPrompt) ?: return ctx.stats.toResult(
                output = "Agent error: ${ctx.stats.lastError}",
                stopReason = StopReason.ERROR
            )

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

            // Parse ALL tool calls (native first, then text-based fallback).
            // Up to MAX_BATCH_TOOL_CALLS will be executed sequentially in the
            // same iteration before another LLM round-trip.
            val allToolCalls = ToolCallParser.parseAllFromCompletion(completion).ifEmpty {
                ToolCallParser.parseAll(assistantText)
            }

            if (allToolCalls.isNotEmpty()) {
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
                    val toolResult = ctx.executeToolWithDuration(toolCall)
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

                    logger.debug("[ReAct] Observation: ${observation.take(200)}...")
                }

                // If we capped the batch, hint to the LLM that some calls were dropped
                if (allToolCalls.size > MAX_BATCH_TOOL_CALLS) {
                    ctx.memory.addUserMessage(
                        AgentPrompts.batchTruncatedNote(allToolCalls.size, MAX_BATCH_TOOL_CALLS)
                    )
                }
            } else {
                // No tool call detected — check if LLM explicitly gave a Final Answer
                val finalAnswer = extractFinalAnswer(assistantText)

                if (finalAnswer != null) {
                    // LLM explicitly signaled it's done
                    ctx.updateSessionStatus("completed")
                    val result = ctx.stats.toResult(
                        output = finalAnswer,
                        stopReason = StopReason.COMPLETED
                    )
                    ctx.transcript?.writeSessionEnd(result)
                    return result
                }

                // When finishReason is "length", the LLM output was cut off
                // (e.g. a mid-reply JSON tool call was truncated), so we must
                // NOT conclude — the agent should keep going.
                if (completion.finishReason == "length") {
                    logger.warn("[ReAct] LLM response truncated (finishReason=length). " +
                        "Requesting continuation instead of ending loop.")
                    val note = "Your previous response was truncated because it exceeded " +
                        "the output length limit. Continue from where you left off. " +
                        "If you had started a tool call, please repeat it now."
                    ctx.memory.addUserMessage(note)
                    ctx.transcript?.writeFormatReminder(note)
                } else {
                    // LLM did not provide a Final Answer or valid tool call.
                    // Do NOT end the loop — instead inject a format reminder so the
                    // LLM has a chance to correct its output in the next turn.
                    logger.info("[ReAct] No tool call or final answer detected. " +
                        "Sending format reminder. Text preview: ${assistantText.take(300)}")

                    val toolNames = ctx.toolRegistry.names().joinToString(", ")
                    val reminder = AgentPrompts.formatReminder(toolNames)
                    ctx.memory.addUserMessage(reminder)
                    ctx.transcript?.writeFormatReminder(reminder)
                }
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
        return result
    }

    /** Extract the Final Answer portion from a ReAct response. */
    private fun extractFinalAnswer(text: String): String? {
        val patterns = listOf(
            Regex("""(?i)\*\*Final Answer:\*\*\s*(.*)""", RegexOption.DOT_MATCHES_ALL),
            Regex("""(?i)Final Answer:\s*(.*)""", RegexOption.DOT_MATCHES_ALL),
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val answer = match.groupValues[1].trim()
            if (answer.isNotBlank()) return answer
        }
        return null
    }

    private fun extractLastAnswer(memory: ChatMemory): String? {
        return memory.messages().lastOrNull { it.role == "assistant" }?.content
    }
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
                ctx.updateSessionStatus("completed")
                val result = ctx.stats.toResult(
                    output = "Agent could not formulate a plan for this task.",
                    stopReason = StopReason.ERROR
                )
                ctx.transcript?.writeSessionEnd(result)
                return result
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
                    ctx.updateSessionStatus("completed")
                    val result = ctx.stats.toResult(
                        output = finalAnswer,
                        stopReason = StopReason.COMPLETED
                    )
                    ctx.transcript?.writeSessionEnd(result)
                    return result
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
                    return result
                }
                is ExecResult.Error -> {
                    ctx.updateSessionStatus("error")
                    val result = ctx.stats.toResult(
                        output = "Agent error: ${executionResult.message}",
                        stopReason = StopReason.ERROR
                    )
                    ctx.transcript?.writeSessionEnd(result)
                    return result
                }
            }
        }

        // Exceeded max replan cycles
        logger.warn("[PlanExec] Exceeded max replan cycles ($maxReplanCycles)")
        val finalAnswer = reflect(ctx)
        ctx.updateSessionStatus("completed")
        val result = ctx.stats.toResult(
            output = finalAnswer,
            stopReason = StopReason.COMPLETED
        )
        ctx.transcript?.writeSessionEnd(result)
        return result
    }

    // ---- Phase 1: Planning ────────────────────────────────────────────

    private fun createPlan(ctx: StrategyContext): List<PlanStep> {
        return requestPlan(ctx, PLANNING_INSTRUCTION)
    }

    private fun replan(ctx: StrategyContext): List<PlanStep> {
        return requestPlan(ctx, AgentPrompts.replanPrompt())
    }

    private fun requestPlan(ctx: StrategyContext, instruction: String): List<PlanStep> {
        val systemPrompt = ctx.buildEffectiveSystemPrompt(instruction)

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

            val systemPrompt = ctx.buildEffectiveSystemPrompt(
                "$stepContext\n$execInstruction"
            )

            // Execute this step (may need multiple LLM calls for one step)
            var stepIterations = 0
            val maxStepIterations = 3  // safety limit per step

            while (stepIterations < maxStepIterations && ctx.stats.iterations < ctx.maxIterations) {
                stepIterations++
                ctx.stats.iterations++

                val completion = ctx.callLLM(systemPrompt) ?: return ExecResult.Error(
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

                // Check for replan request
                if (assistantText.contains("Replan Needed:", ignoreCase = true)) {
                    val reason = assistantText.substringAfter("Replan Needed:").trim().take(500)
                    return ExecResult.ReplanNeeded(reason)
                }

                // Parse tool call (native first, then text-based fallback)
                val toolCall = ToolCallParser.parseFromCompletion(completion)
                    ?: ToolCallParser.parse(assistantText)

                if (toolCall != null) {
                    logger.info("[PlanExec]   Tool: ${toolCall.name}(${toolCall.arguments})")
                    val observation = ctx.executeTool(toolCall)
                    ctx.memory.addToolMessage(
                        toolCallId = toolCall.callId,
                        toolName = toolCall.name,
                        args = toolCall.argumentsJson,
                        result = "**Observation (Step ${step.index}):** $observation"
                    )
                    // Continue loop to let the agent process the observation
                } else {
                    // No tool call — check for final answer
                    val finalAnswer = extractFinalAnswer(assistantText)
                    if (finalAnswer != null) {
                        return ExecResult.Completed
                    }
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
        val systemPrompt = ctx.buildEffectiveSystemPrompt(REFLECTION_INSTRUCTION)
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

        // Store reflection as insight
        ctx.memoryManager?.remember(
            content = reflection.take(1000),
            type = MemoryType.INSIGHT,
            scope = MemoryScope.SESSION,
            key = "reflection",
            importance = 0.9
        )

        return extractFinalAnswer(reflection) ?: reflection
    }

    // ---- Helpers ──────────────────────────────────────────────────────

    private fun extractFinalAnswer(text: String): String? {
        val patterns = listOf(
            Regex("""(?i)\*\*Final Answer:\*\*\s*(.*)""", RegexOption.DOT_MATCHES_ALL),
            Regex("""(?i)Final Answer:\s*(.*)""", RegexOption.DOT_MATCHES_ALL),
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val answer = match.groupValues[1].trim()
            if (answer.isNotBlank()) return answer
        }
        return null
    }

    private fun extractLastAnswer(memory: ChatMemory): String? {
        return memory.messages().lastOrNull { it.role == "assistant" }?.content
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
