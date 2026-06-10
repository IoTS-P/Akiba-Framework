package org.iotsplab.akiba.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.apache.logging.log4j.LogManager
import org.iotsplab.akiba.llm.client.LLMProvider
import org.iotsplab.akiba.llm.config.LLMKeyFileStore
import org.iotsplab.akiba.llm.config.LLMModelFetcher

private val logger = LogManager.getLogger("LLMConfigRoutes")

// ============================================================
//  DTOs
// ============================================================

data class LLMProviderInfo(
    val id: String,
    val displayName: String,
    val openAICompatible: Boolean
)

/** Request body for adding/updating a key entry. */
data class AddKeyEntryRequest(
    val provider: String,
    val modelNames: List<String>,
    val baseUrl: String? = null,
    val apiKey: String
)

/** Non-sensitive view of a key entry stored on disk. */
data class StoredKeyEntryResponse(
    val id: String,
    val provider: String,
    val modelNames: List<String>,
    val baseUrl: String?
)

// ============================================================
//  Routes
// ============================================================

fun Route.llmConfigRoutes() {

    /**
     * List the providers that the framework can talk to. Used by the
     * frontend to populate a dropdown.
     */
    get("/llm/providers") {
        val list = LLMProvider.entries.map {
            LLMProviderInfo(
                id = it.name,
                displayName = it.displayName,
                openAICompatible = it.isOpenAICompatible
            )
        }
        call.respond(mapOf("providers" to list))
    }

    /** List all keys stored on disk (API key values are omitted). */
    get("/llm/keys") {
        val entries = LLMKeyFileStore.load()
        call.respond(mapOf(
            "keys" to entries.map {
                StoredKeyEntryResponse(
                    id = it.id,
                    provider = it.provider,
                    modelNames = it.modelNames,
                    baseUrl = it.baseUrl
                )
            }
        ))
    }

    /**
     * Query the provider for available models.
     *
     * Query parameters:
     * - provider (required): provider identifier, e.g. OPEN_AI, DEEP_SEEK
     * - baseUrl (optional): override the provider's default endpoint
     * - apiKey (required): API key for authentication
     */
    get("/llm/models") {
        val providerStr = call.request.queryParameters["provider"]
        val baseUrl = call.request.queryParameters["baseUrl"]
        val apiKey = call.request.queryParameters["apiKey"]

        if (providerStr.isNullOrBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Query parameter 'provider' is required")
            )
            return@get
        }
        if (apiKey.isNullOrBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Query parameter 'apiKey' is required")
            )
            return@get
        }

        val provider = LLMProvider.fromString(providerStr) ?: run {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Unknown provider '$providerStr'. Valid: ${LLMProvider.entries.joinToString { it.name }}")
            )
            return@get
        }

        try {
            val models = LLMModelFetcher.fetchModels(provider, baseUrl, apiKey)
            call.respond(mapOf("models" to models))
        } catch (e: IllegalArgumentException) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to e.message)
            )
        } catch (e: Exception) {
            logger.error("Failed to fetch models for provider=$providerStr: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to fetch models: ${e.message}")
            )
        }
    }

    /** Add or update a key entry in the on-disk store. */
    post("/llm/keys") {
        val req = try {
            call.receive<AddKeyEntryRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf(
                "error" to "Invalid request body: ${e.message ?: e.javaClass.simpleName}"
            ))
            return@post
        }

        if (req.provider.isBlank() || req.modelNames.isEmpty() || req.modelNames.all { it.isBlank() } || req.apiKey.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "provider, modelNames and apiKey are required")
            )
            return@post
        }

        LLMKeyFileStore.addOrUpdate(
            LLMKeyFileStore.KeyEntry(
                provider = req.provider,
                modelNames = req.modelNames.filter { it.isNotBlank() },
                baseUrl = req.baseUrl?.takeIf { it.isNotBlank() },
                apiKey = req.apiKey
            )
        )
        call.respond(mapOf("message" to "Key saved"))
    }

    /** Delete a key from the on-disk store by provider + modelName. */
    delete("/llm/keys") {
        val provider = call.request.queryParameters["provider"]
        val modelName = call.request.queryParameters["modelName"]
        if (provider.isNullOrBlank() || modelName.isNullOrBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Query parameters 'provider' and 'modelName' are required")
            )
            return@delete
        }

        val removed = LLMKeyFileStore.remove(provider, modelName)
        if (removed) {
            call.respond(mapOf("message" to "Key removed"))
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Key not found"))
        }
    }
}
