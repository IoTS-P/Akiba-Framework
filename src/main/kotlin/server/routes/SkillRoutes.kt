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
import org.iotsplab.akiba.llm.skill.SkillManager
import java.nio.file.Files

fun Route.skillRoutes() {
    get("/skills") {
        val username = call.currentUsernameOrDefault()
        call.respond(mapOf("skills" to SkillManager.listSkills(username)))
    }

    get("/skills/{skillId}") {
        val username = call.currentUsernameOrDefault()
        val skillId = call.parameters["skillId"].orEmpty()
        if (skillId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing skillId"))
            return@get
        }
        val maxChars = call.request.queryParameters["maxChars"]?.toIntOrNull() ?: 20_000
        val path = call.request.queryParameters["path"]?.takeIf { it.isNotBlank() }
        try {
            call.respond(
                if (path == null) SkillManager.readSkill(username, skillId, maxChars)
                else SkillManager.readSkillFile(username, skillId, path, maxChars)
            )
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: e.javaClass.simpleName)))
        }
    }

    get("/skills/{skillId}/file") {
        val username = call.currentUsernameOrDefault()
        val skillId = call.parameters["skillId"].orEmpty()
        val path = call.request.queryParameters["path"].orEmpty()
        if (skillId.isBlank() || path.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing skillId or path"))
            return@get
        }
        val maxChars = call.request.queryParameters["maxChars"]?.toIntOrNull() ?: 20_000
        try {
            call.respond(SkillManager.readSkillFile(username, skillId, path, maxChars))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: e.javaClass.simpleName)))
        }
    }

    post("/skills/upload") {
        val username = call.currentUsernameOrDefault()
        val tmp = Files.createTempFile("akiba-skill-upload-", ".zip")
        var gotFile = false
        try {
            call.receiveMultipart().forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        if (gotFile) {
                            part.dispose()
                            throw IllegalArgumentException("Upload exactly one skill zip file")
                        }
                        val name = part.originalFileName ?: "skill.zip"
                        if (!name.lowercase().endsWith(".zip")) {
                            part.dispose()
                            throw IllegalArgumentException("Skill upload must be a .zip file")
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
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No zip file uploaded"))
                return@post
            }
            val skill = SkillManager.installSkillZip(username, tmp)
            call.respond(mapOf("message" to "Skill installed", "skill" to skill))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: e.javaClass.simpleName)))
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    delete("/skills/{skillId}") {
        val username = call.currentUsernameOrDefault()
        val skillId = call.parameters["skillId"].orEmpty()
        if (skillId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing skillId"))
            return@delete
        }
        try {
            SkillManager.deleteSkill(username, skillId)
            call.respond(mapOf("message" to "Skill deleted"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: e.javaClass.simpleName)))
        }
    }
}
