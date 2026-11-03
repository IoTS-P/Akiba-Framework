package org.iotsplab.akiba.llm.agent.core

import dev.langchain4j.agent.tool.ToolSpecification
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
import org.iotsplab.akiba.llm.tool.core.ToolRegistry

/**
 * Verifies the bounded LLM retry policy: `1 + maxRetries` attempts, 3/5/7 s
 * backoff, and a hard abort (null) once the budget is spent — the failure is
 * then propagated by the runtime instead of retrying forever.
 */
class AgentRetryPolicyTest {

    /** Client whose every call fails immediately. */
    private class FailingClient(
        override val config: LLMConfig,
    ) : AkibaLLMClient {
        var attempts = 0

        override fun chat(
            systemPrompt: String?,
            messages: List<AgentChatMessage>,
            tools: List<ToolSpecification>?
        ): ChatCompletion {
            attempts++
            throw IllegalStateException("simulated provider failure")
        }

        override fun chatStream(
            systemPrompt: String?,
            messages: List<AgentChatMessage>,
            tools: List<ToolSpecification>?
        ): Flow<ChatChunk> = flow {
            attempts++
            throw IllegalStateException("simulated provider failure")
        }

        override fun close() {}
    }

    private fun context(maxRetries: Int): Pair<StrategyContext, FailingClient> {
        val client = FailingClient(
            LLMConfig(
                provider = LLMProvider.DEEP_SEEK,
                modelName = "test-model",
                apiKey = "test-key",
                maxRetries = maxRetries,
            )
        )
        val ctx = StrategyContext(
            client = client,
            memory = InMemoryChatMemory(),
            memoryManager = null,
            toolRegistry = ToolRegistry(),
            sessionId = null,
            systemPrompt = "system",
            maxIterations = 1,
            enrichSystemPromptWithMemory = false,
            auditToolCalls = false,
        )
        return ctx to client
    }

    @Test
    fun `backoff schedule is 3s then 5s then 7s`() {
        assertEquals(listOf(3_000L, 5_000L, 7_000L), LLM_RETRY_BACKOFF_MS)
    }

    @Test
    fun `aborts after the retry budget is spent`() = runBlocking {
        val (ctx, client) = context(maxRetries = 1)

        val elapsed = measureTimeMillis { assertNull(ctx.callLLM("system")) }

        assertEquals(2, client.attempts, "expected 1 initial attempt + 1 retry")
        assertTrue(elapsed >= 2_900, "expected the 3s backoff to be honoured, took ${elapsed}ms")
        val lastError = ctx.stats.lastError.orEmpty()
        assertTrue(lastError.contains("after 1 retry"), "unexpected error: $lastError")
    }

    @Test
    fun `maxRetries zero means a single attempt`() = runBlocking {
        val (ctx, client) = context(maxRetries = 0)

        val elapsed = measureTimeMillis { assertNull(ctx.callLLM("system")) }

        assertEquals(1, client.attempts)
        assertTrue(elapsed < 1_000, "no backoff should be waited, took ${elapsed}ms")
        assertTrue(ctx.stats.lastError.orEmpty().contains("after 0 retries"))
    }
}
