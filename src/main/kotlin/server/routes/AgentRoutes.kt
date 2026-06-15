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
import org.iotsplab.akiba.llm.agent.AgentPrompts
import org.iotsplab.akiba.llm.agent.ModelContextLengthService
import org.iotsplab.akiba.llm.agent.akibaAgent
import org.iotsplab.akiba.llm.client.LLMConfig
import org.iotsplab.akiba.llm.client.LLMProvider
import org.iotsplab.akiba.llm.config.LLMKeyFileStore
import org.iotsplab.akiba.llm.memory.persistentChatMemory
import org.iotsplab.akiba.llm.agent.AgentModule
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
    val projectMode: String? = null
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
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0
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

data class ManualAgentStartRequest(
    val token: String
)

data class ManualAgentStartResponse(
    val sessionId: String,
    val content: String,
    val systemPrompt: String,
    val username: String
)

private data class ManualAgentTurnJob(
    val sessionId: String,
    val content: String,
    val systemPrompt: String,
    val username: String
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

private val agentRouteLogger = LogManager.getLogger("AgentRoutes")
private val agentRouteMapper = jacksonObjectMapper()

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
            username = job.username
        ))
    }

    // ------ List sessions -----------------------------------------------------
    get("/agent/sessions") {
        val instance = call.requireInstanceHeader() ?: return@get
        val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
        val offset = call.parameters["offset"]?.toIntOrNull() ?: 0
        val status = call.parameters["status"]?.takeIf { it.isNotBlank() }
        val binaryId = call.parameters["binaryId"]?.toIntOrNull()
        val moduleName = call.parameters["moduleName"]?.takeIf { it.isNotBlank() }
        try {
            val sessions = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                agentDbClient.listSessions(
                    status = status,
                    binaryId = binaryId,
                    moduleName = moduleName,
                    limit = limit,
                    offset = offset
                )
            }
            call.respond(mapOf("sessions" to sessions.map { it.toResponse() }))
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
                val resolvedProjectName = req.binaryId?.let { binaryId ->
                    val name = req.projectName?.takeIf { it.isNotBlank() }
                        ?: "agent-${binaryId}-${System.currentTimeMillis()}"
                    val createNew = req.projectMode != "existing"
                    // Only bind/open the project here. Importing and auto-analyzing the
                    // binary can take a long time, so defer that to the first chat turn.
                    WorkspaceManager.openOrCreateInteractiveProject(name, createNew, projectDirectory)
                    name
                }
                val sessionId = agentDbClient.createSession(
                    sessionName = req.sessionName ?: "Chat ${System.currentTimeMillis()}",
                    binaryId = req.binaryId,
                    moduleName = "chat",
                    modelName = req.modelName,
                    projectName = resolvedProjectName
                )
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
        val fromIndex = call.parameters["fromIndex"]?.toIntOrNull() ?: 0
        val limit = call.parameters["limit"]?.toIntOrNull() ?: 500

        try {
            val (sessionInfo, msgs) = withDaemonSession(
                daemonHost, daemonPort, instance, serialize = false
            ) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                val info = agentDbClient.getSession(id)
                val messages = agentDbClient.getMessages(id, fromIndex, limit)
                info to messages
            }
            val filtered = msgs.filter { it.role != "system" }
            // Compute cumulative token usage from all messages in the session.
            // Do not serialize this polling request behind long-running chat turns.
            val allMsgs = withDaemonSession(
                daemonHost, daemonPort, instance, serialize = false
            ) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                agentDbClient.getMessages(id, 0, 5000)
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
                info
            }

            val token = UUID.randomUUID().toString()
            ManualAgentTurnRegistry.put(token, ManualAgentTurnJob(
                id, req.content, systemPrompt, call.currentUsernameOrDefault()
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
            call.respond(HttpStatusCode.ServiceUnavailable,
                mapOf("error" to "Agent is not configured: ${e.message}"))
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
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
                agentDbClient.updateSession(id, status = "cancelled")
            }
            call.respond(mapOf("message" to "Session cancelled"))
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    // ------ Export session as Markdown -----------------------------------------
    get("/agent/sessions/{id}/export") {
        val instance = call.requireInstanceHeader() ?: return@get
        val id = call.parameters["id"].orEmpty()
        if (id.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session id"))
            return@get
        }
        try {
            val sessionInfo = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                agentDbClient.getSession(id)
            }
            val md = sessionInfo.transcript?.takeIf { it.isNotBlank() }
                ?: "*Session has no transcript yet (not started or still running).*\n\n*Export the session once the agent has completed.*"
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
    pb.redirectErrorStream(true)
    val logFile = Files.createTempFile("akiba-manual-agent-${sessionInfo.sessionId.take(8)}-", ".log")
    pb.redirectOutput(logFile.toFile())

    val process = pb.start()
    val finished = process.waitFor(15, TimeUnit.MINUTES)
    val output = runCatching { Files.readString(logFile) }.getOrDefault("")
    runCatching { Files.deleteIfExists(logFile) }
    if (!finished) {
        process.destroyForcibly()
        throw IllegalStateException("Manual agent worker timed out\n$output")
    }
    val exitCode = process.exitValue()
    if (exitCode != 0) {
        throw IllegalStateException("Manual agent worker failed with exit code $exitCode\n$output")
    }
    if (output.isNotBlank()) {
        agentRouteLogger.debug("[manual-agent ${sessionInfo.sessionId}]\n$output")
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

private fun SessionInfo.toResponse() = AgentSessionResponse(
    sessionId = sessionId,
    sessionName = sessionName,
    status = status,
    modelName = modelName,
    projectName = projectName,
    moduleName = moduleName,
    createdAt = createdAt,
    updatedAt = updatedAt
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


