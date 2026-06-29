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
    val skipCurrentAction: Boolean = false
) {
    val isEmpty: Boolean
        get() = userMessages.isEmpty() && systemPromptAppend.isNullOrBlank() &&
            !rejectFinalAnswer && !blockCurrentAction && !skipCurrentAction

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
    }
}

fun StrategyContext.applyHarnessDirective(directive: AgentHarnessDirective, label: String = "harness") {
    if (directive.isEmpty) return
    directive.userMessages.forEach { message ->
        memory.addUserMessage(message)
        transcript?.writeFormatReminder("[$label] $message")
    }
}

fun joinPromptParts(vararg parts: String?): String =
    parts.filter { !it.isNullOrBlank() }.joinToString("\n\n")
