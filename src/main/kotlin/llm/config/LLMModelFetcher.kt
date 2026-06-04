package org.iotsplab.akiba.llm.config

import com.fasterxml.jackson.databind.DeserializationFeature
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import org.apache.logging.log4j.LogManager
import org.iotsplab.akiba.llm.client.LLMProvider

/**
 * Fetches the list of available models from a remote LLM provider.
 *
 * Supports OpenAI-compatible `/v1/models`, Ollama `/api/tags`,
 * Google Gemini `/v1beta/models`, and Azure OpenAI deployments.
 */
object LLMModelFetcher {

    private val logger = LogManager.getLogger(LLMModelFetcher::class.java)

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            jackson {
                disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            }
        }
        install(HttpRequestRetry) {
            maxRetries = 2
            exponentialDelay()
        }
    }

    /**
     * Attempt to fetch the list of available model names from the provider.
     *
     * @param provider Which LLM provider to query.
     * @param baseUrl  Optional base URL override. When null the provider's
     *                 well-known default endpoint is used.
     * @param apiKey   API key for authentication.
     * @return List of model identifier strings (may be empty if the provider
     *         returns no models or the endpoint is unsupported).
     * @throws IllegalArgumentException if the provider is unknown or baseUrl
     *         cannot be determined.
     * @throws RuntimeException if the HTTP request fails.
     */
    suspend fun fetchModels(provider: LLMProvider, baseUrl: String?, apiKey: String): List<String> {
        val effectiveBaseUrl = baseUrl ?: defaultBaseUrl(provider)
            ?: throw IllegalArgumentException("No default baseUrl for provider $provider; please provide one.")

        return when (provider) {
            LLMProvider.OLLAMA -> fetchOllamaModels(effectiveBaseUrl)
            LLMProvider.GOOGLE_GEMINI -> fetchGeminiModels(effectiveBaseUrl, apiKey)
            LLMProvider.AZURE_OPEN_AI -> fetchAzureModels(effectiveBaseUrl, apiKey)
            else -> fetchOpenAICompatibleModels(effectiveBaseUrl, apiKey)
        }
    }

    // ------------------------------------------------------------------
    // Provider-specific fetchers
    // ------------------------------------------------------------------

    private suspend fun fetchOpenAICompatibleModels(baseUrl: String, apiKey: String): List<String> {
        val url = normalizeOpenAIUrl(baseUrl) + "/models"
        logger.info("Fetching models from OpenAI-compatible endpoint: $url")

        val response: HttpResponse = client.get(url) {
            header("Authorization", "Bearer $apiKey")
        }

        if (response.status != HttpStatusCode.OK) {
            throw RuntimeException("OpenAI-compatible models endpoint returned ${response.status}")
        }

        val body = response.body<OpenAIModelsResponse>()
        return body.data.map { it.id }
    }

    private suspend fun fetchOllamaModels(baseUrl: String): List<String> {
        val url = baseUrl.trimEnd('/') + "/api/tags"
        logger.info("Fetching models from Ollama endpoint: $url")

        val response: HttpResponse = client.get(url)
        if (response.status != HttpStatusCode.OK) {
            throw RuntimeException("Ollama tags endpoint returned ${response.status}")
        }

        val body = response.body<OllamaTagsResponse>()
        return body.models.map { it.name }
    }

    private suspend fun fetchGeminiModels(baseUrl: String, apiKey: String): List<String> {
        val url = baseUrl.trimEnd('/') + "/models?key=$apiKey"
        logger.info("Fetching models from Gemini endpoint: $url")

        val response: HttpResponse = client.get(url)
        if (response.status != HttpStatusCode.OK) {
            throw RuntimeException("Gemini models endpoint returned ${response.status}")
        }

        val body = response.body<GeminiModelsResponse>()
        return body.models.map { it.name.removePrefix("models/") }
    }

    private suspend fun fetchAzureModels(baseUrl: String, apiKey: String): List<String> {
        val url = baseUrl.trimEnd('/') + "/openai/deployments?api-version=2023-03-15-preview"
        logger.info("Fetching models from Azure OpenAI endpoint: $url")

        val response: HttpResponse = client.get(url) {
            header("api-key", apiKey)
        }
        if (response.status != HttpStatusCode.OK) {
            throw RuntimeException("Azure deployments endpoint returned ${response.status}")
        }

        val body = response.body<AzureDeploymentsResponse>()
        return body.data.map { it.id }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun normalizeOpenAIUrl(baseUrl: String): String {
        val trimmed = baseUrl.trimEnd('/')
        return if (trimmed.endsWith("/v1")) trimmed else "$trimmed/v1"
    }

    private fun defaultBaseUrl(provider: LLMProvider): String? = when (provider) {
        LLMProvider.OPEN_AI -> "https://api.openai.com"
        LLMProvider.DEEP_SEEK -> "https://api.deepseek.com"
        LLMProvider.MOONSHOT -> "https://api.moonshot.cn/v1"
        LLMProvider.ZHIPU -> "https://open.bigmodel.cn/api/paas/v4"
        LLMProvider.QWEN -> "https://dashscope.aliyuncs.com/compatible-mode/v1"
        LLMProvider.MISTRAL -> "https://api.mistral.ai"
        LLMProvider.OLLAMA -> "http://localhost:11434"
        LLMProvider.GOOGLE_GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
        else -> null
    }

    // ------------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------------

    private data class OpenAIModelsResponse(val data: List<OpenAIModelItem> = emptyList())
    private data class OpenAIModelItem(val id: String)

    private data class OllamaTagsResponse(val models: List<OllamaModelItem> = emptyList())
    private data class OllamaModelItem(val name: String)

    private data class GeminiModelsResponse(val models: List<GeminiModelItem> = emptyList())
    private data class GeminiModelItem(val name: String)

    private data class AzureDeploymentsResponse(val data: List<AzureDeploymentItem> = emptyList())
    private data class AzureDeploymentItem(val id: String)
}
