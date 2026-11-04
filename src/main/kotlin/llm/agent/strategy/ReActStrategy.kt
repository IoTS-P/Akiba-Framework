package org.iotsplab.akiba.llm.agent.strategy

import kotlinx.coroutines.delay
import org.iotsplab.akiba.llm.agent.mailbox.WakeConditionRegistry
import org.iotsplab.akiba.llm.agent.mailbox.applyMailboxDrain
import org.iotsplab.akiba.llm.agent.template.Lifecycle
import org.iotsplab.akiba.llm.client.ChatCompletion
import org.iotsplab.akiba.llm.memory.AgentChatMessage
import org.iotsplab.akiba.llm.memory.ChatMemory
import org.iotsplab.akiba.llm.tool.core.ToolCallParser
import org.iotsplab.akiba.llm.agent.core.AgentHarness
import org.iotsplab.akiba.llm.agent.core.AgentHarnessDirective
import org.iotsplab.akiba.llm.agent.core.AgentPrompts
import org.iotsplab.akiba.llm.agent.core.AgentResult
import org.iotsplab.akiba.llm.agent.core.AgentStrategy
import org.iotsplab.akiba.llm.agent.core.DUPLICATE_LOOP_FORCE_COMPACT_THRESHOLD
import org.iotsplab.akiba.llm.agent.core.LoopStats
import org.iotsplab.akiba.llm.agent.core.MAX_BATCH_TOOL_CALLS
import org.iotsplab.akiba.llm.agent.core.MAX_FORCED_COMPACTIONS
import org.iotsplab.akiba.llm.agent.core.MAX_NO_ACTION_TURNS
import org.iotsplab.akiba.llm.agent.core.StopReason
import org.iotsplab.akiba.llm.agent.core.StrategyContext
import org.iotsplab.akiba.llm.agent.core.ToolResultDuplicateSeverity
import org.iotsplab.akiba.llm.agent.core.batchToolCallHint
import org.iotsplab.akiba.llm.agent.core.joinPromptParts
import org.iotsplab.akiba.llm.agent.core.applyHarnessDirective

/**
 * ReAct (Reasoning + Acting) strategy — the framework's default loop.
 *
 * Each turn: if the reply asks for tools, every call is executed and its result
 * appended, and the loop continues; if it asks for none, the turn is over and
 * that text is the answer (see [plainTextAnswer]).
 *
 * No response template is required — the only machine contract is the
 * ```json tool_call block that text-protocol providers use.
 */

private fun isAwaitConditionRegistrationSuccess(rawOutput: String): Boolean {
    val out = rawOutput.trim()
    if (out.startsWith("Error:") ||
        out.startsWith("Tool argument error") ||
        out.startsWith("Tool 'await_condition' execution error")
    ) return false
    return Regex("\\\"status\\\"\\s*:\\s*\\\"registered\\\"").containsMatchIn(out)
}


class ReActStrategy : AgentStrategy {

    override val name: String = "ReAct"

