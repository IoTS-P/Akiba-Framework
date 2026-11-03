package org.iotsplab.akiba.llm.agent.core

import kotlin.test.Test
import kotlin.test.assertEquals
import org.iotsplab.akiba.llm.agent.context.ModelContextWindows

class ModelContextWindowsTest {

    @Test
    fun knownFamiliesResolveToTheirPublishedWindow() {
        assertEquals(1_000_000, ModelContextWindows.contextLength("deepseek-v4-flash"))
        assertEquals(128_000, ModelContextWindows.contextLength("deepseek-v3.2"))
        assertEquals(200_000, ModelContextWindows.contextLength("claude-sonnet-4-5"))
        assertEquals(1_000_000, ModelContextWindows.contextLength("claude-sonnet-4-6"))
        assertEquals(1_048_576, ModelContextWindows.contextLength("gemini-2.5-pro"))
        assertEquals(262_144, ModelContextWindows.contextLength("kimi-k2.5"))
        assertEquals(32_768, ModelContextWindows.contextLength("qwen-max"))
    }

    @Test
    fun longestPrefixWins() {
        assertEquals(128_000, ModelContextWindows.contextLength("gpt-5.1-chat-latest"))
        assertEquals(400_000, ModelContextWindows.contextLength("gpt-5.1"))
        assertEquals(1_048_576, ModelContextWindows.contextLength("qwen3-coder-plus"))
        assertEquals(262_144, ModelContextWindows.contextLength("qwen3-coder-next"))
    }

    @Test
    fun gatewayNamesAndVariantsStillMatch() {
        assertEquals(128_000, ModelContextWindows.contextLength("openai/gpt-4o"))
        assertEquals(1_047_576, ModelContextWindows.contextLength("gpt-4.1:free"))
    }

    @Test
    fun unknownAndCustomModelsFallBackTo128k() {
        assertEquals(128_000, ModelContextWindows.contextLength("my-own-finetune-v3"))
        assertEquals(128_000, ModelContextWindows.contextLength(""))
        assertEquals(128_000, ModelContextWindows.DEFAULT_CONTEXT_WINDOW)
    }
}
