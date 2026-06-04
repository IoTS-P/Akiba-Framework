package org.iotsplab.akiba.llm.client

import kotlinx.coroutines.flow.Flow

/**
 * Provider-agnostic interface for LLM chat completion.
 *
 * Each concrete implementation wraps a specific vendor SDK (langchain4j
 * provider module, raw HTTP client, etc.) and translates between the
 * Akiba domain types ([ChatCompletion], [ChatChunk], …) and the vendor's
 * native API.
 *
 * Typical lifecycle:
 * ```
 *   val client = LLMClientFactory.create(config)
 *   val result = client.chat(systemPrompt, messages)
 *   client.close()
 * ```
 *
 * Instances are **not** thread-safe; use one client per session or
 * serialize access externally.
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
     * @param tools         Optional tool definitions in the provider's native JSON schema format.
     * @return the completed response.
     */
    fun chat(
        systemPrompt: String? = null,
        messages: List<org.iotsplab.akiba.llm.memory.AgentChatMessage>,
        tools: List<String>? = null
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
     * @param tools         Optional tool definitions in the provider's native JSON schema format.
     * @return a flow of response chunks.
     */
    fun chatStream(
        systemPrompt: String? = null,
        messages: List<org.iotsplab.akiba.llm.memory.AgentChatMessage>,
        tools: List<String>? = null
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