    companion object {
        /**
         * Maximum number of tool calls executed sequentially in a single
         * iteration before requesting another LLM round-trip. Defined in
         * [AgentConstants] (it is also referenced inside the ReAct instruction).
         */

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
                "[ReAct] Reply contained no tool call; sending a reminder that prose does not " +
                    "execute anything. Text preview: ${assistantText.take(300)}"
            )
            val toolNames = ctx.toolRegistry.names().joinToString(", ")
            return AgentHarnessDirective.userMessage(AgentPrompts.formatReminder(toolNames))
        }
    }

    override suspend fun execute(ctx: StrategyContext): AgentResult {
        val logger = ctx.logger

        logger.info("[ReAct] Harness: ${ctx.harness.name}")
        ctx.applyHarnessDirective(ctx.harness.beforeRun(ctx), "harness.beforeRun")

        // Write system prompt to transcript (only on first iteration)
        ctx.transcript?.writeSystemPrompt(ctx.buildEffectiveSystemPrompt(REACT_INSTRUCTION))

        while (ctx.stats.iterations < ctx.maxIterations) {
            ctx.stats.iterations++
            logger.debug("[ReAct] iteration ${ctx.stats.iterations}/${ctx.maxIterations}")

            // ---- User-requested pause ----
            // Check before each LLM call, after the previous response has been
            // fully processed (tool calls executed, memory updated) — the
            // correct boundary to wait.  `delay` rather than `Thread.sleep`, so
            // the coroutine Job stays cancellable while paused.
            if (ctx.pauseCheckProvider?.invoke() == true) {
                logger.info("[ReAct] Session paused by user; blocking before LLM call")
                while (ctx.pauseCheckProvider?.invoke() == true) {
                    if (ctx.cancellationReasonProvider?.invoke() != null) {
                        logger.info("[ReAct] Cancelled while paused")
                        return ctx.stats.toResult(
                            output = "Agent cancelled while paused.",
                            stopReason = StopReason.ERROR
                        )
                    }
                    // `delay` is cooperative — if the coroutine Job is
                    // cancelled (e.g. via runtime.cancel), the
                    // CancellationException propagates here and up
                    // through runChildJob.
                    delay(500)
                }
                logger.info("[ReAct] Session resumed from pause")
            }

            // Mailbox draining is framework infrastructure, not a
            // domain-harness responsibility. Run it unconditionally
            // before the overridable harness hook so every resumed
            // agent marks its wake message as read.
            val mailboxDrain = applyMailboxDrain(ctx, ctx.mailboxService)
            ctx.applyHarnessDirective(mailboxDrain, "mailbox.drain")
            val beforeIteration = ctx.harness.beforeIteration(ctx)
            ctx.applyHarnessDirective(beforeIteration, "harness.beforeIteration")
            val beforeChat = ctx.harness.beforeChat(ctx)
            ctx.applyHarnessDirective(beforeChat, "harness.beforeChat")
            val resumeHint = if (ctx.resumedFromStandby) {
                "[system: you are being woken from standby by new mailbox messages; " +
                    "the framework has drained the unread messages into this turn " +
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
                ctx.updateSessionStatus("error", reason = ctx.stats.lastError?.take(500))
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

            // With native function calling, completion.content is often empty:
            // the provider returns the calls structurally and no visible text.
            // Synthesise the equivalent tool_call blocks so the stored history
            // stays complete and readable for the next turn.
            val assistantText = when {
                completion.toolCalls.isNotEmpty() && completion.content.isBlank() ->
                    completion.toolCalls.joinToString("\n") { tc ->
                        "```json\n{\"tool_call\": {\"name\": \"${tc.name}\", \"arguments\": ${tc.argumentsJson}}}\n```"
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

            // Decide whether this reply is acting or answering: with native
            // function calling the structured channel is authoritative, so a
            // reply with no `toolCalls` is an answer and its text is never
            // scanned for calls (a JSON answer must not be read as a call).
            // Text-protocol providers have no structured channel, so their
            // prose is parsed for `tool_call` blocks instead.
            val allToolCalls = when {
                completion.toolCalls.isNotEmpty() ->
                    ToolCallParser.parseAllFromCompletion(completion)
                // Native tool protocol: the structured channel is the only
                // source of truth.  A reply without a tool call is an answer,
                // and scanning its text would misread JSON payloads (e.g. a
                // `{"suspiciousFunctions":[{"name":"..."}]}` answer) as calls.
                usesNativeToolProtocol(ctx) -> emptyList()
                // Text protocol: the call lives in the prose.
                else -> ToolCallParser.parseAll(assistantText)
            }

            if (allToolCalls.isNotEmpty()) {
                // The model is still working: the text-protocol fallback in
                // plainTextAnswer() only counts *consecutive* no-action turns.
                ctx.stats.consecutiveNoActionTurns = 0
                val toolCallsDirective = ctx.harness.beforeToolCalls(ctx, allToolCalls)
                ctx.applyHarnessDirective(toolCallsDirective, "harness.beforeToolCalls")
                if (toolCallsDirective.blockCurrentAction) {
                    logger.warn("[ReAct] Harness blocked current tool-call batch")
                    continue
                }

                // Cap the batch size so one response can't blow through the
                // iteration budget.  Runs after the domain harness so it doesn't
                // interfere with domain-specific blocking, and the hint lands in
                // memory before the observations so the LLM sees it next turn.
                val batchHint = batchToolCallHint(allToolCalls, MAX_BATCH_TOOL_CALLS)
                ctx.applyHarnessDirective(batchHint, "react.batchHint")

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

                    // Pre-execution park gate: validate await_condition
                    // BEFORE the tool registers a wake condition.  A
                    // rejected park then surfaces as a single clean
                    // rejection — never as a misleading "registered"
                    // result contradicted by a later rejection message,
                    // and no registry rollback is needed.
                    val prePark = if (!beforeTool.skipCurrentAction && toolCall.name == "await_condition") {
                        ctx.harness.validatePark(ctx, assistantText)
                    } else {
                        AgentHarnessDirective.None
                    }

                    val toolResult = if (beforeTool.skipCurrentAction) {
                        ctx.applyHarnessDirective(beforeTool, "harness.beforeToolExecution")
                        val synthetic = beforeTool.userMessages.firstOrNull() ?: "[harness synthetic] handled: ${toolCall.name}"
                        StrategyContext.ToolResult(synthetic, 0L)
                    } else if (prePark.rejectPark) {
                        ctx.applyHarnessDirective(prePark, "harness.validatePark")
                        StrategyContext.ToolResult(
                            prePark.userMessages.firstOrNull()
                                ?: "[harness.validatePark] park rejected",
                            0L,
                        )
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

                    logger.debug("[ReAct] Observation: ${observation.take(200)}...")

                    // ── Duplicate-loop guard ──────────────────────────
                    // Track consecutive WARNING-level duplicate tool
                    // results.  When the count reaches the threshold,
                    // force a context compaction to break the loop.
                    val dupDetection = toolResult.duplicateDetection
                    if (dupDetection != null &&
                        dupDetection.severity == ToolResultDuplicateSeverity.WARNING
                    ) {
                        ctx.stats.consecutiveDuplicateWarnings++
                    } else {
                        ctx.stats.consecutiveDuplicateWarnings = 0
                    }

                    // ── Harness-requested or auto forced compaction ──
                    val afterToolDirective = ctx.harness.afterToolExecution(ctx, toolCall, toolResult)
                    val needForceCompact = afterToolDirective.forceCompaction ||
                        (ctx.stats.consecutiveDuplicateWarnings >= DUPLICATE_LOOP_FORCE_COMPACT_THRESHOLD &&
                            ctx.stats.forcedCompactions < MAX_FORCED_COMPACTIONS)

                    if (needForceCompact) {
                        val reason = afterToolDirective.loopBreakReason
                            ?: "Detected ${ctx.stats.consecutiveDuplicateWarnings} consecutive " +
                                "WARNING-level duplicate tool calls. The agent appears to be " +
                                "stuck in an unrecoverable loop. Forcing context compaction " +
                                "to disrupt the pattern."
                        logger.warn("[ReAct] Force compaction triggered: $reason")
                        ctx.applyHarnessDirective(afterToolDirective, "harness.afterToolExecution")
                        ctx.memory.addUserMessage(
                            "[SYSTEM] $reason\n\n" +
                                "You have been repeatedly calling the same tool with the same " +
                                "arguments without progress. The context has been compacted. " +
                                "Review what you have accomplished so far (see the summary) and " +
                                "either:\n" +
                                "  1. Give the answer if the task is already answerable, or\n" +
                                "  2. Switch to a meaningfully different tool or argument, or\n" +
                                "  3. If you are stuck, call `await_condition` to park and wait " +
                                "for external input.\n\n" +
                                "Do NOT repeat the same tool call that triggered this compaction."
                        )
                        ctx.transcript?.writeFormatReminder("[forced-compaction] $reason")
                        ctx.compactFn?.let { compactFn ->
                            val ok = compactFn()
                            ctx.stats.forcedCompactions++
                            ctx.stats.consecutiveDuplicateWarnings = 0
                            logger.info("[ReAct] Forced compaction result: ok=$ok, " +
                                "total forced compactions=${ctx.stats.forcedCompactions}")
                        }
                        // Break out of the inner tool-batch loop; the
                        // outer while loop will start a fresh iteration
                        // with the compacted context.
                        break
                    }

                    // Normal path: apply the afterToolExecution directive
                    // (we already fetched it above; no need to call again).
                    ctx.applyHarnessDirective(afterToolDirective, "harness.afterToolExecution")

                    // Detect `await_condition` tool call: this is the
                    // LLM's signal that it wants to park and wait for a
                    // wake condition.  We immediately exit the loop with
                    // [StopReason.STANDBY] — do NOT continue to the next
                    // LLM call.  The LLM already expressed its intent to
                    // park; making it produce another response just to
                    // phrase an answer wastes a round-trip and risks
                    // the LLM calling more tools instead of wrapping up.
                    if (toolCall.name == "await_condition") {
                        if (prePark.rejectPark) {
                            // Park was rejected PRE-execution; the
                            // rejection message was already injected.
                            // Nothing registered — just continue.
                            continue
                        }
                        if (!isAwaitConditionRegistrationSuccess(toolResult.rawOutput)) {
                            logger.warn(
                                "[ReAct] await_condition did not register successfully; " +
                                    "continuing so the LLM can correct the arguments. Result: ${toolResult.rawOutput.take(300)}"
                            )
                            continue
                        }
                        ctx.stats.awaitConditionRegistered = true

                        // Park-side gate: let the harness inspect current
                        // state and optionally block the park.  This is
                        // independent from validateAnswer (exit gate)
                        // because the checks differ — e.g. "children still
                        // running" is fine for park but not for exit.
                        val parkDirective = ctx.harness.validatePark(ctx, assistantText)
                        ctx.applyHarnessDirective(parkDirective, "harness.validatePark")
                        if (parkDirective.rejectPark) {
                            // The tool has already registered the condition.
                            // A rejected park must roll it back; otherwise the
                            // still-running agent carries a stale condition
                            // that may fire before its next valid park.
                            ctx.sessionId?.let { WakeConditionRegistry.clearAll(it) }
                            ctx.stats.awaitConditionRegistered = false
                            logger.info("[ReAct] Harness rejected park; rolled back wake condition and continuing loop")
                            continue
                        }

                        logger.info("[ReAct] await_condition registered — exiting loop with StopReason.STANDBY")
                        ctx.updateSessionStatus("standby")
                        val standbyResult = ctx.stats.toResult(
                            output = assistantText,
                            stopReason = StopReason.STANDBY,
                        )
                        ctx.transcript?.writeSessionEnd(standbyResult)
                        return compactAndReturn(ctx, standbyResult)
                    }
                }
            } else {
                // No tool call.  Completion rule: the model is done when it
                // stops asking for tools — OpenAI signals that with
                // `finish_reason="stop"`, Anthropic with `stop_reason="end_turn"`,
                // and LangGraph/LangChain route to END on an empty
                // `tool_calls` list (see [plainTextAnswer]).
                ctx.stats.consecutiveNoActionTurns++

                val answer = plainTextAnswer(ctx, completion, assistantText)

                if (answer != null) {
                    val answerDirective = ctx.harness.validateAnswer(ctx, assistantText, answer)

                    ctx.applyHarnessDirective(answerDirective, "harness.validateAnswer")

                    if (!answerDirective.rejectAnswer) {
                        // Park is exclusively requested via await_condition.
                        ctx.updateSessionStatus("cancelling")
                        val result = ctx.stats.toResult(
                            output = answer,
                            stopReason = StopReason.COMPLETED
                        )
                        ctx.transcript?.writeSessionEnd(result)
                        return compactAndReturn(ctx, result)
                    }
                    logger.warn("[ReAct] Harness rejected the answer; continuing loop")
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

        // Max iterations: preserve long-lived STANDBY agents as a
        // safety fallback; ONE_SHOT agents remain terminal errors.
        logger.warn("[ReAct] reached max iterations (${ctx.maxIterations})")
        if (ctx.lifecycle == Lifecycle.STANDBY) {
            ctx.updateSessionStatus("standby", reason = "max_iterations: ${ctx.maxIterations}")
        } else {
            ctx.updateSessionStatus("error", reason = "max_iterations: ${ctx.maxIterations}")
        }
        val result = ctx.stats.toResult(
            output = extractLastAnswer(ctx.memory)
                ?: "Agent reached maximum iterations without producing a final answer.",
            stopReason = StopReason.MAX_ITERATIONS
        )
        ctx.transcript?.writeSessionEnd(result)
        return compactAndReturn(ctx, result)
    }

    /**
     * Decide whether a completion that carried **no tool call** should end the
     * run, and what the answer text is.
     *
     * The model is finished when it stops asking for tools — OpenAI
     * `finish_reason = "stop"`, Anthropic `stop_reason = "end_turn"` and
     * LangGraph agents with an empty `tool_calls` list all route to the end.
     *
     * Deliberately conservative in two ways: a truncated response
     * (`finishReason = length`) is NOT an answer and goes back to
     * [DefaultReActHarness] for a continuation prompt, and text-protocol
     * providers get one format reminder first, because such a model can stop in
     * the middle of a call before emitting anything parseable.
     */
    private fun plainTextAnswer(
        ctx: StrategyContext,
        completion: ChatCompletion,
        assistantText: String,
    ): String? {
        val text = assistantText.trim()
        if (text.isEmpty()) return null
        if (!stopsNormally(completion)) return null

        return when {
            // Native protocol: no tool call + normal stop = finished.
            usesNativeToolProtocol(ctx) -> text
            // No tools registered at all: any normal stop is the answer.
            ctx.toolRegistry.isEmpty() -> text
            // Text protocol: only after the reminder failed to produce a call.
            ctx.stats.consecutiveNoActionTurns >= MAX_NO_ACTION_TURNS -> text
            else -> null
        }
    }

    /**
     * Whether tools are supplied through the provider's structured function
     * calling channel (as opposed to `tool_call` JSON embedded in prose).
     */
    private fun usesNativeToolProtocol(ctx: StrategyContext): Boolean =
        !ctx.toolRegistry.isEmpty() && ctx.client.supportsToolCalling()

    /** True when the provider reported a normal (non-truncated) stop. */
    private fun stopsNormally(completion: ChatCompletion): Boolean {
        val reason = completion.finishReason?.lowercase() ?: return true
        return reason == "stop" || reason == "end_turn" || reason == "complete"
    }

    private fun extractLastAnswer(memory: ChatMemory): String? {
        return memory.messages().lastOrNull { it.role == "assistant" }?.content
    }
}

/**
 * Compact-on-park helper for the default strategies.
 *
 * When the run is producing a terminal result and [StrategyContext.lifecycle]
 * is [Lifecycle.STANDBY], calls [StrategyContext.compactFn] to shrink the
 * in-memory history to a summary plus the trailing rounds and writes it back to
 * the persistent store, so the next resume starts from a small context.
 *
 * Inactive when the lifecycle is ONE_SHOT, `compactOnStandby=false`,
 * `compactFn` is null, or [AkibaAgent.compact] returns false; a failure is
 * caught and logged so it cannot prevent the result from being returned.
 *
 * Not a suspend function — [AkibaAgent.compact] blocks on the HTTP exchange
 * internally, so `execute()` needs no `suspend` modifier.
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
// A bare error string discards whatever the child had already done.  These
// helpers pair the error with the child's last successful assistant turn and
// the tool observations that fed it, so the parent can salvage the partial
// work or re-spawn only the unfinished part.  The snapshot is bounded by
// `maxChars`; full detail stays in the child's persistent memory / transcript
// (`read_history_tool_call` against `sessionId`).

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
 * @param maxChars hard cap on the output size (default 40000, leaving room
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
