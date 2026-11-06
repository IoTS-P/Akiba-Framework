package org.iotsplab.akiba.llm.agent.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.iotsplab.akiba.llm.agent.context.ModelContextWindows
import org.iotsplab.akiba.llm.config.LLMKeyFileStore

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

    @Test
    fun configuredValueBeatsTheTable() {
        // The table says 128K for gpt-4o; a user override must win.
        val hit = ModelContextWindows.resolve("gpt-4o", 300_000)
        assertEquals(300_000, hit.contextLength)
        assertEquals(ModelContextWindows.Source.CONFIGURED, hit.source)
    }

    @Test
    fun tableBeatsTheFallback() {
        val hit = ModelContextWindows.resolve("claude-sonnet-4-6")
        assertEquals(1_000_000, hit.contextLength)
        assertEquals(ModelContextWindows.Source.TABLE, hit.source)
    }

    @Test
    fun unconfiguredUnknownModelFallsBackTo128k() {
        val hit = ModelContextWindows.resolve("phantom-9-preview")
        assertEquals(128_000, hit.contextLength)
        assertEquals(ModelContextWindows.Source.DEFAULT, hit.source)
    }

    @Test
    fun modelMissingFromTheTableUsesItsConfiguredWindow() {
        // The whole point of the setting: a renamed model with no prefix match.
        val hit = ModelContextWindows.resolve("phantom-9-preview", 512_000)
        assertEquals(512_000, hit.contextLength)
        assertEquals(ModelContextWindows.Source.CONFIGURED, hit.source)
    }

    @Test
    fun nonPositiveConfiguredValueIsIgnored() {
        assertEquals(128_000, ModelContextWindows.resolve("gpt-4o", 0).contextLength)
        assertEquals(
            ModelContextWindows.Source.TABLE,
            ModelContextWindows.resolve("gpt-4o", -5).source
        )
    }

    @Test
    fun configuredValueIsCappedAtTheUpperBound() {
        assertEquals(
            ModelContextWindows.MAX_CONTEXT_WINDOW,
            ModelContextWindows.resolve("gpt-4o", 9_000_000).contextLength
        )
    }

    @Test
    fun compactionThresholdFollowsTheResolvedWindow() {
        val configured = ModelContextWindows.resolve("phantom-9-preview", 400_000).contextLength
        assertEquals(300_000, compactTriggerTokens(configured, 0.75))
    }
}

/** Guard rails on the per-model value accepted by the key store. */
class ConfiguredContextLengthValidationTest {

    @Test
    fun nonPositiveIsRejected() {
        val e = assertFailsWith<IllegalArgumentException> {
            LLMKeyFileStore.validateContextLength("gpt-4o", 0)
        }
        assertTrue(e.message?.contains("must be positive") == true, e.message)
    }

    @Test
    fun overTheUpperBoundIsRejectedWithTheBoundInTheMessage() {
        val e = assertFailsWith<IllegalArgumentException> {
            LLMKeyFileStore.validateContextLength("gpt-4o", 2_000_001)
        }
        assertTrue(e.message?.contains("2000000") == true, e.message)
    }

    @Test
    fun theBoundsThemselvesAreAccepted() {
        assertEquals(1, LLMKeyFileStore.validateContextLength("m", 1))
        assertEquals(
            ModelContextWindows.MAX_CONTEXT_WINDOW,
            LLMKeyFileStore.validateContextLength("m", ModelContextWindows.MAX_CONTEXT_WINDOW)
        )
    }
}
