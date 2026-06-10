package org.iotsplab.akiba.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.data.database.AgentDatabaseClient.SessionInfo
import org.iotsplab.akiba.data.database.DatabaseClient
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

// ============================================================
//  Request / Response DTOs
// ============================================================

data class CreateAgentSessionRequest(
    val sessionName: String? = null,
    val systemPrompt: String? = null,
    val modelName: String? = null,
    val binaryId: Int? = null
)

data class AgentSessionResponse(
    val sessionId: String,
    val sessionName: String?,
    val status: String,
    val modelName: String?,
    val createdAt: String?,
    val updatedAt: String?
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
    id: Int = -1
) : AgentModule(
    id = id,
    program = program,
    dbClient = dbClient
) {
    override fun taskPrompt(): String = ""
    override fun defineTools(): List<Tool> = emptyList()
    override fun includeBuiltInTools(): Boolean = true
}

private const val DEFAULT_AGENT_SYSTEM_PROMPT =
    "You are Akiba's interactive assistant. Help the user reason about " +
    "binary analysis, scripting, and the Akiba platform. Answer concisely " +
    "and ask clarifying questions when needed."

// ============================================================
//  Routes
// ============================================================

fun Route.agentRoutes(daemonHost: String, daemonPort: Int) {

    // ------ List sessions -----------------------------------------------------
    get("/agent/sessions") {
        val instance = call.requireInstanceHeader() ?: return@get
        val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
        val offset = call.parameters["offset"]?.toIntOrNull() ?: 0
        try {
            val sessions = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                agentDbClient.listSessions(
                    moduleName = "chat", limit = limit, offset = offset
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
        try {
            val info = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                val sessionId = agentDbClient.createSession(
                    sessionName = req.sessionName ?: "Chat ${System.currentTimeMillis()}",
                    binaryId = req.binaryId,
                    moduleName = "chat",
                    modelName = req.modelName
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
            val msgs = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                agentDbClient.getMessages(id, fromIndex, limit)
            }
            val filtered = msgs.filter { it.role != "system" }
            call.respond(mapOf("messages" to filtered.map { it.toMessageResponse() }))
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

        // Verify the session exists.
        try {
            withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                agentDbClient.getSession(id)
            }
        } catch (e: DatabaseClient.DatabaseDaemonException) {
            if (e.statusCode == HttpStatusCode.NotFound) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
                return@post
            }
            val (status, body) = errorPayload(e)
            call.respond(status, body)
            return@post
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
            return@post
        }

        val systemPrompt = req.systemPrompt?.takeIf { it.isNotBlank() }
            ?: DEFAULT_AGENT_SYSTEM_PROMPT

        try {
            val (result, msgs) = withContext(Dispatchers.Default) {
                withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                    val agentDbClient = AgentDatabaseClient(dbClient)
                    val sessionInfo = agentDbClient.getSession(id)
                    // Resolve LLM config from the saved key store using the
                    // session's modelName (set by the frontend's LLM selector).
                    val llmConfig = sessionInfo.modelName?.let { modelName ->
                        LLMKeyFileStore.load().firstOrNull { entry ->
                            entry.modelNames.any { it.equals(modelName, ignoreCase = true) }
                        }
                    }
                    // Create a minimal AgentModule for the session's binary (if any)
                    // so BuiltInTools has access to agentDbClient, dbClient, and program.
                    val chatModule = ChatAgentModule(
                        dbClient = dbClient,
                        id = sessionInfo.binaryId ?: -1
                        // program = … would require loading from Ghidra in a subprocess
                    )
                    // Register all built-in tools via the module
                    val toolList = BuiltInTools.all(chatModule, agentDbClient).filter { it.name != "run_shell" }

                    val agent = akibaAgent {
                        if (llmConfig != null) {
                            val provider = LLMProvider.fromString(llmConfig.provider)
                                ?: throw IllegalStateException("Unknown LLM provider: ${llmConfig.provider}")
                            config(LLMConfig(
                                provider = provider,
                                modelName = llmConfig.modelNames.first(),
                                apiKey = llmConfig.apiKey,
                                baseUrl = llmConfig.baseUrl
                            ))
                        } else {
                            fromRuntimeOrGlobalConfig()
                        }
                        tools(toolList)
                        system(systemPrompt)
                        session(id)
                        memory(persistentChatMemory(agentDbClient, id))
                        enrichSystemPrompt(true)
                        auditToolCalls(true)
                        maxIterations(20)
                    }
                    val r = agent.run(req.content)
                    val all = agentDbClient.getMessages(id, 0, 1000)
                        .filter { it.role != "system" }
                    r to all
                }
            }

            val userMsg = msgs.lastOrNull { it.role == "user" }
                ?: throw IllegalStateException("User message was not persisted")
            val asstMsg = msgs.lastOrNull { it.role == "assistant" }
                ?: throw IllegalStateException("Assistant message was not persisted")

            call.respond(ChatResponse(
                sessionId = id,
                userMessage = userMsg.toMessageResponse(),
                assistantMessage = AgentMessageResponse(
                    messageId = asstMsg.messageId,
                    messageIndex = asstMsg.messageIndex,
                    role = asstMsg.role,
                    content = asstMsg.content ?: result.output,
                    createdAt = asstMsg.createdAt
                ),
                iterations = result.iterations,
                tokenUsage = mapOf(
                    "input" to result.totalInputTokens,
                    "output" to result.totalOutputTokens
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
            val (sessionInfo, msgs) = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                val info = agentDbClient.getSession(id)
                val messages = agentDbClient.getMessages(id, 0, 500)
                    .filter { it.role != "system" }
                info to messages
            }
            val md = buildString {
                appendLine("# Agent Session: ${sessionInfo.sessionName ?: "Untitled"}")
                appendLine()
                appendLine("- **Session ID:** `$id`")
                appendLine("- **Status:** ${sessionInfo.status}")
                sessionInfo.modelName?.let { appendLine("- **Model:** $it") }
                sessionInfo.binaryId?.let { appendLine("- **Binary ID:** $it") }
                appendLine("- **Created:** ${sessionInfo.createdAt ?: "?"}")
                appendLine("- **Updated:** ${sessionInfo.updatedAt ?: "?"}")
                appendLine()
                appendLine("---")
                appendLine()
                for (msg in msgs) {
                    when (msg.role) {
                        "user" -> {
                            appendLine("## 👤 User")
                            appendLine()
                            appendLine(msg.content ?: "")
                            appendLine()
                        }
                        "assistant" -> {
                            appendLine("## 🤖 Akiba")
                            appendLine()
                            appendLine(msg.content ?: "")
                            appendLine()
                        }
                        "tool" -> {
                            val toolName = msg.toolName ?: "tool"
                            appendLine("### 🛠 `$toolName`")
                            val result = msg.toolResult ?: msg.content ?: ""
                            if (result.isNotBlank()) {
                                appendLine("```")
                                appendLine(result.take(2000)) // truncate very long results
                                appendLine("```")
                            }
                            appendLine()
                        }
                    }
                }
                appendLine("---")
                appendLine()
                appendLine("*Exported from Akiba*")
            }
            call.response.header(
                HttpHeaders.ContentDisposition,
                "attachment; filename=\"session_${id.take(8)}.md\""
            )
            call.respondText(md, ContentType.Text.Plain)
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }
}

private fun SessionInfo.toResponse() = AgentSessionResponse(
    sessionId = sessionId,
    sessionName = sessionName,
    status = status,
    modelName = modelName,
    createdAt = createdAt,
    updatedAt = updatedAt
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
