package org.iotsplab.akiba.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.agent.module.tool
import org.iotsplab.akiba.llm.agent.runtime.RuntimeState
import org.iotsplab.akiba.llm.agent.stream.StreamChunkBus
import org.iotsplab.akiba.llm.config.LLMKeyFileStore
import org.iotsplab.akiba.llm.tool.core.ConfirmationManager
import org.iotsplab.akiba.llm.tool.core.Tool
import org.iotsplab.akiba.llm.tool.ui.UserChoiceManager
import org.iotsplab.akiba.managers.ProjectIndex
import org.iotsplab.akiba.managers.ProjectPrograms
import org.iotsplab.akiba.managers.ProjectWorkers

/** The chat routes. Registered by [agentRoutes]. */
internal fun Route.agentChatRoutes(daemonHost: String, daemonPort: Int) {
    post("/agent/sessions/{id}/chat") {
        val instance = call.requireInstanceHeader() ?: return@post
        val id = call.parameters["id"].orEmpty()
        if (id.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session id"))
            return@post
        }
        val req = call.receive<ChatRequest>()
        if (req.content.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Message content is empty"))
            return@post
        }

        val systemPrompt = withCommonAgentRules(
            req.systemPrompt?.takeIf { it.isNotBlank() } ?: DEFAULT_AGENT_SYSTEM_PROMPT
        )
        // The worker opens the project from its config, so it has to be told the same
        // root the server uses. currentUserProjectDirectory() is empty here, which made
        // the worker create an empty project in the server's working directory.
        val projectDirectory = call.currentUserGhidraProjectsRoot()
        // Resolved here, not inside the finally block: the index is keyed by
        // this root and the call object must not be touched once responding.
        val indexRoot = call.currentUserGhidraProjectsRoot()
        val serverPort = call.request.local.serverPort

        // Validate a requested model switch against the configured LLM
        // keys (whitelist) BEFORE touching any session state — an
        // unknown model is a client error, not a session failure.
        val requestedModel = req.modelName?.trim()?.takeIf { it.isNotEmpty() }
        if (requestedModel != null) {
            val known = LLMKeyFileStore.load().any { entry ->
                entry.modelNames.any { it.equals(requestedModel, ignoreCase = true) }
            }
            if (!known) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "error" to "No LLM key configured for model '$requestedModel'"
                ))
                return@post
            }
        }

        try {
            val sessionInfo = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                var info = agentDbClient.getSession(id)
                // Manual model switch: the chat-page picker follows the
                // session's stored model; when the user deliberately
                // picks a different one, persist it BEFORE launching the
                // worker so this and all later turns use the new model.
                if (requestedModel != null && !requestedModel.equals(info.modelName, ignoreCase = true)) {
                    agentDbClient.updateSession(id, modelName = requestedModel)
                    info = info.copy(modelName = requestedModel)
                }
                agentDbClient.updateSession(id, status = "running")
                agentDbClient.setRuntimeState(
                    id,
                    org.iotsplab.akiba.llm.agent.runtime.RuntimeState.RUNNING.wire(),
                    "manual_chat_start"
                )
                info
            }

            // For project-based sessions (no binaryId), locate the
            // program in the project so the manual agent worker can
            // open it.  FAIL FAST when the project has no program at
            // all: the worker cannot run any analysis tool without one.
            var programName: String? = null
            if (sessionInfo.binaryId == null && sessionInfo.projectName != null) {
                val project = sessionInfo.projectName
                val root = call.currentUserGhidraProjectsRoot()
                val boundId = sessionInfo.currentProgramId
                val bound = sessionInfo.currentProgram?.takeIf { it.isNotBlank() }
                if (boundId != null) {
                    // Identity first: the name can be stale after a rename. The index is
                    // written whenever a project is opened — by the worker that holds it —
                    // so a resident worker's project is covered as long as it refreshes.
                    val resolved = ProjectIndex.pathOfProgram(project, root, boundId)
                        ?: bound?.let { name ->
                            ProjectIndex.entries(project, root).firstOrNull { it.name == name }?.path
                        }
                    programName = when {
                        resolved != null -> resolved
                        // No index entry: the name the session already knows is still what
                        // the worker opens by. It reports back if the file is really gone.
                        bound != null -> {
                            agentRouteLogger.info(
                                "program {} ({}) is not in the index for {}; using the name",
                                bound, boundId, project)
                            bound
                        }
                        else -> {
                            markManualChatFailed(daemonHost, daemonPort, instance, id, "program deleted")
                            call.respond(HttpStatusCode.Conflict, mapOf(
                                "error" to "The program this conversation was using no longer exists. " +
                                    "Pick another program in the header and send again."
                            ))
                            return@post
                        }
                    }
                } else if (bound != null) {
                    programName = bound
                } else {
                    // No implicit "first program" any more: a conversation works
                    // on the program the operator picked. An empty project is the
                    // one case where a turn may run without one, because the agent
                    // has to be able to tell the user what to do next.
                    val hasAnyProgram = findFirstProgramName(project, root) != null
                    if (hasAnyProgram) {
                        markManualChatFailed(daemonHost, daemonPort, instance, id, "no program selected")
                        call.respond(HttpStatusCode.BadRequest, mapOf(
                            "error" to "This conversation has no program selected. " +
                                "Pick one in the header (Program selector) and send again."
                        ))
                        return@post
                    }
                }
            }
            // With an empty project the turn still runs, but the agent must know
            // there is nothing to inspect instead of reporting empty results.
            val noProgramNotice = if (programName == null &&
                sessionInfo.binaryId == null && sessionInfo.projectName != null
            ) {
                "\n\nNo program is loaded in this project, so no binary can be inspected. " +
                    "Tell the user to import a binary into the project (Projects page → Details → Import) " +
                    "and to select it in the header before asking for analysis."
            } else ""
            // Make sure the program has an identity before the worker takes the
            // project: this is the only moment a conversation can create one,
            // and the UI needs it while the turn is still running.
            programName?.let { program ->
                val programId = withContext(Dispatchers.IO) {
                    runCatching {
                        ProjectPrograms.ensureProgramIdentity(sessionInfo.projectName!!, indexRoot, program)
                    }.getOrNull()
                }
                if (programId != null) {
                    // Bind the session to what this turn actually opens; the
                    // daemon accumulates it into program_ids.
                    runCatching {
                        withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                            AgentDatabaseClient(dbClient).updateSession(
                                id,
                                programName = program,
                                programId = programId,
                            )
                        }
                    }
                }
            }

            val workerConfig = createManualAgentConfig(
                sessionInfo = sessionInfo,
                instanceName = instance,
                daemonHost = daemonHost,
                daemonPort = daemonPort,
                projectDirectory = projectDirectory
            )

            var workerError: String? = null
            try {
                // A session without a project still gets a worker of its own; the key only
                // has to be unique, and nothing else shares that project.
                val lockedProject = sessionInfo.projectName?.takeIf { it.isNotBlank() }
                    ?: "session:$id"
                // The worker runs for the whole turn and this call waits for it.
                // Doing that on the caller's Netty event loop pins the browser's
                // keep-alive connection: every other request on it — the message
                // and chunk polls included — queues behind the turn, so the UI
                // freezes until the answer is finished.
                withContext(Dispatchers.IO) {
                    if (ProjectWorkers.poolingEnabled) {
                        // One worker per project, shared by every session on it; the turn
                        // carries the message, so the worker never calls back for details.
                        ResidentWorkers.ensure(lockedProject, workerConfig, serverPort, instance)
                        runPooledTurn(
                            project = lockedProject,
                            sessionId = id,
                            programId = sessionInfo.currentProgramId,
                            prompt = req.content,
                            systemPrompt = systemPrompt + noProgramNotice,
                            username = call.currentUsernameOrDefault(),
                            programName = programName,
                            llm = workerConfig.llm,
                        )
                    } else {
                        // Without the switch there is no worker to talk to; say so instead
                        // of silently leaving the browser waiting.
                        throw ManualAgentWorkerException(
                            "Project workers are disabled on this server (AKIBA_PROJECT_WORKERS).")
                    }
                }
            } catch (e: Exception) {
                workerError = e.message ?: e.javaClass.simpleName
                throw e
            } finally {
                // The worker had the project open; with it gone the index can be
                // rebuilt, which is what keeps the browser's tree current.
                sessionInfo.projectName?.takeIf { it.isNotBlank() }?.let { name ->
                    runCatching {
                        withContext(Dispatchers.IO) {
                            // The index is keyed by the Ghidra projects root, not by
                            // the session workspace directory.
                            ProjectIndex.refresh(name, indexRoot)
                        }
                    }
                }
                runCatching { Files.deleteIfExists(workerConfig.file) }
                // The worker JVM exits with its terminal chunk possibly still
                // in flight (a hung POST, a kill).  The turn is over either
                // way, so resolve the stream here: a poller left on `done`
                // false spun on "waiting for model…" forever.
                org.iotsplab.akiba.llm.agent.stream.StreamChunkBus.finish(id, workerError)
            }

            withDaemonSession(daemonHost, daemonPort, instance, serialize = false) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                val current = agentDbClient.getSession(id)
                if (current.status == "running") {
                    agentDbClient.updateSession(id, status = "closed")
                    runCatching {
                        agentDbClient.setRuntimeState(
                            id,
                            org.iotsplab.akiba.llm.agent.runtime.RuntimeState.CLOSED.wire(),
                            "manual_chat_done",
                        )
                    }
                }
            }

            val msgs = withDaemonSession(daemonHost, daemonPort, instance, serialize = false) { dbClient ->
                AgentDatabaseClient(dbClient).getMessages(id, 0, 1000)
                    .filter { it.role != "system" }
            }
            val userMsg = msgs.lastOrNull { it.role == "user" }
            val asstMsg = msgs.lastOrNull { it.role == "assistant" }
            if (asstMsg == null || userMsg == null) {
                // The worker ran but recorded nothing, or died before it could.
                // These used to throw IllegalStateException, which the catch
                // below reported as "Agent is not configured" — a wrong reason
                // for what is almost always a model failure or a killed worker.
                val reason = workerError?.let { "The agent worker stopped before replying ($it)." }
                    ?: "The agent produced no reply."
                markManualChatFailed(daemonHost, daemonPort, instance, id, reason)
                return@post call.respond(
                    HttpStatusCode.BadGateway,
                    mapOf("error" to reason)
                )
            }

            call.respond(ChatResponse(
                sessionId = id,
                userMessage = userMsg.toMessageResponse(),
                assistantMessage = asstMsg.toMessageResponse(),
                iterations = 1,
                tokenUsage = mapOf(
                    "input" to (asstMsg.inputTokenCount ?: 0),
                    "output" to (asstMsg.tokenCount ?: 0)
                )
            ))
        } catch (e: Exception) {
            when {
                // The user cancelled this turn via /cancel-chat.  That
                // endpoint already parked the session in
                // CLOSED/user_cancelled_chat and terminated the stream
                // generation — do NOT overwrite the state with a
                // failure, and do NOT dump the killed worker's log
                // (exit code + full stdout) into the HTTP response.
                // Respond 200 so the frontend ends the turn calmly.
                ChatCancellations.consume(id) -> {
                    // The agent may have written ERROR on its way out; a stop is not a
                    // failure, so park the session where the user left it.
                    runCatching {
                        withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                            val agentDbClient = AgentDatabaseClient(dbClient)
                            agentDbClient.updateSession(id, status = "closed")
                            agentDbClient.setRuntimeState(
                                id,
                                org.iotsplab.akiba.llm.agent.runtime.RuntimeState.CLOSED.wire(),
                                "user_cancelled_chat"
                            )
                        }
                    }
                    call.respond(mapOf("status" to "cancelled", "sessionId" to id))
                }
                e is IllegalStateException -> {
                    markManualChatFailed(daemonHost, daemonPort, instance, id, e.message)
                    call.respond(HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "Agent is not configured: ${e.message}"))
                }
                else -> {
                    markManualChatFailed(daemonHost, daemonPort, instance, id, e.message)
                    val (status, body) = errorPayload(e)
                    call.respond(status, body)
                }
            }
        }
    }

    // ------ Inject a user hint into a running/standby/closed/error session --
    //
    // Unlike /chat (a synchronous manual turn), /inject posts an asynchronous
    // mailbox message: RUNNING sessions pick it up in applyMailboxDrain at the
    // next beforeIteration, STANDBY ones are woken by AgentMailboxDispatcher,
    // and CLOSED/ERROR ones make the runtime restart the session.
    post("/agent/sessions/{id}/cancel-chat") {
        val id = call.parameters["id"].orEmpty()
        if (id.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session id"))
            return@post
        }
        val cancelled = ProjectWorkers.broker.cancel(id)
        if (cancelled) {
            ChatCancellations.mark(id)
            // The worker ends the turn quietly, so the stream needs its terminal
            // marker here or the bubble waits on "waiting for model…" forever.
            org.iotsplab.akiba.llm.agent.stream.StreamChunkBus.finish(id, USER_CANCELLED_STREAM_ERROR)
        }
        // Release any tool threads blocked on human-in-the-loop prompts
        // (confirmation / user choice) for this session.
        ConfirmationManager.clear(id)
        UserChoiceManager.clear(id)
        if (cancelled) {
            // Mark the session as closed so the frontend can recover.
            val instance = call.instanceHeader()
            // The instance must belong to the caller: the server authenticates
            // to the daemon as the instance owner.
            if (instance != null && !call.requireInstanceOwnership(instance))
                return@post
            if (instance != null) {
                runCatching {
                    withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                        val agentDbClient = AgentDatabaseClient(dbClient)
                        agentDbClient.updateSession(id, status = "closed")
                        runCatching {
                            agentDbClient.setRuntimeState(
                                id,
                                org.iotsplab.akiba.llm.agent.runtime.RuntimeState.CLOSED.wire(),
                                "user_cancelled_chat"
                            )
                        }
                    }
                }
            }
            call.respond(mapOf("status" to "cancelled", "sessionId" to id))
        } else {
            // Nothing is running: either the turn already ended, or the server
            // restarted under it and left the row "running". Either way the
            // session must not stay un-abortable, so close it here.
            val instance = call.instanceHeader()
            if (instance != null && !call.requireInstanceOwnership(instance))
                return@post
            var recovered = false
            if (instance != null) {
                runCatching {
                    withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                        val agentDbClient = AgentDatabaseClient(dbClient)
                        val session = agentDbClient.getSession(id)
                        if (session != null &&
                            (session.status == "running" || session.runtimeState == "running")
                        ) {
                            agentDbClient.updateSession(id, status = "closed")
                            agentDbClient.setRuntimeState(
                                id,
                                org.iotsplab.akiba.llm.agent.runtime.RuntimeState.CLOSED.wire(),
                                "orphaned_chat_closed_by_user"
                            )
                            recovered = true
                        }
                    }
                }
            }
            call.respond(mapOf(
                "status" to if (recovered) "recovered" else "idle",
                "sessionId" to id,
            ))
        }
    }

    // ------ Tool confirmation (human-in-the-loop) ---------------------------
    //
    // When a tool (e.g. run_shell) requires user approval, the tool
    // registers a pending confirmation in ConfirmationManager and blocks
    // its thread. The frontend discovers it via the `pendingConfirmation`
    // field on `GET /agent/sessions/{id}`. The user responds by POSTing
    // to this endpoint, which completes the deferred and unblocks the tool.
}
