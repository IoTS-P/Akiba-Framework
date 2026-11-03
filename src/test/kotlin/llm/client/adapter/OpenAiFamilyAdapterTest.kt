package org.iotsplab.akiba.llm.client.adapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.iotsplab.akiba.llm.client.LLMConfig
import org.iotsplab.akiba.llm.client.LLMProvider

/**
 * Construction-time smoke test for the consolidated OpenAI-compatible adapter.
 *
 * Building the models runs langchain4j's own validation (api key, base url,
 * builder method availability), so this catches wiring regressions that a bare
 * `compileKotlin` cannot.  No network calls are made.
 */
class OpenAiFamilyAdapterTest {

    private fun config(provider: LLMProvider, baseUrl: String? = null) = LLMConfig(
        provider = provider,
        modelName = "test-model",
        apiKey = "test-key",
        baseUrl = baseUrl,
    )

    @Test
    fun `every OpenAI-compatible provider builds and closes cleanly`() {
        val providers = listOf(
            LLMProvider.OPEN_AI,
            LLMProvider.DEEP_SEEK,
            LLMProvider.MOONSHOT,
            LLMProvider.ZHIPU,
            LLMProvider.QWEN,
            LLMProvider.OPEN_AI_COMPATIBLE,
        )
        for (provider in providers) {
            val defaults = OpenAiFamilyDefaultsTable.of(provider)
            val adapter = OpenAiFamilyAdapter(
                config(provider, baseUrl = "https://example.invalid/v1")
            )
            assertEquals(
                defaults.supportsToolCalling,
                adapter.supportsToolCalling(),
                "tool-calling support mismatch for $provider",
            )
            adapter.close()
        }
    }

    @Test
    fun `only vendors with a v1 endpoint normalise the base url`() {
        assertTrue(OpenAiFamilyDefaultsTable.of(LLMProvider.DEEP_SEEK).appendV1IfMissing)
        assertTrue(OpenAiFamilyDefaultsTable.of(LLMProvider.MOONSHOT).appendV1IfMissing)
        assertTrue(OpenAiFamilyDefaultsTable.of(LLMProvider.QWEN).appendV1IfMissing)

        // Zhipu exposes /api/paas/v4 — appending /v1 would 404.
        assertFalse(OpenAiFamilyDefaultsTable.of(LLMProvider.ZHIPU).appendV1IfMissing)
        // A self-hosted endpoint's URL is authoritative.
        assertFalse(OpenAiFamilyDefaultsTable.of(LLMProvider.OPEN_AI_COMPATIBLE).appendV1IfMissing)
    }

    @Test
    fun `tool calling support is off for generic OpenAI-compatible endpoints`() {
        assertTrue(OpenAiFamilyDefaultsTable.of(LLMProvider.DEEP_SEEK).supportsToolCalling)
        assertFalse(OpenAiFamilyDefaultsTable.of(LLMProvider.OPEN_AI_COMPATIBLE).supportsToolCalling)
    }

    @Test
    fun `native providers are rejected by the openai family table`() {
        val rejected = runCatching { OpenAiFamilyDefaultsTable.of(LLMProvider.ANTHROPIC) }
        assertTrue(rejected.isFailure, "Anthropic must not route through the OpenAI family")
    }
}
