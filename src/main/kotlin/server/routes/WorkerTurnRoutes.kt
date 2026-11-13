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
)

/** The worker's poll result; `turn` is null when the wait expired with nothing to do. */
@Serializable
data class TurnNextResponse(val turn: TurnDto? = null)

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
                turn?.let { TurnDto(it.id, it.sessionId, it.programId, it.prompt) },
            ),
        )
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
