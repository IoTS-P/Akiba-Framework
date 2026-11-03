package org.iotsplab.akiba.llm.agent.context

/**
 * Hardcoded context windows, in tokens, matched by model-name prefix.
 *
 * Add a prefix here when a provider ships a model; nothing is fetched or
 * probed at runtime. Unknown models (including every user-defined one) get
 * [DEFAULT_CONTEXT_WINDOW].
 *
 * Values came from models.dev — re-check them when a family changes version
 * (`claude-sonnet-4-5` is 200K, `claude-sonnet-4-6` is 1M).
 */
object ModelContextWindows {

    /** Fallback for any model missing from the table: 128K tokens. */
    const val DEFAULT_CONTEXT_WINDOW: Int = 128_000

    private data class Entry(val prefix: String, val tokens: Int)

    private val entries: List<Entry> = listOf(
        // ---- OpenAI ----
        Entry("gpt-5.1-chat", 128_000),
        Entry("gpt-5.2-chat", 128_000),
        Entry("gpt-5.3-chat", 128_000),
        Entry("gpt-5", 400_000),
        Entry("gpt-4.1", 1_047_576),
        Entry("gpt-4o", 128_000),
        Entry("gpt-4-turbo", 128_000),
        Entry("gpt-oss", 131_072),
        Entry("o1", 200_000),
        Entry("o3", 200_000),
        Entry("o4", 200_000),

        // ---- Anthropic ----
        Entry("claude-opus-4-6", 1_000_000),
        Entry("claude-opus-4-7", 1_000_000),
        Entry("claude-opus-4-8", 1_000_000),
        Entry("claude-opus-5", 1_000_000),
        Entry("claude-sonnet-4-6", 1_000_000),
        Entry("claude-sonnet-5", 1_000_000),
        Entry("claude-haiku-5", 1_000_000),
        Entry("claude-fable", 1_000_000),
        Entry("claude-mythos", 1_000_000),
        Entry("claude-", 200_000),

        // ---- Google ----
        Entry("gemini-3-pro-image", 65_536),
        Entry("gemini-3.1-flash-lite-image", 65_536),
        Entry("gemini-3.1-flash-image", 131_072),
        Entry("gemini-", 1_048_576),

        // ---- DeepSeek ----
        Entry("deepseek-v4", 1_000_000),
        Entry("deepseek-chat-v3.1", 163_840),
        Entry("deepseek-chat-v3-0324", 163_840),
        Entry("deepseek-chat", 1_000_000),
        Entry("deepseek-reasoner", 1_000_000),
        Entry("deepseek-v3-0324", 163_840),
        Entry("deepseek-r1-0528", 163_840),
        Entry("deepseek-v3.2", 128_000),
        Entry("deepseek-v3.1", 131_072),
        Entry("deepseek-v3", 131_072),
        Entry("deepseek-r1", 128_000),
        Entry("deepseek-ocr", 8_192),

        // ---- Moonshot / Kimi ----
        Entry("kimi-k3", 1_048_576),
        Entry("kimi-k2.8", 1_048_576),
        Entry("kimi-k2.5", 262_144),
        Entry("kimi-k2.6", 262_144),
        Entry("kimi-k2.7", 262_144),
        Entry("kimi-k2-thinking", 262_144),
        Entry("kimi-k2-0905", 262_144),
        Entry("kimi-k2", 131_072),

        // ---- Zhipu / GLM ----
        Entry("glm-5.3", 1_000_000),
        Entry("glm-5.2", 1_000_000),
        Entry("glm-5.1", 200_000),
        Entry("glm-5", 204_800),
        Entry("glm-4.7", 204_800),
        Entry("glm-4.6v", 128_000),
        Entry("glm-4.6", 204_800),
        Entry("glm-4.5", 131_072),
        Entry("glm-image", 10_240),

        // ---- Qwen / Alibaba ----
        Entry("qwen-plus", 1_000_000),
        Entry("qwen-turbo", 1_000_000),
        Entry("qwen-flash", 1_000_000),
        Entry("qwen3-coder-plus", 1_048_576),
        Entry("qwen3-coder-flash", 1_000_000),
        Entry("qwen3-max", 262_144),
        Entry("qwen3-coder", 262_144),
        Entry("qwen3-next", 262_144),
        Entry("qwen3-235b", 262_144),
        Entry("qwen3-30b", 262_144),
        Entry("qwen3-", 131_072),
        Entry("qwen2.5-", 131_072),
        Entry("qwen-vl", 131_072),
        Entry("qwen-max", 32_768),

        // ---- Mistral ----
        Entry("mistral-large-4", 1_048_576),
        Entry("mistral-large-latest", 262_144),
        Entry("mistral-large-2512", 262_144),
        Entry("mistral-medium-2604", 262_144),
        Entry("mistral-large", 128_000),
        Entry("mistral-medium", 128_000),
        Entry("ministral", 262_144),
        Entry("devstral", 262_144),
        Entry("magistral", 128_000),
        Entry("codestral", 256_000),
    ).sortedByDescending { it.prefix.length }

    /**
     * Window for [modelName], matched by the longest prefix in the table.
     * Accepts gateway-style names (`openai/gpt-4o`) and `:variant` suffixes.
     */
    fun contextLength(modelName: String): Int {
        val normalized = normalize(modelName)
        if (normalized.isEmpty()) return DEFAULT_CONTEXT_WINDOW
        return entries.firstOrNull { normalized.startsWith(it.prefix) }?.tokens
            ?: DEFAULT_CONTEXT_WINDOW
    }

    private fun normalize(raw: String): String =
        raw.trim().lowercase()
            .substringAfterLast('/')
            .substringBefore(':')
}
