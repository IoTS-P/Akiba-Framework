package org.iotsplab.akiba.server.routes

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.apache.logging.log4j.LogManager
import java.nio.file.Files
import java.nio.file.Path

private val logger = LogManager.getLogger("RuntimeConfigRoutes")

data class RuntimeConfigEntry(
    val name: String,
    val description: String?,
    val json: String,
    val updatedAt: String
)

data class RuntimeConfigRequest(
    val name: String,
    val description: String? = null,
    val json: String
)

private val mapper = jacksonObjectMapper()

/** Base directory for all user configs. */
private val configBase: Path get() = Path.of(System.getProperty("user.home"), ".akiba", "user_configs")

/**
 * Resolve the config directory for a given instance.
 * Configs are stored at: ~/.akiba/user_configs/<instance>/
 */
fun configDirForInstance(instanceName: String): Path {
    val dir = configBase.resolve(instanceName)
    Files.createDirectories(dir)
    return dir
}

fun Route.runtimeConfigRoutes() {

    // List all runtime configs for the current instance
    get("/runtime-configs") {
        val instance = call.instanceHeader() ?: "default"
        val configDir = configDirForInstance(instance)
        try {
            val configs = Files.list(configDir)
                .filter { it.toString().endsWith(".json") }
                .toList()
                .map { path ->
                    RuntimeConfigEntry(
                        name = path.fileName.toString().removeSuffix(".json"),
                        description = null,
                        json = Files.readString(path),
                        updatedAt = Files.getLastModifiedTime(path).toString()
                    )
                }
                .sortedBy { it.name }
            call.respond(mapOf("configs" to configs))
        } catch (e: Exception) {
            logger.error("Failed to list runtime configs: {}", e.message, e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    // Get a specific runtime config
    get("/runtime-configs/{name}") {
        val instance = call.instanceHeader() ?: "default"
        val configDir = configDirForInstance(instance)
        val name = call.parameters["name"] ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing config name"))
            return@get
        }
        val filePath = configDir.resolve("$name.json")
        if (!Files.exists(filePath)) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Config '$name' not found"))
            return@get
        }
        val content = Files.readString(filePath)
        call.respond(
            RuntimeConfigEntry(
                name = name,
                description = null,
                json = content,
                updatedAt = Files.getLastModifiedTime(filePath).toString()
            )
        )
    }

    // Save (create or update) a runtime config
    put("/runtime-configs") {
        val instance = call.instanceHeader() ?: "default"
        val configDir = configDirForInstance(instance)
        val req = try {
            call.receive<RuntimeConfigRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body"))
            return@put
        }

        val safeName = req.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        if (safeName.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Config name is required"))
            return@put
        }

        try {
            mapper.readTree(req.json)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid JSON: ${e.message}"))
            return@put
        }

        try {
            val filePath = configDir.resolve("$safeName.json")
            // Save raw config JSON directly — no wrapper metadata, the child
            // process parses this file as-is via ConfigManager / Jackson.
            Files.writeString(filePath,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readTree(req.json)))
            logger.info("Saved runtime config: $safeName")
            call.respond(mapOf("message" to "Configuration '$safeName' saved."))
        } catch (e: Exception) {
            logger.error("Failed to save runtime config: {}", e.message, e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    // Delete a runtime config
    delete("/runtime-configs/{name}") {
        val instance = call.instanceHeader() ?: "default"
        val configDir = configDirForInstance(instance)
        val name = call.parameters["name"] ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing config name"))
            return@delete
        }
        val filePath = configDir.resolve("$name.json")
        if (!Files.exists(filePath)) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Config '$name' not found"))
            return@delete
        }
        try {
            Files.delete(filePath)
            logger.info("Deleted runtime config: $name")
            call.respond(mapOf("message" to "Configuration '$name' deleted."))
        } catch (e: Exception) {
            logger.error("Failed to delete runtime config: {}", e.message, e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }
}
