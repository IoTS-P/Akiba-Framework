package org.iotsplab.akiba.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.agent.mailbox.AgentMailboxService
import org.iotsplab.akiba.llm.agent.module.tool
import org.iotsplab.akiba.llm.agent.runtime.AgentRuntime
import org.iotsplab.akiba.llm.agent.stream.StreamChunkBus

/** The stream routes. Registered by [agentRoutes]. */
internal fun Route.agentStreamRoutes(daemonHost: String, daemonPort: Int) {

    // ------ Internal: force-close all live LLM clients -----------------------
    //
    // Each AkibaAgent's LLM client keeps a `java.net.http.HttpClient`
    // connection pool alive for **1200 seconds**; when sessions accumulate,
    // the provider's per-IP connection budget is exhausted and it deliberately
    // slows responses, which the frontend sees as a 20-30 minute "hang".
    // `LLMClientFactory`'s shutdown hook only closes them on graceful
    // shutdown, so this endpoint lets an operator release the slots before a
    // rolling restart.
    //
    // It is *internal* (`/agent/internal/`) but still bound to the public
    // listener, so it requires the same instance header as every agent route.
    post("/agent/internal/llm/close-all") {
        val instance = call.requireInstanceHeader() ?: return@post
        if (instance.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing instance header"))
            return@post
        }
        val liveBefore = org.iotsplab.akiba.llm.client.LLMClientFactory.liveClientCount()
        val closed = org.iotsplab.akiba.llm.client.LLMClientFactory.closeAllLiveClients(
            reason = "manual close-all via /agent/internal/llm/close-all (instance=$instance)"
        )
        call.respond(mapOf(
            "status" to "ok",
            "liveClientsBefore" to liveBefore,
            "closed" to closed,
        ))
    }

    // ------ Internal: receive a live LLM streaming chunk from a worker ----
    //
    // Callers (manual-agent worker JVMs and the in-process runtime) POST every
    // chunk, which is kept in [StreamChunkBus]'s per-session history and
    // drained by `GET /agent/sessions/{id}/stream-chunks` for a per-token live
    // view.  No DB write happens here — the worker persists the final message.
    // The endpoint is on the public listener but requires the instance header,
    // and chunks are small so the bus is not easily flooded.
    post("/agent/internal/stream-chunk") {
        val instance = call.requireInstanceHeader() ?: return@post
        if (instance.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing instance header"))
            return@post
        }
        val req = try {
            call.receive<StreamChunkRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid chunk payload: ${e.message}"))
            return@post
        }
        if (req.sessionId.isBlank() && req.chunks.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "sessionId is required"))
            return@post
        }
        // A batch keeps a whole burst to one round-trip; the flat fields stay
        // supported for single-chunk callers (curl probes, older workers).
        val batch = if (req.chunks.isEmpty()) {
            listOf(
                StreamChunkPayload(
                    delta = req.delta,
                    chunkCount = req.chunkCount,
                    byteCount = req.byteCount,
                    done = req.done,
                    error = req.error,
                    newGeneration = req.newGeneration,
                    kind = req.kind,
                )
            )
        } else {
            req.chunks
        }
        for (payload in batch) {
            org.iotsplab.akiba.llm.agent.stream.StreamChunkBus.publish(
                org.iotsplab.akiba.llm.agent.stream.StreamChunkBus.Chunk(
                    sessionId = payload.sessionId.takeIf { it.isNotBlank() } ?: req.sessionId,
                    delta = payload.delta,
                    chunkCount = payload.chunkCount,
                    byteCount = payload.byteCount,
                    done = payload.done,
                    error = payload.error,
                    newGeneration = payload.newGeneration,
                    kind = payload.kind,
                )
            )
        }
        call.respond(mapOf("status" to "ok", "accepted" to batch.size))
    }

    // A resident worker asks for its next turn here and reports the outcome after.
    workerTurnRoutes()

    // ------ Incremental poll: live LLM streaming chunks for a session ------
    //
    // The frontend polls this endpoint (~1 s cadence) while a session
    // is selected and renders the returned deltas with a typewriter
    // animation, giving true per-token "natural growth" over a plain
    // JSON GET — a transport that survives every proxy (an earlier
    // SSE transport died at the outer reverse proxy, which kills
    // `text/event-stream` connections after the first flush).
    //
    // Request:  GET /agent/sessions/{id}/stream-chunks?since=SEQ
    // Response: { active, generation, latestSeq, latestCount, done, error,
    //             chunks: [{ seq, delta, chunkCount, byteCount, done, error }] }
    //
    // `since` is the last `seq` the client applied.  The bus assigns `seq`
    // itself and never restarts it at a generation boundary, so resuming from
    // the client's own cursor can never filter out a new generation's first
    // chunks.  `generation` still bumps when a new LLM generation starts — the
    // client discards accumulated text then, but keeps its cursor.
    get("/agent/sessions/{id}/stream-chunks") {
        val instance = call.request.header("X-Akiba-Instance")
            ?.takeIf { it.isNotBlank() }
            ?: call.request.queryParameters["instance"]?.takeIf { it.isNotBlank() }
        if (instance == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing instance"))
            return@get
        }
        val sessionId = call.parameters["id"].orEmpty()
        if (sessionId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session id"))
            return@get
        }
        val since = call.request.queryParameters["since"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val snap = org.iotsplab.akiba.llm.agent.stream.StreamChunkBus.historySince(sessionId, since)
        if (snap == null) {
            call.respond(mapOf(
                "active" to false,
                "generation" to 0,
                "latestSeq" to 0L,
                "latestCount" to 0,
                "done" to false,
                "chunks" to emptyList<Any>(),
            ))
            return@get
        }
        call.respond(mapOf(
            "active" to true,
            "generation" to snap.generation,
            "latestSeq" to snap.latestSeq,
            "latestCount" to snap.latestCount,
            "done" to snap.done,
            "error" to snap.error,
            "chunks" to snap.chunks.map { c ->
                mapOf(
                    "seq" to c.seq,
                    "delta" to c.delta,
                    "kind" to c.kind,
                    "chunkCount" to c.chunkCount,
                    "byteCount" to c.byteCount,
                    "done" to c.done,
                    "error" to c.error,
                )
            },
        ))
    }

    // ------ Internal: report how many LLM clients are currently open -------
    get("/agent/internal/llm/live-count") {
        val instance = call.requireInstanceHeader() ?: return@get
        if (instance.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing instance header"))
            return@get
        }
        call.respond(mapOf(
            "liveClients" to org.iotsplab.akiba.llm.client.LLMClientFactory.liveClientCount()
        ))
    }

    // ------ Stop the tool a session is running -------------------------------
    //
    // A tool call can be long on purpose (a build, an emulator, a sweep). The user may want
    // it to stop without ending the whole turn, so this asks the running call to stop and
    // the agent gets an observation saying the work was cut short.
    post("/agent/sessions/{id}/inject") {
        val instance = call.requireInstanceHeader() ?: return@post
        val id = call.parameters["id"].orEmpty()
        if (id.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session id"))
            return@post
        }
        val req = try {
            call.receive<InjectRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
            return@post
        }
        if (req.message.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Message content is empty"))
            return@post
        }
        val transient = req.transient

        try {
            val sessionInfo = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                AgentDatabaseClient(dbClient).getSession(id)
            }
            val binaryId = sessionInfo.binaryId
                ?: return@post call.respond(
                    HttpStatusCode.Conflict,
                    mapOf("error" to "Session has no binaryId; cannot locate runtime")
                )

            // Send the mailbox message.
            withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                val mailboxService = org.iotsplab.akiba.llm.agent.mailbox.AgentMailboxService(agentDbClient)
                mailboxService.send(
                    senderSessionId = "system",
                    recipientSessionId = id,
                    kind = "user-hint",
                    subject = if (transient) "[transient]" else "[persistent]",
                    body = req.message,
                    priority = 10,
                )
            }

            // Check the session's runtime state.  If it's CLOSED or
            // ERROR, try to resume it via the runtime so the user's
            // hint is actually processed.  If the runtime doesn't
            // have the factory (cross-process), the hint stays in
            // the mailbox and will be picked up the next time the
            // owning process polls.
            val runtimeState = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                AgentDatabaseClient(dbClient).getRuntimeState(id)?.runtimeState
            }
            val stateLower = runtimeState?.lowercase()
            val resumed = if (stateLower == "closed" || stateLower == "error") {
                try {
                    withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                        val agentDbClient = AgentDatabaseClient(dbClient)
                        val runtime = org.iotsplab.akiba.llm.agent.runtime.AgentRuntime.forBinary(binaryId, agentDbClient)
                        if (runtime.canResume(id)) {
                            runtime.resumeForUserInjection(id) != null
                        } else {
                            false
                        }
                    }
                } catch (e: Exception) {
                    agentRouteLogger.warn("[inject] resumeForUserInjection failed for $id: ${e.message}", e)
                    false
                }
            } else {
                false
            }

            call.respond(mapOf(
                "status" to "sent",
                "sessionId" to id,
                "transient" to transient,
                "runtimeState" to (stateLower ?: "unknown"),
                "resumed" to resumed,
                "hint" to if (resumed) {
                    "Message delivered and session resumed from ${stateLower ?: "terminal"} state."
                } else if (stateLower == "running" || stateLower == "standby" || stateLower == "msghandle") {
                    "Message delivered. The agent will pick it up on its next iteration."
                } else {
                    "Message delivered to mailbox. If the session is in another process, it will be picked up when that process next polls."
                }
            ))
        } catch (e: Exception) {
            agentRouteLogger.error("[inject] failed for session $id: ${e.message}", e)
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    // ------ Pause / Resume a session -----------------------------------------
    //
    // Pausing halts the LLM loop: the agent finishes the current iteration,
    // then blocks before the next LLM call, while messages from other agents
    // keep accumulating in the mailbox.  Resume un-blocks the loop.

}
