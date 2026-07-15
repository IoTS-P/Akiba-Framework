package org.iotsplab.akiba.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.call
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.iotsplab.akiba.module.ModuleJarManager
import java.nio.file.Files

/**
 * Routes for module jar management.
 *
 * - `GET  /api/modules`         — list available modules (public + current user's private)
 * - `POST /api/modules/upload`   — upload a module jar (stored as private to the current user)
 * - `DELETE /api/modules/{jarFileName}` — delete a private module jar
 */
fun Route.moduleRoutes() {
    get("/modules") {
        val username = call.currentUsernameOrDefault()
        val modules = ModuleJarManager.listModules(username)
        call.respond(mapOf("modules" to modules))
    }

    post("/modules/upload") {
        val username = call.currentUsernameOrDefault()
        val tmp = Files.createTempFile("akiba-module-upload-", ".jar")
        var gotFile = false
        try {
            call.receiveMultipart().forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        if (gotFile) {
                            part.dispose()
                            throw IllegalArgumentException("Upload exactly one jar file")
                        }
                        val name = part.originalFileName ?: "module.jar"
                        if (!name.lowercase().endsWith(".jar")) {
                            part.dispose()
                            throw IllegalArgumentException("Module upload must be a .jar file")
                        }
                        part.streamProvider().use { input ->
                            Files.newOutputStream(tmp).use { output -> input.copyTo(output) }
                        }
                        gotFile = true
                    }
                    else -> Unit
                }
                part.dispose()
            }
            if (!gotFile) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No jar file uploaded"))
                return@post
            }
            val entry = ModuleJarManager.installModuleJar(username, tmp)
            call.respond(mapOf("message" to "Module installed", "module" to entry))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: e.javaClass.simpleName)))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: e.javaClass.simpleName)))
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    delete("/modules/{jarFileName}") {
        val username = call.currentUsernameOrDefault()
        val jarFileName = call.parameters["jarFileName"].orEmpty()
        if (jarFileName.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing jarFileName"))
            return@delete
        }
        try {
            ModuleJarManager.deleteModule(username, jarFileName)
            call.respond(mapOf("message" to "Module deleted"))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: e.javaClass.simpleName)))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: e.javaClass.simpleName)))
        }
    }
}
