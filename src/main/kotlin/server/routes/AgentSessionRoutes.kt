package org.iotsplab.akiba.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.nio.file.Path
import java.util.UUID
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.data.database.DatabaseClient
import org.iotsplab.akiba.llm.agent.context.ModelContextWindows
import org.iotsplab.akiba.llm.agent.core.LLM_PROGRESS_PREFIX
import org.iotsplab.akiba.llm.agent.core.LLM_RETRY_STATUS_PREFIX
import org.iotsplab.akiba.llm.agent.core.SYSTEM_SESSION_UUID
import org.iotsplab.akiba.llm.agent.mailbox.AgentMailboxService
import org.iotsplab.akiba.llm.agent.module.tool
import org.iotsplab.akiba.llm.agent.runtime.AgentRuntime
import org.iotsplab.akiba.llm.agent.runtime.RuntimeState
import org.iotsplab.akiba.llm.config.LLMKeyFileStore
import org.iotsplab.akiba.llm.tool.core.ConfirmationManager
import org.iotsplab.akiba.llm.tool.ui.UserChoiceManager
import org.iotsplab.akiba.managers.ProjectIndex
import org.iotsplab.akiba.managers.ProjectWorkers
import org.iotsplab.akiba.managers.WorkspaceManager

/** The session routes. Registered by [agentRoutes]. */
internal fun Route.agentSessionRoutes(daemonHost: String, daemonPort: Int) {
    get("/agent/sessions") {
        val instance = call.requireInstanceHeader() ?: return@get
        val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
        val offset = call.parameters["offset"]?.toIntOrNull() ?: 0
        val status = call.parameters["status"]?.takeIf { it.isNotBlank() }
        val binaryId = call.parameters["binaryId"]?.toIntOrNull()
        val moduleName = call.parameters["moduleName"]?.takeIf { it.isNotBlank() }
        // Default behaviour: only top-level (non-sub-agent) sessions.
        // Pass `parentSessionId=ALL` to see every session.
        // Pass a UUID to filter to direct children of that parent.
        val parentSessionId = call.parameters["parentSessionId"]?.takeIf { it.isNotBlank() }
        try {
            val sessions = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                agentDbClient.listSessions(
                    status = status,
                    binaryId = binaryId,
                    moduleName = moduleName,
                    limit = limit,
                    offset = offset,
                    parentSessionId = parentSessionId
                )
            }
            call.respond(mapOf("sessions" to sessions
                .filter { it.sessionId != SYSTEM_SESSION_UUID }
                .map { it.toResponse() }))
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    // ------ Search sessions by title / message content -------------------------
    // Returns matching TOP-LEVEL sessions (a sub-agent hit is reported as
    // its root session).  Used by the Agent view's search box.
    get("/agent/sessions/search") {
        val instance = call.requireInstanceHeader() ?: return@get
        val query = call.parameters["q"]?.trim().orEmpty()
        if (query.length < 2) {
            call.respond(HttpStatusCode.BadRequest, mapOf(
                "error" to "Query too short (min 2 characters)"))
            return@get
        }
        val limit = call.parameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
        try {
            val sessions = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                AgentDatabaseClient(dbClient).searchSessions(query, limit)
            }
            call.respond(mapOf("sessions" to sessions
                .filter { it.sessionId != SYSTEM_SESSION_UUID }
                .map { it.toResponse() }))
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    // ------ Get direct children of a session ----------------------------------
    get("/agent/sessions/{id}/children") {
        val instance = call.requireInstanceHeader() ?: return@get
        val id = call.parameters["id"].orEmpty()
        if (id.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session id"))
            return@get
        }
        try {
            val children = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                AgentDatabaseClient(dbClient).getSessionChildren(id)
            }
            call.respond(mapOf("children" to children
                .filter { it.sessionId != SYSTEM_SESSION_UUID }
                .map { it.toResponse() }))
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    // ------ Get a single session by id ---------------------------------------
    //
    // Lightweight endpoint used by the frontend to poll the active
    // session's status without re-downloading the full session list.
    // The poll is what keeps the status pill in the chat header and
    // the status badge in the session list in sync with the DB after
    // the agent transitions to STANDBY (the chat endpoint is the
    // only thing that flips the state, and it can happen long after
    // the original `listSessions` returned).
    get("/agent/sessions/{id}") {
        val instance = call.requireInstanceHeader() ?: return@get
        val id = call.parameters["id"].orEmpty()
        if (id.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session id"))
            return@get
        }
        try {
            val info = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                AgentDatabaseClient(dbClient).getSession(id)
            }
            // Attach any pending human-confirmation request from the
            // in-process ConfirmationManager so the frontend can detect
            // it via the existing status poll without a separate endpoint.
            val pendingConf = ConfirmationManager.getPending(id)?.let { pc ->
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
            val pendingChoice = UserChoiceManager.getPending(id)?.toDto()
            call.respond(info.toResponse(pendingConf, pendingChoice))
        } catch (e: DatabaseClient.DatabaseDaemonException) {
            // 404 from the daemon (session not found) — surface as 404
            if (e.statusCode == HttpStatusCode.NotFound) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
            } else {
                val (status, body) = errorPayload(e)
                call.respond(status, body)
            }
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    // ------ List ALL pending confirmations (cross-session) -------------------
    //
    // Returns every pending confirmation in the JVM, keyed by sessionId.
    // The per-session poll only covers the session the user is viewing;
    // this global view lets the frontend surface a modal even when a
    // sub-agent is the one blocked on confirmation.
    post("/agent/sessions") {
        val instance = call.requireInstanceHeader() ?: return@post
        val req = runCatching { call.receive<CreateAgentSessionRequest>() }
            .getOrDefault(CreateAgentSessionRequest())
        val projectDirectory = call.currentUserProjectDirectory()
        // The index is keyed by absolute path and the Projects routes resolve the
        // same root, so both sides must name the project the same way.
        val indexRoot = call.currentUserGhidraProjectsRoot()
        try {
            val info = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)

                // Determine the project name and whether to create a project.
                //
                // Path A: binaryId is provided → legacy flow, import the binary
                //         into a (possibly new) project on the first chat turn.
                // Path B: projectName + programName (no binaryId) → open an
                //         existing project and use that program; no import.
                val resolvedProjectName: String?
                val createNewProject: Boolean

                if (req.binaryId != null) {
                    val name = req.projectName?.takeIf { it.isNotBlank() }
                        ?: "agent-${req.binaryId}-${System.currentTimeMillis()}"
                    resolvedProjectName = name
                    createNewProject = req.projectMode != "existing"
                    WorkspaceManager.openOrCreateInteractiveProject(name, createNewProject, projectDirectory)
                    // Only create/validate here. Holding the project would lock the
                    // worker out of the very project this session is about to use.
                    WorkspaceManager.releaseActiveProject()
                    // Closed again, so its tree can be read and indexed for the browser.
                    ProjectIndex.refresh(name, indexRoot)
                } else if (req.projectName != null && req.projectName.isNotBlank()) {
                    resolvedProjectName = req.projectName
                    createNewProject = req.projectMode == "new"
                    // Open the existing project so the first chat turn can
                    // access the requested program.
                    WorkspaceManager.openOrCreateInteractiveProject(
                        req.projectName, createNewProject, projectDirectory
                    )
                    WorkspaceManager.releaseActiveProject()
                    ProjectIndex.refresh(req.projectName, indexRoot)
                } else {
                    resolvedProjectName = null
                    createNewProject = false
                }

                val sessionId = agentDbClient.createSession(
                    sessionName = req.sessionName ?: "Chat ${System.currentTimeMillis()}",
                    binaryId = req.binaryId,
                    moduleName = "chat",
                    modelName = req.modelName,
                    projectName = resolvedProjectName,
                    parentSessionId = req.parentSessionId
                )
                // Chat sessions use a "one question → one subprocess" model.
                // There is no agent process running between user messages, so
                // the session starts in the "closed" (idle) state.  The
                // /chat endpoint flips it to "running" for the duration of
                // a single manual turn, then back to "closed" when done.
                agentDbClient.updateSession(sessionId, status = "closed")
                runCatching {
                    agentDbClient.setRuntimeState(
                        sessionId,
                        org.iotsplab.akiba.llm.agent.runtime.RuntimeState.CLOSED.wire(),
                        "session_created_idle",
                    )
                }
                agentDbClient.getSession(sessionId)
            }
            call.respond(HttpStatusCode.Created, info.toResponse())
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    // ------ Get session messages ---------------------------------------------
    get("/agent/sessions/{id}/messages") {
        val instance = call.requireInstanceHeader() ?: return@get
        val id = call.parameters["id"].orEmpty()
        if (id.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session id"))
            return@get
        }
        val fromIndex = call.request.queryParameters["fromIndex"]?.toIntOrNull() ?: 0
        val limitParam = call.request.queryParameters["limit"]?.toIntOrNull()

        try {
            val (sessionInfo, msgs) = withDaemonSession(
                daemonHost, daemonPort, instance, serialize = false
            ) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                val info = agentDbClient.getSession(id)
                val messages = if (limitParam != null) {
                    // Preserve explicit pagination callers, but still obey the
                    // daemon-side page cap by asking for at most one page.
                    agentDbClient.getMessages(id, fromIndex, limitParam)
                } else {
                    // Frontend default path: return the full transcript so wake
                    // selectors can segment all wake cycles, not just the first
                    // daemon page (500 rows).
                    fetchAllAgentMessages(agentDbClient, id, fromIndex)
                }
                info to messages
            }
            // Filter out system messages (compaction summaries etc.)
            // BUT keep LLM retry-status notices so the frontend can show
            // "LLM timed out, retry #N in X minutes" during outages,
            // AND keep LLM in-flight progress heartbeats so the frontend
            // can render "agent still working on LLM (Ns elapsed)" during
            // long-form generations.
            val filtered = msgs.filter { m ->
                if (m.role != "system") return@filter true
                val c = m.content ?: ""
                c.startsWith(LLM_RETRY_STATUS_PREFIX) || c.startsWith(LLM_PROGRESS_PREFIX)
            }
            // Compute cumulative token usage from the messages we already
            // fetched (avoid a second round-trip to the daemon).
            // Compaction rebuilds persist kept messages as new rows with
            // their original token counts, so collapse exact duplicate
            // assistant rows before summing — otherwise a session that
            // went through many compactions reports inflated usage.
            var totalInput = 0L
            var totalOutput = 0L
            val seenAssistant = HashSet<String>()
            for (m in filtered) {
                if (m.role != "assistant") continue
                val key = "in:${m.inputTokenCount}:out:${m.tokenCount}:len:${m.content?.length}:${m.content}"
                if (!seenAssistant.add(key)) continue
                totalInput += m.inputTokenCount ?: 0
                totalOutput += m.tokenCount ?: 0
            }
            // Find the last input token count (from the most recent LLM call)
            val lastInputTokens = filtered.lastOrNull { it.role == "assistant" }?.inputTokenCount
            // Configured per-model window wins over the static table.
            val contextLength = sessionInfo.modelName?.let { name ->
                ModelContextWindows.resolve(
                    name,
                    LLMKeyFileStore.configuredContextLength(name)
                ).contextLength
            }
            call.respond(mapOf(
                "messages" to filtered.map { it.toMessageResponse() },
                "totalInputTokens" to totalInput,
                "totalOutputTokens" to totalOutput,
                "lastInputTokens" to lastInputTokens,
                "contextLength" to contextLength
            ))
        } catch (e: DatabaseClient.DatabaseDaemonException) {
            if (e.statusCode == HttpStatusCode.NotFound) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
            } else {
                val (status, body) = errorPayload(e)
                call.respond(status, body)
            }
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    // ------ List conversations (mailbox) for a session -----------------------
    // Returns a JSON array of conversations the session is a
    // participant in, derived from mailbox messages.  Each entry
    // has conversationId, status, messageCount, unhandledCount,
    // lastMessagePreview, participants, etc.  Used by the frontend's
    // right-side conversation panel.
    //
    // The summary derivation lives in
    // [org.iotsplab.akiba.llm.agent.mailbox.AgentMailboxService.listConversations]
    // so the LLM `query_conversations` tool and this HTTP route
    // always return the same data.
    get("/agent/sessions/{id}/conversations") {
        val instance = call.requireInstanceHeader() ?: return@get
        val id = call.parameters["id"].orEmpty()
        if (id.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session id"))
            return@get
        }
        val statusFilter = (call.parameters["status"] ?: "all").lowercase()
        try {
            val summaries = withDaemonSession(
                daemonHost, daemonPort, instance, serialize = false
            ) { dbClient ->
                AgentMailboxService(AgentDatabaseClient(dbClient))
                    .listConversations(id, statusFilter = statusFilter, limit = 50)
            }
            call.respond(mapOf(
                "sessionId" to id,
                "totalConversations" to summaries.size,
                "returned" to summaries.size,
                "conversations" to summaries.map { s ->
                    mapOf(
                        "conversationId" to s.conversationId,
                        "status" to s.status,
                        "participants" to s.participants,
                        "messageCount" to s.messageCount,
                        "unhandledCount" to s.unhandledCount,
                        "lastMessagePreview" to s.lastMessagePreview,
                        "lastMessageKind" to s.lastMessageKind,
                        "lastMessageAt" to s.lastMessageAt,
                        "lastMessageSubject" to s.lastMessageSubject,
                    )
                },
            ))
        } catch (e: DatabaseClient.DatabaseDaemonException) {
            if (e.statusCode == HttpStatusCode.NotFound) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
            } else {
                val (status, body) = errorPayload(e)
                call.respond(status, body)
            }
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    // ------ Get full message history of a conversation -----------------------
    // Returns all mailbox messages belonging to conversation
    // convId, with full body, sender/recipient, kind, priority,
    // timestamps, and ack status.  Used by the frontend when the
    // user expands a conversation in the side panel.
    //
    // The chain-walking / participation filter lives in
    // [org.iotsplab.akiba.llm.agent.mailbox.AgentMailboxService.getConversationMessages]
    // so the LLM `query_conversation` tool and this HTTP route
    // always return the same data.
    get("/agent/sessions/{id}/conversations/{convId}") {
        val instance = call.requireInstanceHeader() ?: return@get
        val id = call.parameters["id"].orEmpty()
        val convIdStr = call.parameters["convId"].orEmpty()
        if (id.isBlank() || convIdStr.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session id or conversation id"))
            return@get
        }
        val convId = convIdStr.toLongOrNull()
            ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "conversationId must be numeric"))
                return@get
            }
        try {
            val messages = withDaemonSession(
                daemonHost, daemonPort, instance, serialize = false
            ) { dbClient ->
                AgentMailboxService(AgentDatabaseClient(dbClient))
                    .getConversationMessages(id, convId)
            }
            call.respond(mapOf(
                "conversationId" to convId,
                "messageCount" to messages.size,
                "messages" to messages.map { m ->
                    mapOf(
                        "messageId" to m.messageId,
                        "senderSessionId" to m.senderSessionId,
                        "recipientSessionId" to m.recipientSessionId,
                        "kind" to m.kind,
                        "subject" to m.subject,
                        "body" to m.body,
                        "priority" to m.priority,
                        "acked" to (m.ackedAt != null),
                        "createdAt" to m.createdAt,
                    )
                },
            ))
        } catch (e: DatabaseClient.DatabaseDaemonException) {
            if (e.statusCode == HttpStatusCode.NotFound) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
            } else {
                val (status, body) = errorPayload(e)
                call.respond(status, body)
            }
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    // ------ Send a chat turn --------------------------------------------------
    post("/agent/sessions/{id}/pause") {
        val instance = call.requireInstanceHeader() ?: return@post
        val id = call.parameters["id"].orEmpty()
        if (id.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session id"))
            return@post
        }
        try {
            val sessionInfo = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                AgentDatabaseClient(dbClient).getSession(id)
            }
            val binaryId = sessionInfo.binaryId
                ?: return@post call.respond(
                    HttpStatusCode.Conflict,
                    mapOf("error" to "Session has no binaryId; cannot locate runtime")
                )

            val paused = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                val runtime = org.iotsplab.akiba.llm.agent.runtime.AgentRuntime.forBinary(binaryId, agentDbClient)
                runtime.pause(id)
            }

            if (paused) {
                call.respond(mapOf("status" to "paused", "sessionId" to id))
            } else {
                call.respond(HttpStatusCode.Conflict, mapOf(
                    "error" to "Session cannot be paused (not running or not in this process)"
                ))
            }
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    post("/agent/sessions/{id}/resume") {
        val instance = call.requireInstanceHeader() ?: return@post
        val id = call.parameters["id"].orEmpty()
        if (id.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session id"))
            return@post
        }
        try {
            val sessionInfo = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                AgentDatabaseClient(dbClient).getSession(id)
            }
            val binaryId = sessionInfo.binaryId
                ?: return@post call.respond(
                    HttpStatusCode.Conflict,
                    mapOf("error" to "Session has no binaryId; cannot locate runtime")
                )

            val resumed = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                val runtime = org.iotsplab.akiba.llm.agent.runtime.AgentRuntime.forBinary(binaryId, agentDbClient)
                runtime.resume(id)
            }

            if (resumed) {
                call.respond(mapOf("status" to "resumed", "sessionId" to id))
            } else {
                call.respond(HttpStatusCode.Conflict, mapOf(
                    "error" to "Session cannot be resumed (not paused or not in this process)"
                ))
            }
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    // ------ Request immediate LLM retry --------------------------------------
    //
    // When the LLM call fails and enters the exponential-backoff retry
    // loop, the user can skip the remaining wait and trigger an
    // immediate retry via this endpoint.

    post("/agent/sessions/{id}/retry-now") {
        val instance = call.requireInstanceHeader() ?: return@post
        val id = call.parameters["id"].orEmpty()
        if (id.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session id"))
            return@post
        }
        try {
            val sessionInfo = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                AgentDatabaseClient(dbClient).getSession(id)
            }
            val binaryId = sessionInfo.binaryId
                ?: return@post call.respond(
                    HttpStatusCode.Conflict,
                    mapOf("error" to "Session has no binaryId; cannot locate runtime")
                )

            val applied = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                val runtime = org.iotsplab.akiba.llm.agent.runtime.AgentRuntime.forBinary(binaryId, agentDbClient)
                runtime.retryNow(id)
            }

            if (applied) {
                call.respond(mapOf("status" to "retry_requested", "sessionId" to id))
            } else {
                call.respond(HttpStatusCode.Conflict, mapOf(
                    "error" to "Session not found in this runtime (it may be running in a different process)"
                ))
            }
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    // ------ Stop a running chat turn ----------------------------------------
    //
    // The stop is aimed at the turn, not at the process: one worker serves every
    // session on a project, so killing it would take the other sessions down too.
    // The worker polls the turn's cancel flag and aborts there.

    delete("/agent/sessions/{id}") {
        val instance = call.requireInstanceHeader() ?: return@delete
        val id = call.parameters["id"].orEmpty()
        if (id.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session id"))
            return@delete
        }
        try {
            withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)

                // Reject deletion of sessions that are still running.
                // The user must wait for the flow to finish (or cancel
                // it first) before deleting — otherwise we'd be yanking
                // the rug out from under a live agent process.
                val session = agentDbClient.getSession(id)
                val status = session.status
                val isRunning = status != "closed" && status != "error" &&
                    status != "completed" && status != "cancelled" && status != "failed"
                if (isRunning) {
                    throw IllegalArgumentException(
                        "Session is still running (status='$status'). " +
                            "Please end the flow first before deleting."
                    )
                }

                // Hard-delete the session and all descendants.
                val deleted = agentDbClient.deleteSession(id)
                agentRouteLogger.info("[delete-session] hard-deleted $deleted session(s) for root=$id")
            }

            // Stop anything still running for this session and clear pending
            // confirmations (defensive — the session should already be terminal).
            ProjectWorkers.broker.cancel(id)
            ConfirmationManager.clear(id)
            UserChoiceManager.clear(id)

            call.respond(mapOf("message" to "Session deleted"))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to e.message))
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    // ------ Export session as Markdown -----------------------------------------
    //
    // Query parameters:
    //   `scope=self` (default): export only the requested session's transcript
    //                            (legacy behaviour — matches pre-tree sessions)
    //   `scope=tree`           : export the full agent tree starting from the
    //                            requested session as root. The Markdown
    //                            document starts with a "Agent Tree" section
    //                            that lists per-layer statistics and a tree
    //                            diagram, followed by one peer section per
    //                            agent (each with its own statistics and the
    //                            full transcript).
    //
    get("/agent/sessions/{id}/export") {
        val instance = call.requireInstanceHeader() ?: return@get
        val id = call.parameters["id"].orEmpty()
        if (id.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session id"))
            return@get
        }
        val scope = (call.parameters["scope"] ?: "self").lowercase()
        try {
            val md = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                val rootInfo = agentDbClient.getSession(id)
                when (scope) {
                    "tree" -> renderTreeExport(agentDbClient, rootInfo)
                    else -> rootInfo.transcript?.takeIf { it.isNotBlank() }
                        ?: "*Session has no transcript yet (not started or still running).*\n\n*Export the session once the agent has completed.*"
                }
            }
            call.response.header(
                HttpHeaders.ContentDisposition,
                "attachment; filename=\"session_${id.take(8)}.md\""
            )
            call.respondText(md, ContentType.Text.Plain)
        } catch (e: DatabaseClient.DatabaseDaemonException) {
            if (e.statusCode == HttpStatusCode.NotFound) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
            } else {
                val (status, body) = errorPayload(e)
                call.respond(status, body)
            }
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }
}
