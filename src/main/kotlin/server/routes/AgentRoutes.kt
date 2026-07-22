package org.iotsplab.akiba.server.routes

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.LogManager
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.data.database.AgentDatabaseClient.SessionInfo
import org.iotsplab.akiba.data.database.DatabaseClient
import org.iotsplab.akiba.llm.agent.AgentMailboxService
import org.iotsplab.akiba.llm.tool.ConfirmationManager
import org.iotsplab.akiba.llm.agent.AgentPrompts
import org.iotsplab.akiba.llm.agent.ModelContextLengthService
import org.iotsplab.akiba.llm.agent.akibaAgent
import org.iotsplab.akiba.llm.client.LLMConfig
import org.iotsplab.akiba.llm.client.LLMProvider
import org.iotsplab.akiba.llm.config.LLMKeyFileStore
import org.iotsplab.akiba.llm.memory.persistentChatMemory
import org.iotsplab.akiba.llm.agent.AgentModule
import org.iotsplab.akiba.llm.agent.SYSTEM_SESSION_UUID
import org.iotsplab.akiba.llm.tool.BuiltInTools
import org.iotsplab.akiba.llm.tool.ListModulesTool
import org.iotsplab.akiba.llm.tool.QueryGhidraAPITool
import org.iotsplab.akiba.llm.tool.QuerySessionHistoryTool
import org.iotsplab.akiba.llm.tool.QueryMemoriesTool
import org.iotsplab.akiba.llm.tool.Tool
import org.iotsplab.akiba.llm.tool.RunScriptTool
import org.iotsplab.akiba.managers.ConfigManager
import org.iotsplab.akiba.managers.WorkspaceManager
import org.iotsplab.akiba.utils.ProcedureArgumentsDeserializer
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// ============================================================
//  Request / Response DTOs
// ============================================================

data class CreateAgentSessionRequest(
    val sessionName: String? = null,
    val systemPrompt: String? = null,
    val modelName: String? = null,
    val binaryId: Int? = null,
    val projectName: String? = null,
    val projectMode: String? = null,
    /**
     * Name of a specific program (domain file) within the Ghidra project.
     * When set together with [projectName] but without [binaryId], the
     * session is created against an existing project+program without
     * importing a new binary.  The first chat turn will open this program
     * directly instead of calling `ensureProgramForBinary`.
     */
    val programName: String? = null,
    /** Parent session id (set by `spawn_sub_agent` when spawning children). */
    val parentSessionId: String? = null
)

data class AgentSessionResponse(
    val sessionId: String,
    val sessionName: String?,
    val status: String,
    val modelName: String?,
    val projectName: String?,
    val moduleName: String?,
    val createdAt: String?,
    val updatedAt: String?,
    val parentSessionId: String? = null,
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
    /**
     * Lifecycle policy declared at spawn time (`one_shot` or `standby`).
     * Exposed so the frontend can render a dedicated "standby-capable"
     * badge for STANDBY children without round-tripping to the daemon.
     */
    val lifecycle: String? = null,
    /**
     * The session's `runtime_state` column value (mirrors `status` for
     * the current enum but kept distinct so future divergence is non-
     * breaking on the wire).  Optional for callers that only want the
     * legacy `status` view.
     */
    val runtimeState: String? = null,
    /**
     * Human-readable reason captured when the session entered a
     * terminal/error state.  The frontend should surface this when
     * `runtimeState == "error"` so users can see which child failed
     * and why without opening logs.
     */
    val closingReason: String? = null,
    /** Convenience alias for error displays. */
    val errorMessage: String? = null,
    /**
     * Pending human-confirmation request for this session, if any.
     *
     * Populated from [ConfirmationManager] when a tool (e.g. `run_shell`)
     * is blocked waiting for the user to approve/deny an action. The
     * frontend detects this via the existing session status poll and
     * shows a confirmation modal. `null` when no confirmation is pending.
     */
    val pendingConfirmation: PendingConfirmationDto? = null,
)

/** Wire DTO for a pending confirmation request (see [ConfirmationManager]). */
data class PendingConfirmationDto(
    val requestId: String,
    val toolName: String,
    val command: String,
    val workingDirectory: String,
    val timeout: Int,
    val action: String = "shell_command",
    val targetPath: String? = null,
    val createdAt: Long
)

/** Request body for `POST /agent/sessions/{id}/confirmation/respond`. */
data class ConfirmationRespondRequest(
    /** Whether the user approved the action. */
    val approved: Boolean
)

/** Request body for `POST /agent/internal/confirmation/request` (worker → server). */
data class InternalConfirmationRequest(
    val sessionId: String,
    val toolName: String,
    val command: String,
    val workingDirectory: String,
    val timeout: Int,
    /** Action type: "shell_command" or "file_access". Defaults to shell_command. */
    val action: String = "shell_command",
    /** Target path for file_access actions. Null for shell_command. */
    val targetPath: String? = null
)

data class AgentMessageResponse(
    val messageId: Long,
    val messageIndex: Int,
    val role: String,
    val content: String?,
    val createdAt: String?,
    val toolName: String? = null,
    val toolResult: String? = null
)

data class ChatRequest(
    /** Free-form user message. */
    val content: String,
    /** Optional system prompt override (only respected on the first turn). */
    val systemPrompt: String? = null
)

