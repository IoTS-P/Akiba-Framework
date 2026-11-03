package org.iotsplab.akiba.llm.agent.core

import dev.langchain4j.agent.tool.ToolSpecification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.iotsplab.akiba.llm.client.AkibaLLMClient
import org.iotsplab.akiba.llm.client.ChatChunk
import org.iotsplab.akiba.llm.client.ChatCompletion
import org.iotsplab.akiba.llm.client.LLMConfig
import org.iotsplab.akiba.llm.client.LLMProvider
import org.iotsplab.akiba.llm.memory.AgentChatMessage
import org.iotsplab.akiba.llm.memory.InMemoryChatMemory
import org.iotsplab.akiba.llm.tool.core.Tool
import org.iotsplab.akiba.llm.tool.core.ToolRegistry

/**
 * Verifies *when* an iteration ends.
 *
 * The rule is the one every tool-calling agent uses: the model is finished when
 * it stops requesting tools (OpenAI `finish_reason=stop`, Anthropic `end_turn`,
 * LangGraph: empty `tool_calls` → END).  A literal `Final Answer:` marker is no
 * longer required — it only trims the visible payload when present.
 */
class AgentCompletionRuleTest {

    /** Returns scripted completions in order, repeating the last one. */
    private class ScriptedClient(
        private val script: List<ChatCompletion>,
        private val toolCalling: Boolean = true,
    ) : AkibaLLMClient {
        var calls = 0
        override val config = LLMConfig(
            provider = LLMProvider.DEEP_SEEK,
            modelName = "test-model",
            apiKey = "test-key",
        )

        override fun chat(
            systemPrompt: String?,
            messages: List<AgentChatMessage>,
            tools: List<ToolSpecification>?
        ): ChatCompletion = next()

        override fun chatStream(
            systemPrompt: String?,
            messages: List<AgentChatMessage>,
            tools: List<ToolSpecification>?
        ): Flow<ChatChunk> = flow {
            val completion = next()
            if (completion.content.isNotEmpty()) {
                emit(ChatChunk(delta = completion.content, isComplete = false))
            }
            emit(
                ChatChunk(
                    delta = "",
                    isComplete = true,
                    finishReason = completion.finishReason,
                    toolCalls = completion.toolCalls,
                )
            )
        }

        override fun supportsToolCalling(): Boolean = toolCalling

        private fun next(): ChatCompletion {
            val i = (calls++).coerceAtMost(script.lastIndex)
            return script[i]
        }

        override fun close() {}
    }

    private fun context(client: AkibaLLMClient, maxIterations: Int = 5): StrategyContext {
        val registry = ToolRegistry()
        registry.register(Tool("echo", "echo the input") { args -> args["text"]?.toString() ?: "" })
        return StrategyContext(
            client = client,
            memory = InMemoryChatMemory(),
            memoryManager = null,
            toolRegistry = registry,
            sessionId = null,
            systemPrompt = "system",
            maxIterations = maxIterations,
            enrichSystemPromptWithMemory = false,
            auditToolCalls = false,
        )
    }

    @Test
    fun `native tool calling - a plain stop with no tool call finishes the run`() = runBlocking {
        val client = ScriptedClient(
            listOf(ChatCompletion(content = "The binary is a hello-world ELF.", finishReason = "stop"))
        )
        val ctx = context(client)

        val result = ReActStrategy().execute(ctx)

        assertEquals(StopReason.COMPLETED, result.stopReason)
        assertEquals("The binary is a hello-world ELF.", result.output)
        assertEquals(1, client.calls, "must finish on the first response, not re-prompt")
        assertEquals(1, ctx.stats.iterations)
    }

    @Test
    fun `a truncated response is not treated as an answer`() = runBlocking {
        val client = ScriptedClient(
            listOf(
                ChatCompletion(content = "The binary is a hel", finishReason = "length"),
                ChatCompletion(content = "The binary is a hello-world ELF.", finishReason = "stop"),
            )
        )
        val ctx = context(client)

        val result = ReActStrategy().execute(ctx)

        assertEquals(StopReason.COMPLETED, result.stopReason)
        assertEquals("The binary is a hello-world ELF.", result.output)
        assertEquals(2, client.calls, "the truncated turn must trigger a continuation")
    }

    @Test
    fun `an answer that looks like a tool call is still an answer`() = runBlocking {
        // Regression: with native tool calling the structured channel is
        // authoritative, so a JSON payload in the reply must never be scanned
        // for calls — even when it has exactly the flat `name`/`arguments`
        // shape the text parser accepts.
        val payload = """Here is the summary: {"name": "echo", "arguments": {"text": "hi"}}"""
        val client = ScriptedClient(
            listOf(ChatCompletion(content = payload, finishReason = "stop"))
        )
        val ctx = context(client)

        val result = ReActStrategy().execute(ctx)

        assertEquals(StopReason.COMPLETED, result.stopReason)
        assertEquals(payload, result.output)
        assertEquals(1, client.calls)
        assertEquals(0, ctx.stats.toolCallsMade, "the JSON payload must not be executed as a tool")
    }

    @Test
    fun `text protocol gets one reminder, then the prose is accepted`() = runBlocking {
        val client = ScriptedClient(
            listOf(ChatCompletion(content = "I think it is a hello-world binary.", finishReason = "stop")),
            toolCalling = false,
        )
        val ctx = context(client)

        val result = ReActStrategy().execute(ctx)

        assertEquals(StopReason.COMPLETED, result.stopReason)
        assertEquals("I think it is a hello-world binary.", result.output)
        assertEquals(
            MAX_NO_ACTION_TURNS,
            client.calls,
            "text protocol should be reminded once before its answer is accepted",
        )
        assertTrue(
            ctx.memory.messages().any { msg ->
                msg.role == "user" && msg.content.contains("tool", ignoreCase = true)
            },
            "the format reminder should have been injected into the conversation",
        )
    }
}
