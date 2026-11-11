package org.iotsplab.akiba.llm.agent.dummy

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.iotsplab.akiba.llm.client.AkibaLLMClient
import org.iotsplab.akiba.llm.client.ChatChunk
import org.iotsplab.akiba.llm.client.ChatCompletion
import org.iotsplab.akiba.llm.client.LLMConfig
import org.iotsplab.akiba.llm.client.LLMProvider
import org.iotsplab.akiba.llm.memory.AgentChatMessage

/**
 * A scripted stand-in for a real model, so agent behaviour can be tested
 * without a network, an API key, or a paid provider.
 *
 * The client answers from a fixed list: the first reply for the first call,
 * the second for the second, and — once the list runs out — the last reply
 * forever. Every request is recorded in [requests] so a test can assert what
 * the agent actually sent (system prompt, tool results, history growth).
 *
 * @param script replies in call order; must not be empty.
 */
class DummyLLMClient(
    private val script: List<ChatCompletion>,
) : AkibaLLMClient {

    init {
        require(script.isNotEmpty()) { "DummyLLMClient needs at least one scripted reply" }
    }

    override val config: LLMConfig = LLMConfig(
        provider = LLMProvider.OPEN_AI,
        modelName = "dummy-scripted",
        apiKey = "dummy",
    )

    /** Messages of every request, in call order. */
    val requests: MutableList<List<AgentChatMessage>> = mutableListOf()

    /** Number of model calls made so far. */
    val callCount: Int get() = requests.size

    private var scriptIndex = 0

    override fun chat(
        systemPrompt: String?,
        messages: List<AgentChatMessage>,
        tools: List<dev.langchain4j.agent.tool.ToolSpecification>?,
    ): ChatCompletion {
        requests += messages
        return next()
    }

    override fun chatStream(
        systemPrompt: String?,
        messages: List<AgentChatMessage>,
        tools: List<dev.langchain4j.agent.tool.ToolSpecification>?,
    ): Flow<ChatChunk> = flow {
        requests += messages
        val reply = next()
        // Split the text into a few deltas so streaming code paths are exercised,
        // then mark the flow complete with the tool calls attached.
        if (reply.content.isNotEmpty()) {
            val step = (reply.content.length / 3).coerceAtLeast(1)
            reply.content.chunked(step).forEach { piece ->
                emit(ChatChunk(delta = piece))
            }
        }
        emit(
            ChatChunk(
                delta = "",
                isComplete = true,
                finishReason = reply.finishReason,
                toolCalls = reply.toolCalls,
            ),
        )
    }

    override fun supportsToolCalling(): Boolean = true

    override fun supportsStreaming(): Boolean = true

    private fun next(): ChatCompletion {
        val reply = script[scriptIndex.coerceAtMost(script.lastIndex)]
        scriptIndex++
        return reply
    }

    override fun close() = Unit
}