/**
 * Body of `POST /api/agent/sessions/{id}/inject`.
 *
 * Injects a user hint into a running, standby, closed, or error
 * session via the mailbox system.  The message is delivered to the
 * LLM as the last user message before the next LLM call.
 */
data class InjectRequest(
    /** The user's hint / guidance message. */
    val message: String,
    /**
     * If true, the message is delivered as a TRANSIENT user message
     * (visible to the current LLM call but NOT persisted to chat
     * history or compaction summaries).  If false, the message is
     * persisted as a regular user message.
     */
    val transient: Boolean = false,
)

data class ManualAgentStartRequest(
    val token: String
)

data class ManualAgentStartResponse(
    val sessionId: String,
    val content: String,
    val systemPrompt: String,
    val username: String,
    val programName: String? = null
)

private data class ManualAgentTurnJob(
    val sessionId: String,
    val content: String,
    val systemPrompt: String,
    val username: String,
    val programName: String? = null
)

private data class ManualAgentWorkerConfig(
    val file: Path,
    val environment: Map<String, String>
)

private object ManualAgentTurnRegistry {
    private val jobs = ConcurrentHashMap<String, ManualAgentTurnJob>()

    fun put(token: String, job: ManualAgentTurnJob) {
        jobs[token] = job
    }

    fun take(token: String): ManualAgentTurnJob? = jobs.remove(token)

    fun remove(token: String) {
        jobs.remove(token)
    }
}

private object ManualAgentProcessRegistry {
    private val processes = ConcurrentHashMap<String, Process>()

    fun put(sessionId: String, process: Process) {
        processes[sessionId] = process
    }

    fun remove(sessionId: String, process: Process) {
        processes.remove(sessionId, process)
    }

    fun cancel(sessionId: String): Boolean {
        val process = processes.remove(sessionId) ?: return false
        process.destroyForcibly()
        return true
    }
}

private val agentRouteLogger = LogManager.getLogger("AgentRoutes")
private val agentRouteMapper = jacksonObjectMapper()
private const val MANUAL_AGENT_TIMEOUT_MINUTES: Long = 10
private const val AGENT_MESSAGES_PAGE_SIZE = 500
private const val AGENT_MESSAGES_MAX_RETURN = 20_000

/**
 * Fetch a complete session transcript from the daemon despite the daemon's
 * `/agent/message/get` hard cap of 500 rows per request.
 *
 * The frontend wake selector needs all `[[AKIBA_WAKE_EVENT]]` markers and the
 * messages between them.  Fetching only the first page can make later wake
 * cycles unselectable or make a selected wake appear to have missing messages
 * once a long multi-wake agent session grows beyond 500 rows.
 */
private fun fetchAllAgentMessages(
    agentDbClient: AgentDatabaseClient,
    sessionId: String,
    fromIndex: Int = 0,
    maxMessages: Int = AGENT_MESSAGES_MAX_RETURN,
): List<AgentDatabaseClient.MessageInfo> {
    val out = mutableListOf<AgentDatabaseClient.MessageInfo>()
    var nextIndex = fromIndex
    while (out.size < maxMessages) {
        val page = agentDbClient.getMessages(
            sessionId = sessionId,
            fromIndex = nextIndex,
            limit = minOf(AGENT_MESSAGES_PAGE_SIZE, maxMessages - out.size),
        )
        if (page.isEmpty()) break
        out += page
        nextIndex = (page.maxOfOrNull { it.messageIndex } ?: nextIndex) + 1
        if (page.size < AGENT_MESSAGES_PAGE_SIZE) break
    }
    return out
}

data class ChatResponse(
    val sessionId: String,
    val userMessage: AgentMessageResponse,
    val assistantMessage: AgentMessageResponse,
    val iterations: Int,
    val tokenUsage: Map<String, Int>?
)

/**
 * Minimal [AgentModule] used by the interactive chat agent.
 * Provides no real task but gives access to [BuiltInTools] and
 * the session's [Program] (if a binary was selected).
 */
private class ChatAgentModule(
    dbClient: DatabaseClient,
    program: ghidra.program.model.listing.Program? = null,
    id: Int = -1,
    private val username: String? = null
) : AgentModule(
    id = id,
    program = program,
    dbClient = dbClient
) {
    override fun taskPrompt(): String = ""
    override fun defineTools(): List<Tool> = emptyList()
    override fun includeBuiltInTools(): Boolean = true
    override fun toolUsername(): String? = username
}

private val DEFAULT_AGENT_SYSTEM_PROMPT: String = """
    ${AgentPrompts.DEFAULT_SYSTEM_PROMPT}

    <interactive_session>
    You are Akiba's interactive assistant. Help the user reason about binary
    analysis, scripting, and the Akiba platform. Answer concisely, but keep
    conclusions evidence-backed. If the selected session has a binary, assume
    that binary is the current analysis target.
    </interactive_session>
""".trimIndent()

private fun withCommonAgentRules(prompt: String): String =
    if (prompt.contains("<tools_usage_policy>")) prompt else "$prompt\n\n${AgentPrompts.DEFAULT_AGENT_RULES}"

// ============================================================
//  Routes
// ============================================================

