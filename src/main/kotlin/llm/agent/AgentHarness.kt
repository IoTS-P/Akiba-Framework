package org.iotsplab.akiba.llm.agent

import org.iotsplab.akiba.llm.client.ChatCompletion

/**
 * Advisory and enforcement hooks around an [AgentStrategy] execution loop.
 *
 * Harnesses are intended for domain-specific workflow control: coverage gates,
 * required tool ordering, final-answer validation, durable-state checks, and
 * future multi-agent orchestration. The default implementation is a no-op.
 */
interface AgentHarness {
    val name: String get() = this::class.simpleName ?: "AgentHarness"

    fun beforeRun(ctx: StrategyContext): AgentHarnessDirective = AgentHarnessDirective.None
    fun beforeIteration(ctx: StrategyContext): AgentHarnessDirective = AgentHarnessDirective.None
    fun beforeChat(ctx: StrategyContext): AgentHarnessDirective = AgentHarnessDirective.None

    fun afterAssistantMessage(
        ctx: StrategyContext,
        assistantText: String,
        completion: ChatCompletion
    ): AgentHarnessDirective = AgentHarnessDirective.None

    fun beforeToolCalls(
        ctx: StrategyContext,
        toolCalls: List<ParsedToolCall>
    ): AgentHarnessDirective = AgentHarnessDirective.None

    fun beforeToolExecution(
        ctx: StrategyContext,
        toolCall: ParsedToolCall
    ): AgentHarnessDirective = AgentHarnessDirective.None

    fun afterToolExecution(
        ctx: StrategyContext,
        toolCall: ParsedToolCall,
        toolResult: StrategyContext.ToolResult
    ): AgentHarnessDirective = AgentHarnessDirective.None

    fun afterNoAction(
        ctx: StrategyContext,
        assistantText: String,
        completion: ChatCompletion? = null
    ): AgentHarnessDirective = AgentHarnessDirective.None

    fun validateFinalAnswer(
        ctx: StrategyContext,
        assistantText: String,
        finalAnswer: String
    ): AgentHarnessDirective = AgentHarnessDirective.None
}

object DefaultAgentHarness : AgentHarness {
    override val name: String = "DefaultAgentHarness"

    // Drain is best-effort; null service or zero unread messages
    // returns None, matching the no-op default.
    override fun beforeIteration(ctx: StrategyContext): AgentHarnessDirective =
        applyMailboxDrain(ctx, ctx.mailboxService)
}

data class AgentHarnessDirective(
    val userMessages: List<String> = emptyList(),
    val systemPromptAppend: String? = null,
    val rejectFinalAnswer: Boolean = false,
    val blockCurrentAction: Boolean = false,
    val skipCurrentAction: Boolean = false,
    /**
     * When `true`, the strategy interrupts the current iteration
     * immediately after processing this directive, forces a context
     * compaction (if [StrategyContext.compactFn] is available), and
     * injects [loopBreakReason] as a user message before resuming
     * the loop.  Used to break out of unrecoverable LLM loops where
     * the agent keeps issuing the same tool call despite repeated
     * warnings.
     */
    val forceCompaction: Boolean = false,
    /** Human-readable explanation injected alongside [forceCompaction]. */
    val loopBreakReason: String? = null,
) {
    val isEmpty: Boolean
        get() = userMessages.isEmpty() && systemPromptAppend.isNullOrBlank() &&
            !rejectFinalAnswer && !blockCurrentAction && !skipCurrentAction &&
            !forceCompaction

    companion object {
        val None = AgentHarnessDirective()

        fun userMessage(message: String): AgentHarnessDirective =
            AgentHarnessDirective(userMessages = listOf(message))

        fun promptAppend(text: String): AgentHarnessDirective =
            AgentHarnessDirective(systemPromptAppend = text)

        fun rejectFinalAnswer(reason: String): AgentHarnessDirective =
            AgentHarnessDirective(
                userMessages = listOf(reason),
                rejectFinalAnswer = true
            )

        fun blockAction(reason: String): AgentHarnessDirective =
            AgentHarnessDirective(
                userMessages = listOf(reason),
                blockCurrentAction = true
            )

        fun skipAction(syntheticResult: String? = null): AgentHarnessDirective =
            AgentHarnessDirective(
                userMessages = syntheticResult?.let { listOf(it) } ?: emptyList(),
                skipCurrentAction = true
            )

        /**
         * Force the strategy to compact context and break the current
         * iteration's flow, injecting [reason] as a user message.
         */
        fun forceCompaction(reason: String): AgentHarnessDirective =
            AgentHarnessDirective(
                userMessages = listOf(reason),
                forceCompaction = true,
                loopBreakReason = reason,
            )
    }
}

fun StrategyContext.applyHarnessDirective(directive: AgentHarnessDirective, label: String = "harness") {
    if (directive.isEmpty) return
    directive.userMessages.forEach { message ->
        if (isTransientWakeContextMessage(message)) {
            // Wake boards / resume hints are real-time scheduling
            // views.  They must be visible to the *current* LLM
            // call, but must NOT be persisted into chat history or
            // compaction summaries.  Persisting them caused old
            // wake boards to linger and confuse later turns.
            addTransientUserMessage(message)
        } else {
            memory.addUserMessage(message)
        }
        transcript?.writeFormatReminder("[$label] $message")
    }
}

/** True for real-time scheduling panels that should not enter history. */
private fun isTransientWakeContextMessage(message: String): Boolean =
    message.startsWith("[Agent Wake Board]") ||
        message.startsWith("[resume context]") ||
        message.contains("\n[resume context]") ||
        message.contains("\n[BACKPRESSURE]")

fun joinPromptParts(vararg parts: String?): String =
    parts.filter { !it.isNullOrBlank() }.joinToString("\n\n")
