package org.iotsplab.akiba.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.agent.module.tool
import org.iotsplab.akiba.llm.tool.core.ConfirmationManager
import org.iotsplab.akiba.llm.tool.core.ToolInterrupts
import org.iotsplab.akiba.llm.tool.core.ToolRunner
import org.iotsplab.akiba.llm.tool.ui.UserChoiceEvent
import org.iotsplab.akiba.llm.tool.ui.UserChoiceManager
import org.iotsplab.akiba.managers.ProjectPrograms

/** The human routes. Registered by [agentRoutes]. */
internal fun Route.agentHumanRoutes(daemonHost: String, daemonPort: Int) {
    post("/agent/sessions/{sessionId}/tool/interrupt") {
        val sessionId = call.parameters["sessionId"] ?: return@post call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "sessionId is required")
        )
        // In the server the call is local; in a worker the press is parked for it to collect.
        val local = ToolRunner.interrupt(sessionId)
        ToolInterrupts.request(sessionId)
        call.respond(mapOf("interrupted" to true, "runningHere" to local))
    }

    // ------ Internal: was this session's tool asked to stop? (worker → server) ---
    get("/agent/internal/tool/interrupt") {
        val sessionId = call.request.queryParameters["sessionId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "sessionId is required"))
        call.respond(mapOf("interrupted" to ToolInterrupts.consume(sessionId)))
    }

    // ------ Internal: tool confirmation request (cross-process long-poll) ---
    //
    // Called by worker processes when a tool (e.g. run_shell) requires
    // user confirmation. The worker makes an HTTP POST to this endpoint
    // and blocks until the user responds (or the 5-minute timeout
    // expires). The server registers the pending request in
    // [ConfirmationManager] (visible to the frontend via
    // `GET /agent/sessions/{id}`), then suspends until
    // `POST /agent/sessions/{id}/confirmation/respond` arrives.
    post("/agent/internal/confirmation/request") {
        val req = call.receive<InternalConfirmationRequest>()
        val approved = ConfirmationManager.requestConfirmation(
            sessionId = req.sessionId,
            toolName = req.toolName,
            command = req.command,
            workingDirectory = req.workingDirectory,
            timeout = req.timeout,
            action = req.action,
            targetPath = req.targetPath
        )
        call.respond(mapOf("approved" to approved))
    }

    // ------ Internal: user-choice request (cross-process long-poll) ---------
    //
    // Called by worker processes when `ask_user_choice` needs the user to
    // pick an option. Same long-poll pattern as the confirmation endpoint:
    // the worker blocks on the HTTP response until the user answers, the
    // inactivity timeout expires, or a BTW question arrives.
    post("/agent/internal/user-choice/request") {
        val req = call.receive<InternalUserChoiceRequest>()
        val entry = UserChoiceManager.register(
            sessionId = req.sessionId,
            question = req.question,
            options = req.options,
            allowCustomInput = req.allowCustomInput
        )
        if (entry == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session id"))
            return@post
        }
        // Worker re-entering the long-poll after answering a BTW question
        // locally — deliver that answer first, then keep waiting.
        if (req.btwId != null && req.btwAnswer != null) {
            UserChoiceManager.completeBtw(req.sessionId, req.btwId, req.btwAnswer)
        }
        when (val ev = UserChoiceManager.awaitEvent(entry)) {
            is org.iotsplab.akiba.llm.tool.ui.UserChoiceEvent.Resolved ->
                call.respond(mapOf(
                    "status" to if (ev.answer != null) "answered" else "cancelled",
                    "choice" to ev.answer
                ))
            org.iotsplab.akiba.llm.tool.ui.UserChoiceEvent.TimedOut ->
                call.respond(mapOf("status" to "timeout", "choice" to null))
            is org.iotsplab.akiba.llm.tool.ui.UserChoiceEvent.Btw ->
                call.respond(mapOf(
                    "status" to "btw",
                    "btwId" to ev.exchange.btwId,
                    "btwQuestion" to ev.exchange.question
                ))
        }
    }

    // ------ List sessions -----------------------------------------------------
    get("/agent/pending-confirmations") {
        val instance = call.requireInstanceHeader() ?: return@get
        if (instance.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing instance header"))
            return@get
        }
        val all = ConfirmationManager.getAllPending()
        call.respond(mapOf(
            "pending" to all.mapValues { (_, pc) ->
                PendingConfirmationDto(
                    requestId = pc.requestId,
                    toolName = pc.toolName,
                    command = pc.command,
                    workingDirectory = pc.workingDirectory,
                    timeout = pc.timeout,
                    action = pc.action,
                    targetPath = pc.targetPath,
                    createdAt = pc.createdAt
                )
            }
        ))
    }

    // ------ List ALL pending user choices (cross-session) -------------------
    //
    // Same rationale as `/agent/pending-confirmations`: a sub-agent may be
    // the one blocked on `ask_user_choice` while the user is viewing the
    // root session — surface every pending choice JVM-wide so the frontend
    // can show the modal regardless of the currently open session.
    get("/agent/pending-user-choices") {
        val instance = call.requireInstanceHeader() ?: return@get
        if (instance.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing instance header"))
            return@get
        }
        val all = UserChoiceManager.getAllPending()
        call.respond(mapOf(
            "pending" to all.mapValues { (_, snap) -> snap.toDto() }
        ))
    }

    // ------ Create session ----------------------------------------------------
    post("/agent/sessions/{id}/confirmation/respond") {
        val id = call.parameters["id"].orEmpty()
        if (id.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session id"))
            return@post
        }
        val req = runCatching { call.receive<ConfirmationRespondRequest>() }
            .getOrNull()
        val approved = req?.approved ?: false

        val delivered = ConfirmationManager.respond(id, approved)
        call.respond(mapOf(
            "status" to if (delivered) "delivered" else "no_pending",
            "sessionId" to id,
            "approved" to approved,
            "message" to if (delivered) {
                if (approved) "Approval delivered to the agent." else "Denial delivered to the agent."
            } else "No pending confirmation for this session."
        ))
    }

    // ------ User choice response (human-in-the-loop) ------------------------
    //
    // The frontend discovers a pending `ask_user_choice` request via
    // `pendingUserChoice` on `GET /agent/sessions/{id}` (or the global
    // `/agent/pending-user-choices` poll) and delivers the answer here.
    post("/agent/sessions/{id}/user-choice/respond") {
        val id = call.parameters["id"].orEmpty()
        if (id.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session id"))
            return@post
        }
        val req = runCatching { call.receive<UserChoiceRespondRequest>() }
            .getOrNull()
        val answer: String? = when {
            req == null || req.cancelled -> null
            else -> req.choice?.trim()?.takeIf { it.isNotEmpty() }
        }

        val delivered = UserChoiceManager.respond(id, answer)
        call.respond(mapOf(
            "status" to if (delivered) "delivered" else "no_pending",
            "sessionId" to id,
            "answered" to (answer != null),
            "message" to if (delivered) {
                if (answer != null) "Answer delivered to the agent."
                else "Cancellation delivered to the agent."
            } else "No pending user-choice request for this session."
        ))
    }

    // ------ User choice follow-up ("BTW") question ---------------------------
    //
    // While an `ask_user_choice` request is pending, the user may ask
    // follow-up questions inside the choice modal. The question is handed
    // to the blocked consumer (in-process tool thread, or worker via the
    // long-poll), which answers it via BtwAssistant (read-only). Suspends
    // until the answer is ready (up to BTW_AWAIT_TIMEOUT_MS); submitting
    // refreshes the choice request's inactivity timeout.
    post("/agent/sessions/{id}/user-choice/btw") {
        val id = call.parameters["id"].orEmpty()
        if (id.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session id"))
            return@post
        }
        val req = runCatching { call.receive<UserChoiceBtwRequest>() }.getOrNull()
        val question = req?.question?.trim()
        if (question.isNullOrEmpty()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing question"))
            return@post
        }
        if (question.length > 4000) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Question too long (max 4000 chars)"))
            return@post
        }
        val answer = UserChoiceManager.submitBtw(id, question)
        if (answer == null) {
            call.respond(HttpStatusCode.Conflict, mapOf(
                "answered" to false,
                "error" to "No pending user-choice request for this session."
            ))
        } else {
            call.respond(mapOf("answered" to true, "answer" to answer))
        }
    }

    // ------ Delete session ----------------------------------------------------
    /**
     * Rename a conversation or point it at a different program.
     *
     * Both live on the session row so the choice survives a restart and is the
     * one the next turn starts from.
     */
    patch("/agent/sessions/{id}") {
        if (!call.requireAuthenticated()) return@patch
        val instance = call.requireInstanceHeader() ?: return@patch
        val id = call.parameters["id"].orEmpty()
        if (id.isBlank()) {
            return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session id"))
        }
        val req = call.receive<SessionPatchRequest>()
        if (req.sessionName == null && req.programName == null) {
            return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Nothing to update"))
        }
        val name = req.sessionName?.trim()?.takeIf { it.isNotEmpty() }
        val targetProgram = req.programName?.trim()?.takeIf { it.isNotEmpty() }
        try {
            // Switching program also rebinds the identity, otherwise the next
            // turn would resolve the old id and open the wrong program.
            val targetProgramId = if (targetProgram != null) {
                val project = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                    AgentDatabaseClient(dbClient).getSession(id).projectName
                }
                if (project != null) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ProjectPrograms.ensureProgramIdentity(
                                project,
                                call.currentUserGhidraProjectsRoot(),
                                targetProgram,
                            )
                        }.getOrNull()
                    }
                } else null
            } else null
            withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                AgentDatabaseClient(dbClient).updateSession(
                    id,
                    sessionName = name,
                    programName = targetProgram,
                    programId = targetProgramId,
                )
            }
            val info = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                AgentDatabaseClient(dbClient).getSession(id)
            }
            call.respond(info.toResponse())
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

}