fun Route.agentRoutes(daemonHost: String, daemonPort: Int) {

    // ------ Internal manual-agent worker handshake ---------------------------
    post("/agent/internal/manual-turn/start") {
        val req = call.receive<ManualAgentStartRequest>()
        val job = ManualAgentTurnRegistry.take(req.token)
        if (job == null) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Invalid or expired manual-agent token"))
            return@post
        }
        call.respond(ManualAgentStartResponse(
            sessionId = job.sessionId,
            content = job.content,
            systemPrompt = job.systemPrompt,
            username = job.username,
            programName = job.programName
        ))
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

    // ------ List sessions -----------------------------------------------------
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
            call.respond(info.toResponse(pendingConf))
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

    // ------ Create session ----------------------------------------------------
    post("/agent/sessions") {
        val instance = call.requireInstanceHeader() ?: return@post
        val req = runCatching { call.receive<CreateAgentSessionRequest>() }
            .getOrDefault(CreateAgentSessionRequest())
        val projectDirectory = call.currentUserProjectDirectory()
        try {
            val info = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)

                // Determine the project name and whether to create a new project.
                //
                // Path A: binaryId is provided → legacy flow: import binary
                //         into a (possibly new) project on first chat turn.
                // Path B: projectName + programName provided (no binaryId) →
                //         open an existing project and use the specified
                //         program directly.  No binary import needed.
                val resolvedProjectName: String?
                val createNewProject: Boolean

                if (req.binaryId != null) {
                    val name = req.projectName?.takeIf { it.isNotBlank() }
                        ?: "agent-${req.binaryId}-${System.currentTimeMillis()}"
                    resolvedProjectName = name
                    createNewProject = req.projectMode != "existing"
                    WorkspaceManager.openOrCreateInteractiveProject(name, createNewProject, projectDirectory)
                } else if (req.projectName != null && req.projectName.isNotBlank()) {
                    resolvedProjectName = req.projectName
                    createNewProject = req.projectMode == "new"
                    // Open the existing project so the first chat turn can
                    // access the requested program.
                    WorkspaceManager.openOrCreateInteractiveProject(
                        req.projectName, createNewProject, projectDirectory
                    )
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
                        org.iotsplab.akiba.llm.agent.RuntimeState.CLOSED.wire(),
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
            val filtered = msgs.filter { it.role != "system" }
            // Compute cumulative token usage from all messages in the session.
            // Do not serialize this polling request behind long-running chat turns.
            val allMsgs = withDaemonSession(
                daemonHost, daemonPort, instance, serialize = false
            ) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                fetchAllAgentMessages(agentDbClient, id)
            }
            var totalInput = 0L
            var totalOutput = 0L
            // inputTokenCount is set on assistant messages from LLM tokenUsage
            // tokenCount holds the output token count for assistant messages
            for (m in allMsgs) {
                totalInput += m.inputTokenCount ?: 0
                totalOutput += m.tokenCount ?: 0
            }
            // Find the last input token count (from the most recent LLM call)
            val lastInputTokens = allMsgs.lastOrNull { it.role == "assistant" }?.inputTokenCount
            // Resolve context length for this session's model
            val contextLength = sessionInfo.modelName?.let { modelName ->
                LLMKeyFileStore.load().firstOrNull { entry ->
                    entry.modelNames.any { it.equals(modelName, ignoreCase = true) }
                }?.let { entry ->
                    val provider = LLMProvider.fromString(entry.provider)
                    if (provider != null) ModelContextLengthService.getContextLength(provider, modelName) else null
                }
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
    // [org.iotsplab.akiba.llm.agent.AgentMailboxService.listConversations]
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
    // [org.iotsplab.akiba.llm.agent.AgentMailboxService.getConversationMessages]
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
        val projectDirectory = call.currentUserProjectDirectory()
        val serverPort = call.request.local.serverPort

        try {
            val sessionInfo = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                val info = agentDbClient.getSession(id)
                agentDbClient.updateSession(id, status = "running")
                agentDbClient.setRuntimeState(
                    id,
                    org.iotsplab.akiba.llm.agent.RuntimeState.RUNNING.wire(),
                    "manual_chat_start"
                )
                info
            }

            val token = UUID.randomUUID().toString()
            // For project-based sessions (no binaryId), try to find the
            // program name in the project so the manual agent worker
            // can open it.  This is a best-effort lookup — if it fails,
            // the worker will try to auto-open the first program.
            var programName: String? = null
            if (sessionInfo.binaryId == null && sessionInfo.projectName != null) {
                programName = try {
                    findFirstProgramName(sessionInfo.projectName, call.currentUserGhidraProjectsRoot())
                } catch (_: Exception) { null }
            }
            ManualAgentTurnRegistry.put(token, ManualAgentTurnJob(
                id, req.content, systemPrompt, call.currentUsernameOrDefault(),
                programName
            ))

            val workerConfig = createManualAgentConfig(
                sessionInfo = sessionInfo,
                instanceName = instance,
                daemonHost = daemonHost,
                daemonPort = daemonPort,
                projectDirectory = projectDirectory
            )

            try {
                runManualAgentWorker(
                    workerConfig = workerConfig,
                    serverPort = serverPort,
                    token = token,
                    sessionInfo = sessionInfo
                )
            } finally {
                ManualAgentTurnRegistry.remove(token)
                runCatching { Files.deleteIfExists(workerConfig.file) }
            }

            withDaemonSession(daemonHost, daemonPort, instance, serialize = false) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                val current = agentDbClient.getSession(id)
                if (current.status == "running") {
                    agentDbClient.updateSession(id, status = "closed")
                    runCatching {
                        agentDbClient.setRuntimeState(
                            id,
                            org.iotsplab.akiba.llm.agent.RuntimeState.CLOSED.wire(),
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
                ?: throw IllegalStateException("User message was not persisted")
            val asstMsg = msgs.lastOrNull { it.role == "assistant" }
                ?: throw IllegalStateException("Assistant message was not persisted")

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
        } catch (e: IllegalStateException) {
            markManualChatFailed(daemonHost, daemonPort, instance, id, e.message)
            call.respond(HttpStatusCode.ServiceUnavailable,
                mapOf("error" to "Agent is not configured: ${e.message}"))
        } catch (e: Exception) {
            markManualChatFailed(daemonHost, daemonPort, instance, id, e.message)
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    // ------ Inject a user hint into a running/standby/closed/error session --
    //
    // Unlike /chat (which starts a synchronous manual agent turn),
    // /inject sends an asynchronous mailbox message to the session.
    // For RUNNING agents, the message is picked up by
    // applyMailboxDrain in the next beforeIteration.  For STANDBY
    // agents, the AgentMailboxDispatcher wakes them up.  For
    // CLOSED/ERROR agents, the runtime restarts the session.
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
                val mailboxService = org.iotsplab.akiba.llm.agent.AgentMailboxService(agentDbClient)
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
                        val runtime = org.iotsplab.akiba.llm.agent.AgentRuntime.forBinary(binaryId, agentDbClient)
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
    // The user can pause a running agent to temporarily halt its LLM
    // loop.  The agent finishes the current iteration, then blocks
    // before the next LLM call.  Other agents can still send messages
    // (they accumulate in the mailbox).  Resume un-blocks the loop.

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
                val runtime = org.iotsplab.akiba.llm.agent.AgentRuntime.forBinary(binaryId, agentDbClient)
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
                val runtime = org.iotsplab.akiba.llm.agent.AgentRuntime.forBinary(binaryId, agentDbClient)
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
                val runtime = org.iotsplab.akiba.llm.agent.AgentRuntime.forBinary(binaryId, agentDbClient)
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

    // ------ Cancel a running manual chat turn --------------------------------
    //
    // When the user sends a chat message via /chat, the server spawns
    // a child process and blocks until it finishes.  This endpoint
    // forcibly kills that child process so the /chat call returns with
    // an error, letting the user send a new message.

    post("/agent/sessions/{id}/cancel-chat") {
        val id = call.parameters["id"].orEmpty()
        if (id.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session id"))
            return@post
        }
        val cancelled = ManualAgentProcessRegistry.cancel(id)
        if (cancelled) {
            // Mark the session as closed so the frontend can recover.
            val instance = call.instanceHeader()
            if (instance != null) {
                runCatching {
                    withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                        val agentDbClient = AgentDatabaseClient(dbClient)
                        agentDbClient.updateSession(id, status = "closed")
                        runCatching {
                            agentDbClient.setRuntimeState(
                                id,
                                org.iotsplab.akiba.llm.agent.RuntimeState.CLOSED.wire(),
                                "user_cancelled_chat"
                            )
                        }
                    }
                }
            }
            call.respond(mapOf("status" to "cancelled", "sessionId" to id))
        } else {
            call.respond(HttpStatusCode.Conflict, mapOf(
                "error" to "No running manual chat process for this session"
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

    // ------ Delete session ----------------------------------------------------
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

            // Cancel any lingering manual agent process and clear
            // pending confirmations (defensive — the session should
            // already be terminal, but just in case).
            ManualAgentProcessRegistry.cancel(id)
            ConfirmationManager.clear(id)

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

/**
 * Statistics computed from a session's message history. Used in both the
 * "Agent Tree" overview and the per-agent sections of the tree-export
 * document.
 */
private data class SessionStats(
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val toolCallCount: Int,
    val messageCount: Int,
    /** Map of tool name → number of invocations. */
    val toolUsage: Map<String, Int>
)

/**
 * A node in the agent hierarchy being exported, paired with its own
 * statistics. Children are populated by [renderTreeExport].
 */
private data class ExportTreeNode(
    val info: AgentDatabaseClient.SessionInfo,
    val stats: SessionStats,
    val depth: Int,
    val children: List<ExportTreeNode>
)

/**
 * Build a Markdown document that contains:
 *   1. A header with overall export metadata.
 *   2. An "Agent Tree" section with:
 *        - per-layer statistics (token usage, tool calls)
 *        - a textual ASCII tree diagram
 *   3. A peer section per agent in DFS pre-order, each containing the
 *      session's own statistics and its full transcript.
 *
 * Cycles are detected via a `seen` set and silently skipped so a malformed
 * hierarchy cannot crash the export.
 */
private fun renderTreeExport(
    agentDbClient: AgentDatabaseClient,
    root: AgentDatabaseClient.SessionInfo
): String {
    val rootNode = collectExportTree(agentDbClient, root, depth = 0, seen = HashSet())
    if (rootNode == null) {
        // The root session was deleted while we were iterating — fall back
        // to a single-session export so the user still gets useful output.
        return root.transcript?.takeIf { it.isNotBlank() }
            ?: "*Session has no transcript yet (not started or still running).*\n\n*Export the session once the agent has completed.*"
    }
    val tree = rootNode.descendantsFlattened()

    val totalInput = tree.sumOf { it.stats.totalInputTokens }
    val totalOutput = tree.sumOf { it.stats.totalOutputTokens }
    val totalToolCalls = tree.sumOf { it.stats.toolCallCount }
    val maxDepth = tree.maxOf { it.depth }

    // Per-layer aggregation. Use a sorted map keyed by depth so layers
    // appear in natural order in the table.
    val perLayer = sortedMapOf<Int, MutableList<ExportTreeNode>>()
    tree.forEach { node ->
        perLayer.getOrPut(node.depth) { mutableListOf() }.add(node)
    }
    val layerStats: List<Triple<Int, List<ExportTreeNode>, SessionStats>> = perLayer.map { (depth, nodes) ->
        val layer = SessionStats(
            totalInputTokens = nodes.sumOf { it.stats.totalInputTokens },
            totalOutputTokens = nodes.sumOf { it.stats.totalOutputTokens },
            toolCallCount = nodes.sumOf { it.stats.toolCallCount },
            messageCount = nodes.sumOf { it.stats.messageCount },
            toolUsage = nodes.flatMap { it.stats.toolUsage.entries }
                .groupBy({ it.key }, { it.value })
                .mapValues { (_, values) -> values.sum() }
        )
        Triple(depth, nodes, layer)
    }

    val now = nowString()
    val md = buildString {
        appendLine()
        appendLine("---")
        appendLine()
        appendLine("# Agent Tree Export — ${root.sessionName ?: root.sessionId.take(8)}")
        appendLine()
        appendLine("| Field | Value |")
        appendLine("|-------|-------|")
        appendLine("| Exported at | `$now` |")
        appendLine("| Root Session | `${root.sessionId}` |")
        appendLine("| Root Name | ${root.sessionName?.let { "`$it`" } ?: "_(unnamed)_"} |")
        appendLine("| Root Status | `${root.status}` |")
        appendLine("| Root Model | ${root.modelName?.let { "`$it`" } ?: "_(unknown)_"} |")
        appendLine("| Total Agents | ${tree.size} |")
        appendLine("| Max Depth | $maxDepth |")
        appendLine("| Total Input Tokens | $totalInput |")
        appendLine("| Total Output Tokens | $totalOutput |")
        appendLine("| Total Tool Calls | $totalToolCalls |")
        appendLine()

        // ---- Agent Tree overview ---------------------------------------------
        appendLine("---")
        appendLine()
        appendLine("## Agent Tree")
        appendLine()
        appendLine("This section describes the hierarchy of all agents reached from the")
        appendLine("exported root session, including every spawned sub-agent at any depth.")
        appendLine()
        appendLine("The tree grows **left-to-right**: the root is the leftmost node and")
        appendLine("each additional column represents one more level of depth. Sub-agents")
        appendLine("appear as peer sections later in this document so the structure stays")
        appendLine("flat and readable when an agent spawns many levels of children.")
        appendLine()

        appendLine("### Per-Layer Statistics")
        appendLine()
        appendLine("| Layer | Agents | Input Tokens | Output Tokens | Tool Calls | Messages |")
        appendLine("|-------|--------|--------------|---------------|------------|----------|")
        for ((depth, nodes, stats) in layerStats) {
            appendLine("| $depth | ${nodes.size} | ${stats.totalInputTokens} | ${stats.totalOutputTokens} | ${stats.toolCallCount} | ${stats.messageCount} |")
        }
        appendLine()

        // ---- Top tools used per layer (only the first few layers, to keep
        //      the document readable when there are many layers).
        appendLine("### Tool Usage by Layer")
        appendLine()
        for ((depth, _, stats) in layerStats) {
            if (stats.toolUsage.isEmpty()) continue
            val top = stats.toolUsage.entries.sortedByDescending { it.value }.take(8)
            val breakdown = top.joinToString(", ") { "`${it.key}` × ${it.value}" }
            appendLine("- **Layer $depth**: $breakdown")
        }
        appendLine()

        // ---- ASCII tree diagram (left-to-right indentation) ------------------
        appendLine("### Tree Diagram")
        appendLine()
        appendLine("The diagram below uses 4-space indentation to mirror the left-to-right")
        appendLine("layout of the frontend: the root is at column 0, its direct children")
        appendLine("at column 1, and so on.")
        appendLine()
        appendLine("```text")
        renderAsciiTree(this, rootNode, depth = 0)
        appendLine("```")
        appendLine()

        // ---- Per-agent sections ----------------------------------------------
        appendLine("---")
        appendLine()
        appendLine("## Agent Transcripts")
        appendLine()
        appendLine("Each agent appears as a peer section below, listed in DFS pre-order")
        appendLine("(parent before its children). Within each section you will find the")
        appendLine("session's own statistics and its full transcript.")
        appendLine()

        for (node in tree) {
            appendLine("---")
            appendLine()
            appendLine("## Agent ${nodeDepthLabel(node.depth)} — ${node.info.sessionName ?: node.info.sessionId.take(8)}")
            appendLine()
            appendLine("**ID:** `${node.info.sessionId}`")
            appendLine()
            appendLine("| Field | Value |")
            appendLine("|-------|-------|")
            appendLine("| Depth | ${node.depth} |")
            appendLine("| Status | `${node.info.status}` |")
            appendLine("| Model | ${node.info.modelName?.let { "`$it`" } ?: "_(unknown)_"} |")
            appendLine("| Module | ${node.info.moduleName?.let { "`$it`" } ?: "_(unknown)_"} |")
            appendLine("| Parent | ${node.info.parentSessionId?.let { "`$it`" } ?: "_none (root)_"} |")
            appendLine("| Created | ${node.info.createdAt ?: "_(unknown)_"} |")
            appendLine("| Updated | ${node.info.updatedAt ?: "_(unknown)_"} |")
            appendLine("| Children | ${node.children.size} |")
            appendLine()
            appendLine("**Statistics:**")
            appendLine()
            appendLine("- Input tokens: `${node.stats.totalInputTokens}`")
            appendLine("- Output tokens: `${node.stats.totalOutputTokens}`")
            appendLine("- Total tokens: `${node.stats.totalInputTokens + node.stats.totalOutputTokens}`")
            appendLine("- Tool calls: `${node.stats.toolCallCount}`")
            appendLine("- Messages: `${node.stats.messageCount}`")
            if (node.stats.toolUsage.isNotEmpty()) {
                val tools = node.stats.toolUsage.entries
                    .sortedByDescending { it.value }
                    .joinToString(", ") { "`${it.key}` × ${it.value}" }
                appendLine("- Tool breakdown: $tools")
            }
            appendLine()
            appendLine("### Transcript")
            appendLine()
            val transcript = node.info.transcript?.takeIf { it.isNotBlank() }
                ?: "_No transcript recorded yet._"
            appendLine(transcript)
            appendLine()
        }

        appendLine("---")
        appendLine()
    }
    return md
}

/**
 * Walk the parent → child graph and produce a subtree rooted at [root].
 *
 * Cycles are guarded via [seen]; sessions that fail to load are skipped
 * silently so a malformed hierarchy cannot crash the export. Returns
 * `null` when [root] is already part of an ancestor's chain (cycle) or
 * when its metadata cannot be read — the caller should treat that as
 * "stop descending here".
 */
private fun collectExportTree(
    agentDbClient: AgentDatabaseClient,
    root: AgentDatabaseClient.SessionInfo,
    depth: Int,
    seen: HashSet<String>
): ExportTreeNode? {
    if (!seen.add(root.sessionId)) return null
    val children = try {
        agentDbClient.getSessionChildren(root.sessionId)
    } catch (_: Exception) {
        emptyList()
    }
    val stats = computeSessionStats(agentDbClient, root.sessionId)
    val childNodes = children.mapNotNull { child ->
        try {
            val childInfo = agentDbClient.getSession(child.sessionId)
            collectExportTree(agentDbClient, childInfo, depth + 1, seen)
        } catch (_: Exception) {
            null
        }
    }
    return ExportTreeNode(info = root, stats = stats, depth = depth, children = childNodes)
}

/**
 * Flatten the export tree into DFS pre-order (parent before its children).
 * Used by [renderTreeExport] to render the per-agent peer sections and to
 * compute aggregate counts.
 */
private fun ExportTreeNode.descendantsFlattened(): List<ExportTreeNode> =
    listOf(this) + children.flatMap { it.descendantsFlattened() }

/**
 * Compute aggregate statistics for a session by walking its message
 * history. Assistant messages carry `inputTokenCount` / `tokenCount`;
 * tool invocations are messages with `role = "tool"`.
 */
private fun computeSessionStats(
    agentDbClient: AgentDatabaseClient,
    sessionId: String
): SessionStats {
    var inputTokens = 0L
    var outputTokens = 0L
    var toolCalls = 0
    var totalMessages = 0
    val toolCounts = HashMap<String, Int>()
    try {
        // Pull enough messages to cover long-running sessions. We do not
        // expect more than a few thousand even for sub-agents.
        val messages = agentDbClient.getMessages(sessionId, 0, 5000)
        for (m in messages) {
            totalMessages++
            if (m.role == "assistant") {
                inputTokens += m.inputTokenCount ?: 0
                outputTokens += m.tokenCount ?: 0
            } else if (m.role == "tool") {
                toolCalls++
                val name = m.toolName ?: "unknown"
                toolCounts.merge(name, 1) { a, b -> a + b }
            }
        }
    } catch (_: Exception) {
        // Best-effort: missing messages yield zeroed stats.
    }
    return SessionStats(
        totalInputTokens = inputTokens,
        totalOutputTokens = outputTokens,
        toolCallCount = toolCalls,
        messageCount = totalMessages,
        toolUsage = toolCounts
    )
}

/**
 * Write an ASCII tree representation of [root] into [out], using the
 * classic "├──" / "└──" connectors. Recurses depth-first into each
 * node's children. Each node label carries its status and short id so
 * the diagram is self-explanatory when shared without the surrounding
 * table.
 */
private fun renderAsciiTree(
    out: StringBuilder,
    root: ExportTreeNode,
    depth: Int
) {
    val indent = "    ".repeat(depth)
    renderAsciiTreeLine(out, root, indent, isLast = true, isRoot = true)
}

/**
 * Helper that emits one node and recurses into its children. Each line
 * receives a pre-computed [prefix] so children stay aligned with their
 * parent's connector ("│   " for non-last, "    " for last).
 */
private fun renderAsciiTreeLine(
    out: StringBuilder,
    node: ExportTreeNode,
    prefix: String,
    isLast: Boolean,
    isRoot: Boolean
) {
    val connector = when {
        isRoot -> ""
        isLast -> "└── "
        else -> "├── "
    }
    val shortId = node.info.sessionId.take(8)
    val name = node.info.sessionName?.take(32) ?: "(unnamed)"
    val status = node.info.status
    val tokens = node.stats.totalInputTokens + node.stats.totalOutputTokens
    out.append(
        prefix + connector +
            "`${name}` [$status] (id=$shortId, depth=${node.depth}, " +
            "in=${node.stats.totalInputTokens}, out=${node.stats.totalOutputTokens}, " +
            "tools=${node.stats.toolCallCount}, total=$tokens)\n"
    )
    if (node.children.isNotEmpty()) {
        val childPrefix = prefix + if (isRoot) "" else if (isLast) "    " else "│   "
        node.children.forEachIndexed { idx, child ->
            renderAsciiTreeLine(out, child, childPrefix, idx == node.children.lastIndex, isRoot = false)
        }
    }
}

private fun nodeDepthLabel(depth: Int): String = when (depth) {
    0 -> "(root)"
    else -> "($depth)"
}

private fun nowString(): String =
    java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

private fun markManualChatFailed(
    daemonHost: String,
    daemonPort: Int,
    instance: String,
    sessionId: String,
    reason: String?,
) {
    runCatching {
        withDaemonSession(daemonHost, daemonPort, instance, serialize = false) { dbClient ->
            val agentDbClient = AgentDatabaseClient(dbClient)
            val current = agentDbClient.getSession(sessionId)
            if (current.status != "closed" || current.closingReason.isNullOrEmpty()) {
                agentDbClient.updateSession(sessionId, status = "error")
                agentDbClient.setRuntimeState(
                    sessionId,
                    org.iotsplab.akiba.llm.agent.RuntimeState.ERROR.wire(),
                    reason?.take(200) ?: "manual_chat_failed",
                )
            }
        }
    }
}

private fun createManualAgentConfig(
    sessionInfo: SessionInfo,
    instanceName: String,
    daemonHost: String,
    daemonPort: Int,
    projectDirectory: Path
): ManualAgentWorkerConfig {
    val binaryId = sessionInfo.binaryId
    val modelName = sessionInfo.modelName
        ?: throw IllegalStateException("Manual agent session has no selected LLM model")
    val keyEntry = LLMKeyFileStore.load().firstOrNull { entry ->
        entry.modelNames.any { it.equals(modelName, ignoreCase = true) }
    } ?: throw IllegalStateException("No LLM key configured for model '$modelName'")

    val llmEnvName = "AKIBA_MANUAL_LLM_${UUID.randomUUID().toString().replace("-", "") }"
    val projectRoot = Path.of(ConfigManager.projectConf.projectRoot)
        .resolve(projectDirectory)
        .toAbsolutePath()
        .normalize()
    val projectName = sessionInfo.projectName ?: "manual-agent-${sessionInfo.sessionId.take(8)}"

    val mainConfig = linkedMapOf<String, Any?>(
        "username" to DAEMON_USER,
        "password" to DAEMON_PASSWORD,
        "usingInstance" to instanceName,
        "general" to linkedMapOf(
            "autoAnalysisTimeout" to ConfigManager.mainConf.autoAnalysisTimeout,
            "threads" to 1,
            "logsRoot" to ConfigManager.mainConf.logsRoot,
            "workspaceRoot" to ConfigManager.mainConf.workspaceRoot
        ),
        "withGhidraProject" to linkedMapOf(
            "projectRoot" to projectRoot.toString(),
            "name" to projectName,
            "mode" to "new",
            "saveProject" to true,
            "noCreateProgram" to (binaryId == null)
        ),
        "sqlSource" to linkedMapOf(
            "serverIP" to daemonHost,
            "serverPort" to daemonPort,
            "useSnapshot" to "current",
            "constraint" to (binaryId?.let { "WHERE u.id = $it" } ?: "server"),
            "disableUpdate" to false,
            "useLocalCache" to null
        ),
        "llm" to linkedMapOf(
            "provider" to keyEntry.provider,
            "modelName" to modelName,
            "apiKeyEnv" to llmEnvName,
            "baseUrl" to (keyEntry.baseUrl ?: "")
        ),
        "tasks" to listOf(
            linkedMapOf(
                "mainClassName" to "org.iotsplab.akiba.module.AkibaUtils",
                "timeout" to 600,
                "consoleLogLevel" to "INFO",
                "fileLogLevel" to "DEBUG"
            )
        )
    )
    val root = linkedMapOf("main" to mainConfig)
    val file = Files.createTempFile("akiba-manual-agent-", ".json")
    file.toFile().writeText(agentRouteMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root))
    return ManualAgentWorkerConfig(file, mapOf(llmEnvName to keyEntry.apiKey))
}

private fun runManualAgentWorker(
    workerConfig: ManualAgentWorkerConfig,
    serverPort: Int,
    token: String,
    sessionInfo: SessionInfo
) {
    val scriptPath = findAkibaScriptForAgent()
    val pb = ProcessBuilder(scriptPath, "-c", "${workerConfig.file}@/main")
    pb.directory(File(System.getProperty("user.dir", ".")))
    val env = pb.environment()
    env.putAll(workerConfig.environment)
    env["AKIBA_MANUAL_AGENT"] = "1"
    env["AKIBA_MANUAL_AGENT_TOKEN"] = token
    env["AKIBA_MANUAL_AGENT_SERVER_PORT"] = serverPort.toString()
    env["AKIBA_MANUAL_AGENT_SESSION_ID"] = sessionInfo.sessionId
    sessionInfo.projectName?.let { env["AKIBA_MANUAL_AGENT_PROJECT_NAME"] = it }
    pb.redirectErrorStream(true)
    val logFile = Files.createTempFile("akiba-manual-agent-${sessionInfo.sessionId.take(8)}-", ".log")
    pb.redirectOutput(logFile.toFile())

    val process = pb.start()
    ManualAgentProcessRegistry.put(sessionInfo.sessionId, process)
    val finished = try {
        process.waitFor(MANUAL_AGENT_TIMEOUT_MINUTES, TimeUnit.MINUTES)
    } finally {
        ManualAgentProcessRegistry.remove(sessionInfo.sessionId, process)
    }
    val output = runCatching { Files.readString(logFile) }.getOrDefault("")
    runCatching { Files.deleteIfExists(logFile) }
    if (!finished) {
        process.destroyForcibly()
        throw IllegalStateException("Manual agent worker timed out after ${MANUAL_AGENT_TIMEOUT_MINUTES} minutes\n$output")
    }
    val exitCode = process.exitValue()
    if (exitCode != 0) {
        throw IllegalStateException("Manual agent worker failed with exit code $exitCode\n$output")
    }
    if (output.isNotBlank()) {
        agentRouteLogger.debug("[manual-agent ${sessionInfo.sessionId}]\n$output")
    }
}

/** Best-effort lookup of the first program name in a Ghidra project.
 *  Used by the manual-agent chat endpoint to pass the program name
 *  to the worker so it can open it for tool access. */
private fun findFirstProgramName(projectName: String, projectDir: java.nio.file.Path): String? {
    val grpFile = projectDir.resolve("$projectName.gpr")
    val repFile = projectDir.resolve("$projectName.rep")
    if (!Files.isRegularFile(grpFile) || !Files.isDirectory(repFile)) return null
    return try {
        WorkspaceManager.openOrCreateInteractiveProject(projectName, false, projectDir)
        val project = WorkspaceManager.project
        project.projectData.refresh(true)
        val first = project.projectData.rootFolder.files.firstOrNull { f ->
            val doc = f.domainObjectClass
            doc != null && ghidra.program.model.listing.Program::class.java.isAssignableFrom(doc)
        }
        WorkspaceManager.releaseActiveProject()
        first?.name
    } catch (_: Exception) {
        null
    }
}

private fun findAkibaScriptForAgent(): String {
    try {
        val source = org.iotsplab.akiba.Main::class.java.protectionDomain.codeSource
        val loc = source.location.toURI()
        val jarFile = File(loc)
        if (jarFile.name.endsWith(".jar")) {
            val distRoot = jarFile.parentFile.parentFile
            val script = File(distRoot, "bin/akiba")
            if (script.isFile) return script.absolutePath
        }
    } catch (_: Exception) { }
    val cwd = System.getProperty("user.dir", ".")
    val candidates = listOf(
        File(cwd, "bin/akiba"),
        File(cwd, "../bin/akiba"),
        File(cwd, "akiba_framework/bin/akiba")
    )
    for (candidate in candidates) {
        if (candidate.isFile) return candidate.absolutePath
    }
    return "akiba"
}

private fun SessionInfo.toResponse(
    pendingConfirmation: PendingConfirmationDto? = null
) = AgentSessionResponse(
    sessionId = sessionId,
    sessionName = sessionName,
    status = status,
    modelName = modelName,
    projectName = projectName,
    moduleName = moduleName,
    createdAt = createdAt,
    updatedAt = updatedAt,
    parentSessionId = parentSessionId,
    lifecycle = lifecycle,
    runtimeState = runtimeState,
    closingReason = closingReason,
    errorMessage = if (runtimeState == "error" || status == "error") closingReason else null,
    pendingConfirmation = pendingConfirmation,
    // token totals omitted here for performance; use GET /agent/sessions/{id}/messages
)

private fun AgentDatabaseClient.MessageInfo.toMessageResponse() = AgentMessageResponse(
    messageId = messageId,
    messageIndex = messageIndex,
    role = role,
    content = if (role == "tool") toolResult ?: content else content,
    createdAt = createdAt,
    toolName = if (role == "tool") toolName else null,
    toolResult = if (role == "tool") (toolResult ?: content) else null
)


