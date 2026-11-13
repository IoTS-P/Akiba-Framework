package org.iotsplab.akiba.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.iotsplab.akiba.managers.TurnBroker
import org.iotsplab.akiba.managers.ProjectWorkers

/** A turn handed to a resident project worker. */
@Serializable
data class TurnDto(
    val id: String,
    val sessionId: String,
    val programId: String? = null,
    val prompt: String,
    val systemPrompt: String = "",
    val username: String = "",
    val programName: String? = null,
    val llm: TurnLlmDto? = null,
)

@Serializable
data class TurnLlmDto(
    val provider: String,
    val modelName: String,
    val apiKey: String,
    val baseUrl: String? = null,
)

/** The worker's poll result; `turn` is null when the wait expired with nothing to do. */
@Serializable
data class TurnNextResponse(val turn: TurnDto? = null)

/** One row of a project's file tree, as a worker read it. */
@Serializable
data class ProjectIndexEntryDto(
    val path: String,
    val name: String,
    val folder: Boolean,
    val size: Long = 0,
    val modified: Long = 0,
)

/** The tree a worker hands over for indexing. */
@Serializable
data class ProjectIndexRequest(
    val project: String,
    val entries: List<ProjectIndexEntryDto> = emptyList(),
)

/** Whether the user has asked to stop this turn. */
@Serializable
data class TurnCancelState(val cancelled: Boolean = false)

/** How a turn ended, as the worker reports it back. */
@Serializable
data class TurnFinishedRequest(
    val turnId: String,
    val status: String = "ok",
    val error: String? = null,
)

/**
 * The two calls a resident worker makes: ask for the next turn, and say how the last one
 * ended. Pull rather than push, so the server never has to reach into a worker's port and
 * a worker that dies simply stops polling.
 */
fun Route.workerTurnRoutes(broker: TurnBroker = ProjectWorkers.broker) {
    get("/agent/internal/turn/next") {
        val instance = call.requireInstanceHeader() ?: return@get
        if (instance.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing instance header"))
            return@get
        }
        val project = call.request.queryParameters["project"]?.takeIf { it.isNotBlank() }
        if (project == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "project is required"))
            return@get
        }
        // Workers ask with a long wait: that is cheaper than polling in a tight loop and
        // still lets them notice a shutdown request promptly.
        val waitMs = call.request.queryParameters["waitMs"]?.toLongOrNull()?.coerceIn(0L, 30_000L) ?: 20_000L
        val turn = withContext(Dispatchers.IO) { broker.next(project, waitMs) }
        call.respond(
            TurnNextResponse(
                turn?.let {
                    TurnDto(
                        it.id, it.sessionId, it.programId, it.prompt, it.systemPrompt, it.username,
                        it.programName, it.llm?.let { wire -> TurnLlmDto(wire.provider, wire.modelName, wire.apiKey, wire.baseUrl) },
                    )
                },
            ),
        )
    }

    // Polled by the worker while it runs a turn: a stop request has to reach the turn,
    // not the process, or cancelling one session would stop every session on the project.
    get("/agent/internal/turn/{turnId}/cancelled") {
        val instance = call.requireInstanceHeader() ?: return@get
        if (instance.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing instance header"))
            return@get
        }
        val turnId = call.parameters["turnId"].orEmpty()
        if (turnId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing turn id"))
            return@get
        }
        call.respond(TurnCancelState(broker.isCancelled(turnId)))
    }

    // The worker is the only process that can read a project it holds open, and it has
    // no database pool of its own, so it hands the tree over and the server stores it.
    post("/agent/internal/project/index") {
        val instance = call.requireInstanceHeader() ?: return@post
        if (instance.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing instance header"))
            return@post
        }
        val req = try {
            call.receive<ProjectIndexRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload: ${e.message}"))
            return@post
        }
        val root = java.nio.file.Path.of("ghidra_projects").toAbsolutePath().normalize()
        val stored = withContext(Dispatchers.IO) {
            org.iotsplab.akiba.managers.ProjectIndex.replace(
                req.project,
                root,
                req.entries.map { e ->
                    org.iotsplab.akiba.managers.ProjectPrograms.Entry(
                        path = e.path, name = e.name, folder = e.folder,
                        size = e.size, modified = e.modified,
                    )
                },
            )
        }
        call.respond(mapOf("indexed" to stored, "entries" to req.entries.size, "project" to req.project))
    }

    post("/agent/internal/turn/finished") {
        val instance = call.requireInstanceHeader() ?: return@post
        if (instance.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing instance header"))
            return@post
        }
        val req = try {
            call.receive<TurnFinishedRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload: ${e.message}"))
            return@post
        }
        broker.finish(req.turnId, TurnBroker.Result(req.status, req.error))
        call.respond(mapOf("status" to "ok"))
    }
}
