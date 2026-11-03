package org.iotsplab.akiba.llm.client

import kotlinx.coroutines.flow.Flow

/**
 * Provider-agnostic interface for LLM chat completion.
 *
 * Each implementation wraps one vendor SDK (langchain4j provider module, raw
 * HTTP client, …) and translates between the Akiba domain types
 * ([ChatCompletion], [ChatChunk], …) and the vendor's native API.
 *
 * Instances are **not** thread-safe; use one client per session or serialize
 * access externally.
 */
interface AkibaLLMClient : AutoCloseable {

    /** The configuration this client was created from. */
    val config: LLMConfig

    // ============================================================
    //  Synchronous chat
    // ============================================================

    /**
     * Send a chat completion request and block until the full response
     * is available.
     *
     * @param systemPrompt  Optional system-level instructions.
     * @param messages      Ordered conversation history as [AgentChatMessage] list.
     *                      Role is one of "user", "assistant", "tool".
     * @param tools         Optional tool definitions, already converted to the
     *                      provider-facing schema by [Tool.toToolSpecification].
     * @return the completed response.
     */
    fun chat(
        systemPrompt: String? = null,
        messages: List<org.iotsplab.akiba.llm.memory.AgentChatMessage>,
        tools: List<dev.langchain4j.agent.tool.ToolSpecification>? = null
    ): ChatCompletion

    // ============================================================
    //  Streaming chat
    // ============================================================

    /**
     * Send a chat completion request and return a cold [Flow] of
     * incremental chunks.
     *
     * The flow completes after the final chunk ([ChatChunk.isComplete] == true).
     *
     * @param systemPrompt  Optional system-level instructions.
     * @param messages      Ordered conversation history as [AgentChatMessage] list.
     * @param tools         Optional tool definitions, already converted to the
     *                      provider-facing schema by [Tool.toToolSpecification].
     * @return a flow of response chunks.
     */
    fun chatStream(
        systemPrompt: String? = null,
        messages: List<org.iotsplab.akiba.llm.memory.AgentChatMessage>,
        tools: List<dev.langchain4j.agent.tool.ToolSpecification>? = null
    ): Flow<ChatChunk>

    // ============================================================
    //  Model capability queries
    // ============================================================

    /** Whether this provider/model supports tool/function calling. */
    fun supportsToolCalling(): Boolean = true

    /** Whether streaming is supported. */
    fun supportsStreaming(): Boolean = true

    /**
     * Estimate the token count for the given text using the model's
     * tokenizer approximation.  The result is a **rough estimate** and
     * should not be relied upon for billing.
     */
    fun estimateTokenCount(text: String): Int {
        // Naive estimate: ~4 chars per token for English, ~2 chars for CJK
        val cjkCount = text.count { it.code > 0x2E80 }
        val asciiCount = text.length - cjkCount
        return (asciiCount / 4 + cjkCount / 2).coerceAtLeast(1)
    }
}
