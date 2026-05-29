package org.iotsplab.akiba.data.database

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.iotsplab.akiba.module.Log

/**
 * Client for Agent-related database operations.
 *
 * This object extends the existing [DatabaseClient] pattern, sending HTTP POST
 * requests to the `akiba_db_daemon`'s `/agent/*` routes.  It is the framework-side
 * counterpart of [org.iotsplab.akiba.dbDaemon.operations.AgentOps].
 *
 * All methods are synchronous (runBlocking) to match the existing [DatabaseClient] API.
 */*/
object AgentDatabaseClient {

    private val mapper get() = jacksonObjectMapper()

    // ============================================================
    //  Session CRUD
    // ============================================================

    data class SessionInfo(
        val sessionId: String,
        val sessionName: String?,
        val status: String,
        val binaryId: Int?,
        val moduleName: String?,
        val graphId: String?,
        val modelName: String?,
        val createdAt: String?,
        val updatedAt: String?,
        val resumedAt: String?,
        val completedAt: String?
    )

    /**
     * Create a new agent session.
     * @return the UUID of the newly created session
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun createSession(
        sessionName: String? = null,
        binaryId: Int? = null,
        moduleName: String? = null,
        modelName: String? = null
    ): String = runBlocking {
        val body = mapOf(
            "sessionName" to sessionName,
            "binaryId" to binaryId,
            "moduleName" to moduleName,
            "modelName" to modelName
        )
        val response = DatabaseClient.post("/agent/session/create", body)
        if (response.first == HttpStatusCode.OK)
            response.second.removeSurrounding("\"")  // UUID string
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * Get a session by ID.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun getSession(sessionId: String): SessionInfo = runBlocking {
        val response = DatabaseClient.post("/agent/session/get", mapOf("sessionId" to sessionId))
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<SessionInfo>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * List sessions with optional filters.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun listSessions(
        status: String? = null,
        binaryId: Int? = null,
        moduleName: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): List<SessionInfo> = runBlocking {
        val body = mapOf(
            "status" to status,
            "binaryId" to binaryId,
            "moduleName" to moduleName,
            "limit" to limit,
            "offset" to offset
        )
        val response = DatabaseClient.post("/agent/session/list", body)
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<List<SessionInfo>>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * Update a session's status or other fields.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun updateSession(
        sessionId: String,
        status: String? = null,
        sessionName: String? = null,
        graphId: String? = null,
        modelName: String? = null
    ) = runBlocking {
        val body = mapOf(
            "sessionId" to sessionId,
            "status" to status,
            "sessionName" to sessionName,
            "graphId" to graphId,
            "modelName" to modelName
        )
        val response = DatabaseClient.post("/agent/session/update", body)
        if (response.first != HttpStatusCode.OK)
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    // ============================================================
    //  Messages (ChatMemory backing store)
    // ============================================================

    data class MessageInfo(
        val messageId: Long,
        val messageIndex: Int,
        val role: String,
        val content: String?,
        val toolCallId: String?,
        val toolName: String?,
        val toolCallArgs: String?,
        val toolResult: String?,
        val tokenCount: Int?,
        val createdAt: String?
    )

    data class MessageData(
        val role: String,
        val content: String? = null,
        val toolCallId: String? = null,
        val toolName: String? = null,
        val toolCallArgs: String? = null,
        val toolResult: String? = null,
        val tokenCount: Int? = null
    )

    /**
     * Append one or more messages to a session.
     * @return list of generated message IDs
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun appendMessages(sessionId: String, messages: List<MessageData>): List<Long> = runBlocking {
        val body = mapOf(
            "sessionId" to sessionId,
            "messages" to messages
        )
        val response = DatabaseClient.post("/agent/message/append", body)
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<List<Long>>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * Get messages for a session with pagination.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun getMessages(sessionId: String, fromIndex: Int = 0, limit: Int = 100): List<MessageInfo> = runBlocking {
        val body = mapOf(
            "sessionId" to sessionId,
            "fromIndex" to fromIndex,
            "limit" to limit
        )
        val response = DatabaseClient.post("/agent/message/get", body)
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<List<MessageInfo>>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * Delete messages from a specific index onward (for sliding window memory).
     * @return number of deleted messages
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun deleteMessagesFrom(sessionId: String, fromIndex: Int): Int = runBlocking {
        val body = mapOf(
            "sessionId" to sessionId,
            "fromIndex" to fromIndex
        )
        val response = DatabaseClient.post("/agent/message/delete_from", body)
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<Int>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    // ============================================================
    //  Memories (cognitive layer)
    // ============================================================

    data class MemoryInfo(
        val memoryId: Long,
        val sessionId: String?,
        val binaryId: Int?,
        val memoryType: String,
        val scope: String,
        val key: String?,
        val content: String,
        val importance: Double?,
        val metadata: String?,
        val createdAt: String?
    )

    /**
     * Store a new memory.
     * @return the generated memory ID
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun storeMemory(
        sessionId: String? = null,
        binaryId: Int? = null,
        memoryType: String = "finding",
        scope: String = "session",
        key: String? = null,
        content: String,
        importance: Double? = null,
        metadata: String? = null
    ): Long = runBlocking {
        val body = mapOf(
            "sessionId" to sessionId,
            "binaryId" to binaryId,
            "memoryType" to memoryType,
            "scope" to scope,
            "key" to key,
            "content" to content,
            "importance" to importance,
            "metadata" to metadata
        )
        val response = DatabaseClient.post("/agent/memory/store", body)
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<Long>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * Query memories by various filters.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun queryMemories(
        sessionId: String? = null,
        binaryId: Int? = null,
        memoryType: String? = null,
        scope: String? = null,
        key: String? = null,
        minImportance: Double? = null,
        limit: Int = 50
    ): List<MemoryInfo> = runBlocking {
        val body = mapOf(
            "sessionId" to sessionId,
            "binaryId" to binaryId,
            "memoryType" to memoryType,
            "scope" to scope,
            "key" to key,
            "minImportance" to minImportance,
            "limit" to limit
        )
        val response = DatabaseClient.post("/agent/memory/query", body)
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<List<MemoryInfo>>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    // ============================================================
    //  Session Context
    // ============================================================

    data class ContextInfo(
        val sessionId: String,
        val environment: String?,
        val contextConfig: String?,
        val moduleData: String?,
        val updatedAt: String?
    )

    /**
     * Save or update session context.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun saveContext(
        sessionId: String,
        environment: String? = null,
        contextConfig: String? = null,
        moduleData: String? = null
    ) = runBlocking {
        val body = mapOf(
            "sessionId" to sessionId,
            "environment" to environment,
            "contextConfig" to contextConfig,
            "moduleData" to moduleData
        )
        val response = DatabaseClient.post("/agent/context/save", body)
        if (response.first != HttpStatusCode.OK)
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * Load session context.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun loadContext(sessionId: String): ContextInfo = runBlocking {
        val response = DatabaseClient.post("/agent/context/load", mapOf("sessionId" to sessionId))
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<ContextInfo>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    // ============================================================
    //  Graph Topology
    // ============================================================

    data class GraphInfo(
        val graphId: String,
        val sessionId: String,
        val graphName: String?,
        val entryNode: String?,
        val maxIterations: Int,
        val convergenceThreshold: Double,
        val cycleStrategy: String,
        val createdAt: String?
    )

    data class NodeInfo(
        val graphId: String,
        val nodeId: String,
        val nodeName: String?,
        val role: String,
        val modelName: String?,
        val systemPrompt: String?,
        val tools: String?,
        val config: String?
    )

    data class EdgeInfo(
        val graphId: String,
        val edgeId: Long,
        val sourceNode: String,
        val targetNode: String,
        val edgeType: String,
        val condition: String?,
        val priority: Int,
        val config: String?
    )

    data class FullGraph(
        val graph: GraphInfo,
        val nodes: List<NodeInfo>,
        val edges: List<EdgeInfo>
    )

    /**
     * Create a new agent graph.
     * @return the UUID of the newly created graph
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun createGraph(
        sessionId: String,
        graphName: String? = null,
        entryNode: String? = null,
        maxIterations: Int = 10,
        convergenceThreshold: Double = 0.95,
        cycleStrategy: String = "MAX_ITERATIONS"
    ): String = runBlocking {
        val body = mapOf(
            "sessionId" to sessionId,
            "graphName" to graphName,
            "entryNode" to entryNode,
            "maxIterations" to maxIterations,
            "convergenceThreshold" to convergenceThreshold,
            "cycleStrategy" to cycleStrategy
        )
        val response = DatabaseClient.post("/agent/graph/create", body)
        if (response.first == HttpStatusCode.OK)
            response.second.removeSurrounding("\"")
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * Add a node to a graph.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun addGraphNode(
        graphId: String,
        nodeId: String,
        nodeName: String? = null,
        role: String = "WORKER",
        modelName: String? = null,
        systemPrompt: String? = null,
        tools: String = "[]",
        config: String = "{}"
    ) = runBlocking {
        val body = mapOf(
            "graphId" to graphId,
            "nodeId" to nodeId,
            "nodeName" to nodeName,
            "role" to role,
            "modelName" to modelName,
            "systemPrompt" to systemPrompt,
            "tools" to tools,
            "config" to config
        )
        val response = DatabaseClient.post("/agent/graph/add_node", body)
        if (response.first != HttpStatusCode.OK)
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * Add an edge to a graph.
     * @return the generated edge ID
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun addGraphEdge(
        graphId: String,
        sourceNode: String,
        targetNode: String,
        edgeType: String = "DELEGATE",
        condition: String? = null,
        priority: Int = 0,
        config: String = "{}"
    ): Long = runBlocking {
        val body = mapOf(
            "graphId" to graphId,
            "sourceNode" to sourceNode,
            "targetNode" to targetNode,
            "edgeType" to edgeType,
            "condition" to condition,
            "priority" to priority,
            "config" to config
        )
        val response = DatabaseClient.post("/agent/graph/add_edge", body)
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<Long>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * Load the full graph definition (graph + nodes + edges).
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun loadGraph(graphId: String): FullGraph = runBlocking {
        val response = DatabaseClient.post("/agent/graph/load", mapOf("graphId" to graphId))
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<FullGraph>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    // ============================================================
    //  Graph Execution Log
    // ============================================================

    data class ExecutionInfo(
        val executionId: Long,
        val graphId: String,
        val sessionId: String,
        val iteration: Int,
        val nodeId: String,
        val inputSummary: String?,
        val outputSummary: String?,
        val status: String,
        val durationMs: Long?,
        val createdAt: String?
    )

    /**
     * Record a graph execution step.
     * @return the generated execution ID
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun recordExecution(
        graphId: String,
        sessionId: String,
        iteration: Int,
        nodeId: String,
        inputSummary: String? = null,
        outputSummary: String? = null,
        status: String = "running",
        durationMs: Long? = null
    ): Long = runBlocking {
        val body = mapOf(
            "graphId" to graphId,
            "sessionId" to sessionId,
            "iteration" to iteration,
            "nodeId" to nodeId,
            "inputSummary" to inputSummary,
            "outputSummary" to outputSummary,
            "status" to status,
            "durationMs" to durationMs
        )
        val response = DatabaseClient.post("/agent/execution/record", body)
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<Long>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * Get execution log for a session.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun getExecutions(
        sessionId: String,
        graphId: String? = null,
        limit: Int = 100
    ): List<ExecutionInfo> = runBlocking {
        val body = mapOf(
            "sessionId" to sessionId,
            "graphId" to graphId,
            "limit" to limit
        )
        val response = DatabaseClient.post("/agent/execution/get", body)
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<List<ExecutionInfo>>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    // ============================================================
    //  Human-in-the-Loop
    // ============================================================

    data class HumanInputInfo(
        val inputId: Long,
        val sessionId: String,
        val requestText: String,
        val responseText: String?,
        val status: String,
        val createdAt: String?,
        val answeredAt: String?,
        val expiresAt: String?
    )

    /**
     * Create a human input request.
     * @return the generated input ID
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun requestHumanInput(
        sessionId: String,
        requestText: String,
        expiresAt: String? = null
    ): Long = runBlocking {
        val body = mapOf(
            "sessionId" to sessionId,
            "requestText" to requestText,
            "expiresAt" to expiresAt
        )
        val response = DatabaseClient.post("/agent/human/request", body)
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<Long>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * Respond to a human input request.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun respondHumanInput(inputId: Long, responseText: String, status: String = "answered") = runBlocking {
        val body = mapOf(
            "inputId" to inputId,
            "responseText" to responseText,
            "status" to status
        )
        val response = DatabaseClient.post("/agent/human/respond", body)
        if (response.first != HttpStatusCode.OK)
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * Poll pending human input requests for a session.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun pollHumanInputs(sessionId: String, includeAnswered: Boolean = false): List<HumanInputInfo> = runBlocking {
        val body = mapOf(
            "sessionId" to sessionId,
            "includeAnswered" to includeAnswered
        )
        val response = DatabaseClient.post("/agent/human/poll", body)
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<List<HumanInputInfo>>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    // ============================================================
    //  Scripts & Script Executions (per-instance)
    // ============================================================

    data class ScriptInfo(
        val id: Int,
        val name: String,
        val description: String?,
        val code: String?,
        val codeSize: Int?,
        val language: String?,
        val output: String?,
        val outputSize: Int?,
        val status: String?,
        val saveResult: Boolean?,
        val maxOutputSize: Long?,
        val createdAt: String?,
        val finishedAt: String?
    )

    data class ScriptExecutionInfo(
        val id: Int,
        val scriptId: Int,
        val binaryId: Int?,
        val status: String?,
        val output: String?,
        val errorMessage: String?,
        val startedAt: String?,
        val finishedAt: String?
    )

    /**
     * Create a new script record in the current per-instance DB.
     * @return the generated script ID
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun createScript(
        name: String,
        description: String = "",
        code: String,
        language: String = "kotlin",
        saveResult: Boolean = true,
        maxOutputSize: Long = 10485760
    ): Int = runBlocking {
        val body = mapOf(
            "name" to name,
            "description" to description,
            "code" to code,
            "language" to language,
            "saveResult" to saveResult,
            "maxOutputSize" to maxOutputSize
        )
        val response = DatabaseClient.post("/script/create", body)
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<Int>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * Get a script by ID.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun getScript(scriptId: Int): ScriptInfo = runBlocking {
        val response = DatabaseClient.post("/script/get", mapOf("scriptId" to scriptId))
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<ScriptInfo>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * List scripts in the current per-instance DB.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun listScripts(limit: Int = 100, offset: Int = 0): List<ScriptInfo> = runBlocking {
        val body = mapOf("limit" to limit, "offset" to offset)
        val response = DatabaseClient.post("/script/list", body)
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<List<ScriptInfo>>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * Update a script's metadata or code.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun updateScript(
        scriptId: Int,
        name: String? = null,
        description: String? = null,
        code: String? = null,
        language: String? = null,
        saveResult: Boolean? = null,
        maxOutputSize: Long? = null
    ) = runBlocking {
        val body = mapOf(
            "scriptId" to scriptId,
            "name" to name,
            "description" to description,
            "code" to code,
            "language" to language,
            "saveResult" to saveResult,
            "maxOutputSize" to maxOutputSize
        )
        val response = DatabaseClient.post("/script/update", body)
        if (response.first != HttpStatusCode.OK)
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * Update a script's output after execution.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun updateScriptOutput(
        scriptId: Int,
        output: String? = null,
        status: String,
        maxOutputSize: Long? = null
    ) = runBlocking {
        val body = mapOf(
            "scriptId" to scriptId,
            "output" to output,
            "status" to status,
            "maxOutputSize" to maxOutputSize
        )
        val response = DatabaseClient.post("/script/update_output", body)
        if (response.first != HttpStatusCode.OK)
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * Delete a script by ID.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun deleteScript(scriptId: Int) = runBlocking {
        val response = DatabaseClient.post("/script/delete", mapOf("scriptId" to scriptId))
        if (response.first != HttpStatusCode.OK)
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * Create a script execution record.
     * @return the generated execution ID
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun createScriptExecution(
        scriptId: Int,
        binaryId: Int? = null
    ): Int = runBlocking {
        val body = mapOf("scriptId" to scriptId, "binaryId" to binaryId)
        val response = DatabaseClient.post("/script/execution/create", body)
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<Int>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * Get a script execution by ID.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun getScriptExecution(executionId: Int): ScriptExecutionInfo = runBlocking {
        val response = DatabaseClient.post("/script/execution/get", mapOf("executionId" to executionId))
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<ScriptExecutionInfo>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * List executions for a script.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun listScriptExecutions(scriptId: Int): List<ScriptExecutionInfo> = runBlocking {
        val response = DatabaseClient.post("/script/execution/list", mapOf("scriptId" to scriptId))
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<List<ScriptExecutionInfo>>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * Update a script execution's output and status.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun updateScriptExecution(
        executionId: Int,
        output: String? = null,
        status: String,
        errorMessage: String? = null
    ) = runBlocking {
        val body = mapOf(
            "executionId" to executionId,
            "output" to output,
            "status" to status,
            "errorMessage" to errorMessage
        )
        val response = DatabaseClient.post("/script/execution/update", body)
        if (response.first != HttpStatusCode.OK)
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    // ============================================================
    //  Tool Call Audit
    // ============================================================

    /**
     * Record a tool call for auditing.
     * @return the generated call ID
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun recordToolCall(
        sessionId: String,
        messageId: Long? = null,
        nodeId: String? = null,
        toolName: String,
        toolArgs: String? = null,
        resultSummary: String? = null,
        success: Boolean = true,
        durationMs: Long? = null
    ): Long = runBlocking {
        val body = mapOf(
            "sessionId" to sessionId,
            "messageId" to messageId,
            "nodeId" to nodeId,
            "toolName" to toolName,
            "toolArgs" to toolArgs,
            "resultSummary" to resultSummary,
            "success" to success,
            "durationMs" to durationMs
        )
        val response = DatabaseClient.post("/agent/tool_call/record", body)
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<Long>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }
}
