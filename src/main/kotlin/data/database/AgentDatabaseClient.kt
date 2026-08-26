package org.iotsplab.akiba.data.database

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.iotsplab.akiba.module.Log

/**
 * Client for Agent-related database operations.
 *
 * This class extends the existing [DatabaseClient] pattern, sending HTTP POST
 * requests to the `akiba_db_daemon`'s `/agent/ *` routes.  It is the framework-side
 * counterpart of [org.iotsplab.akiba.dbDaemon.operations.AgentOps].
 *
 * Each instance is bound to a single [DatabaseClient] and therefore to a single
 * daemon session (host/port + auth token). This makes it safe for concurrent
 * multi-tenant use when the surrounding code creates one
 * [AgentDatabaseClient] per request/session.
 *
 * All methods are synchronous (runBlocking) to match the existing [DatabaseClient] API.
 *
 * @param dbClient The underlying [DatabaseClient] used to issue HTTP requests.
 */
class AgentDatabaseClient(private val dbClient: DatabaseClient) {

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
        val projectName: String? = null,
        /** Workflow run that spawned this session (null for interactive sessions). */
        val workflowId: String? = null,
        val createdAt: String?,
        val updatedAt: String?,
        val resumedAt: String?,
        val completedAt: String?,
        val transcript: String? = null,
        val parentSessionId: String? = null,
        val lifecycle: String? = null,
        val runtimeState: String? = null,
        val closingReason: String? = null,
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
        modelName: String? = null,
        projectName: String? = null,
        /**
         * Workflow run that spawned this session (the daemon propagates
         * it to child sessions automatically).  Null for interactive
         * sessions created from the web UI.
         */
        workflowId: String? = null,
        /**
         * Optional parent session id. Set when spawning a child agent
         * (e.g. via `spawn_sub_agent`) so the frontend can group them
         * into a parent/child tree.
         */
        parentSessionId: String? = null
    ): String = runBlocking {
        val body = mutableMapOf<String, Any?>(
            "sessionName" to sessionName,
            "binaryId" to binaryId,
            "moduleName" to moduleName,
            "modelName" to modelName,
            "projectName" to projectName
        )
        if (workflowId != null) body["workflowId"] = workflowId
        if (parentSessionId != null) body["parentSessionId"] = parentSessionId
        val response = dbClient.post("/agent/session/create", body)
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
        val response = dbClient.post("/agent/session/get", mapOf("sessionId" to sessionId))
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<SessionInfo>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * List sessions with optional filters.
     *
     * @param parentSessionId
     *   - `null` (default): only top-level sessions (parent_session_id IS NULL).
     *     Sub-agents spawned by `spawn_sub_agent` are hidden by default.
     *   - a specific UUID: only direct children of that parent.
     *   - the literal string "ALL" (or any non-UUID string): return every
     *     session regardless of parent (used by advanced views / debugging).
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun listSessions(
        status: String? = null,
        binaryId: Int? = null,
        moduleName: String? = null,
        workflowId: String? = null,
        limit: Int = 50,
        offset: Int = 0,
        parentSessionId: String? = null
    ): List<SessionInfo> = runBlocking {
        val body = mutableMapOf<String, Any?>(
            "status" to status,
            "binaryId" to binaryId,
            "moduleName" to moduleName,
            "workflowId" to workflowId,
            "limit" to limit,
            "offset" to offset
        )
        if (parentSessionId != null) body["parentSessionId"] = parentSessionId
        val response = dbClient.post("/agent/session/list", body)
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<List<SessionInfo>>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * Search top-level sessions by title and message content.  A root
     * session matches when any session in its subtree has a matching
     * `session_name` or any matching message body.  See the daemon's
     * `AgentOps.SearchSessions` for the exact semantics.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun searchSessions(query: String, limit: Int = 50): List<SessionInfo> = runBlocking {
        val response = dbClient.post("/agent/session/search", mapOf(
            "query" to query,
            "limit" to limit
        ))
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<List<SessionInfo>>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * List direct children of [parentSessionId] in chronological order.
     * Used by the frontend to walk the parent → child tree.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun getSessionChildren(parentSessionId: String): List<SessionInfo> = runBlocking {
        val body = mapOf("parentSessionId" to parentSessionId)
        val response = dbClient.post("/agent/session/children", body)
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
        modelName: String? = null,
        transcript: String? = null
    ) = runBlocking {
        val body = mapOf(
            "sessionId" to sessionId,
            "status" to status,
            "sessionName" to sessionName,
            "graphId" to graphId,
            "modelName" to modelName,
            "transcript" to transcript
        )
        val response = dbClient.post("/agent/session/update", body)
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
        val inputTokenCount: Int? = null,
        val createdAt: String?
    )

    data class MessageData(
        val role: String,
        val content: String? = null,
        val toolCallId: String? = null,
        val toolName: String? = null,
        val toolCallArgs: String? = null,
        val toolResult: String? = null,
        val tokenCount: Int? = null,
        val inputTokenCount: Int? = null
    )

    /**
     * Append one or more messages to a session.
     *
     * @return the message_index values the daemon assigned to the
     *   appended rows, in request order.  PersistentChatMemory stamps
     *   its buffer with these so later index-based operations (e.g.
     *   removeLast) hit the correct DB rows regardless of any rows
     *   written outside the chat-memory track.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun appendMessages(sessionId: String, messages: List<MessageData>): List<Int> = runBlocking {
        val body = mapOf(
            "sessionId" to sessionId,
            "messages" to messages
        )
        val response = dbClient.post("/agent/message/append", body)
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<List<Int>>(response.second)
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
        val response = dbClient.post("/agent/message/get", body)
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
        val response = dbClient.post("/agent/message/delete_from", body)
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
        val response = dbClient.post("/agent/memory/store", body)
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
        val response = dbClient.post("/agent/memory/query", body)
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
        val response = dbClient.post("/agent/context/save", body)
        if (response.first != HttpStatusCode.OK)
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * Load session context.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun loadContext(sessionId: String): ContextInfo = runBlocking {
        val response = dbClient.post("/agent/context/load", mapOf("sessionId" to sessionId))
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<ContextInfo>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    // ============================================================
    //  Transcript Append
    // ============================================================

    /**
     * Append Markdown content to a session's transcript field.
     * Content is concatenated to any existing transcript text.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun appendTranscript(sessionId: String, content: String) = runBlocking {
        val body = mapOf("sessionId" to sessionId, "content" to content)
        val response = dbClient.post("/agent/transcript/append", body)
        if (response.first != HttpStatusCode.OK)
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    // ============================================================
    //  Session Lifecycle
    // ============================================================

    /**
     * Update a session's `lifecycle` field. The orchestrator decides
     * when a session becomes standby (typically after its one-shot
     * task completes).
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun setSessionLifecycle(sessionId: String, lifecycle: String) = runBlocking {
        require(lifecycle in setOf("one_shot", "standby")) {
            "lifecycle must be one_shot or standby, got '$lifecycle'"
        }
        val body = mapOf("sessionId" to sessionId, "lifecycle" to lifecycle)
        val response = dbClient.post("/agent/session/set_lifecycle", body)
        if (response.first != HttpStatusCode.OK)
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    // ============================================================
    //  Runtime State
    // ============================================================

    /**
     * Snapshot of a session's runtime state. The [runtimeState] field
     * is one of `running / standby / msghandle / cancelling / closed`;
     * the daemon returns the raw string and the caller is expected to
     * map it through [org.iotsplab.akiba.llm.agent.RuntimeState.fromWire].
     */
    data class RuntimeStateInfo(
        val sessionId: String,
        val runtimeState: String,
        val closingReason: String?,
        val lifecycle: String?,
        val status: String?,
        val parentSessionId: String? = null,
    )

    /**
     * Update the session's `runtime_state` and optional `closing_reason`.
     * Caller is responsible for ensuring the transition is legal
     * (see [org.iotsplab.akiba.llm.agent.RuntimeState.canTransition]);
     * the daemon merely mirrors the new value.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun setRuntimeState(
        sessionId: String,
        runtimeState: String,
        closingReason: String? = null,
    ) = runBlocking {
        require(runtimeState in setOf("running", "standby", "msghandle", "cancelling", "closed", "error")) {
            "runtimeState must be one of running|standby|msghandle|cancelling|closed|error, got '$runtimeState'"
        }
        val body = mutableMapOf<String, Any?>(
            "sessionId" to sessionId,
            "runtimeState" to runtimeState,
        )
        if (closingReason != null) body["closingReason"] = closingReason
        val response = dbClient.post("/agent/session/set_runtime_state", body)
        if (response.first != HttpStatusCode.OK)
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * Hard-delete a session and all of its descendants from the database.
     *
     * The caller MUST ensure the session (and all children) are in a
     * terminal state before calling this.  Dependent rows (messages,
     * tool calls, graphs, etc.) are removed via ON DELETE CASCADE.
     *
     * @return number of sessions deleted (root + descendants).
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun deleteSession(sessionId: String): Int = runBlocking {
        val response = dbClient.post("/agent/session/delete", mapOf("sessionId" to sessionId))
        if (response.first == HttpStatusCode.OK) {
            val node = mapper.readTree(response.second)
            node.path("deleted").asInt(0)
        } else {
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
        }
    }

    /**
     * Hard-delete every session TREE rooted at a session whose
     * `project_name` matches [projectName].  Child sessions do not
     * inherit the parent's `project_name` (their column is NULL),
     * so the daemon walks `parent_session_id` recursively from
     * every project-matching root and deletes the whole tree.
     * Dependent rows (messages, tool calls, graphs, etc.) are
     * removed via ON DELETE CASCADE.
     *
     * Used by the Projects page "delete project" flow when the user
     * opts into removing the project's agent sessions as well.
     *
     * @return number of sessions deleted (roots + descendants).
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun deleteSessionsByProject(projectName: String): Int = runBlocking {
        val response = dbClient.post("/agent/session/delete-by-project", mapOf("projectName" to projectName))
        if (response.first == HttpStatusCode.OK) {
            val node = mapper.readTree(response.second)
            node.path("deleted").asInt(0)
        } else {
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
        }
    }

    /**
     * Fetch the current `runtime_state` and `closing_reason`. Returns
     * null when the session does not exist. Used by the dispatcher
     * pre-flight and by JobHandle.await to seed the local state cache.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun getRuntimeState(sessionId: String): RuntimeStateInfo? = runBlocking {
        val response = dbClient.post(
            "/agent/session/get_runtime_state",
            mapOf("sessionId" to sessionId),
        )
        when {
            response.first == HttpStatusCode.OK ->
                mapper.readValue<RuntimeStateInfo>(response.second)
            response.first == HttpStatusCode.NotFound -> null
            else -> throw DatabaseClient.DatabaseDaemonException(
                response.first, response.first.description
            )
        }
    }

    /**
     * Walk the descendant tree of [rootSessionId] and return every
     * session that is still live (`runtime_state != 'closed'`).
     * One SQL round-trip via a recursive CTE. Used by cascade
     * cancel and OrphanReaper.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun listLiveSubtree(rootSessionId: String, includeClosed: Boolean = false): List<RuntimeStateInfo> =
        runBlocking {
            val body = mapOf(
                "rootSessionId" to rootSessionId,
                "includeClosed" to includeClosed,
            )
            val response = dbClient.post("/agent/session/list_live_subtree", body)
            if (response.first == HttpStatusCode.OK)
                mapper.readValue<List<RuntimeStateInfo>>(response.second)
            else
                throw DatabaseClient.DatabaseDaemonException(
                    response.first, response.first.description
                )
        }

    // ============================================================
    //  Agent status snapshot (used by get_agent_status tool)
    // ============================================================

    /**
     * Relationship between the calling session and the target
     * session.  Auto-detected by the daemon from
     * `parent_session_id` so the caller never has to declare
     * it.  See
     * [org.iotsplab.akiba.dbDaemon.operations.AgentOps.GetAgentStatus]
     * for the detection rules.
     *
     * The current default policy admits [SELF] and [DIRECT_CHILD]
     * only; [DIRECT_PARENT] / [SIBLING] / [OTHER] are detected
     * (so the LLM can see WHY access was denied) but currently
     * rejected.  Adding more admitted relationships will
     * require an explicit permission model.
     */
    enum class AgentRelationship {
        /** target == caller. */
        SELF,
        /** target.parent_session_id == caller. */
        DIRECT_CHILD,
        /** caller.parent_session_id == target. */
        DIRECT_PARENT,
        /** target.parent_session_id == caller.parent_session_id (and not self). */
        SIBLING,
        /** Any other relationship (grandchild, uncle, unrelated, ...). */
        OTHER,
        ;

        /** Wire value used in the request / response. */
        fun wire(): String = name.lowercase()

        companion object {
            fun fromWire(raw: String?): AgentRelationship? = when (raw?.lowercase()) {
                "self" -> SELF
                "direct_child" -> DIRECT_CHILD
                "direct_parent" -> DIRECT_PARENT
                "sibling" -> SIBLING
                "other" -> OTHER
                else -> null
            }
        }
    }

    /**
     * Structured response of a `getAgentStatus` call.  The
     * `directChildren` list is the list of `agent_sessions` rows
     * whose `parent_session_id` equals the target's `session_id`,
     * capped by [childLimit] (default 64, hard max 256); when the
     * cap is hit [directChildrenTruncated] is true.
     */
    data class AgentStatusInfo(
        val targetSessionId: String,
        val targetRuntimeState: String,
        val targetLifecycle: String?,
        val targetParentSessionId: String?,
        val targetBinaryId: Int?,
        val targetModuleName: String?,
        val targetModelName: String?,
        val targetClosingReason: String?,
        val targetCreatedAt: String?,
        val targetUpdatedAt: String?,
        val targetCompletedAt: String?,
        val lastMessageAt: String?,
        val totalInputTokens: Long,
        val totalOutputTokens: Long,
        val totalToolCalls: Long,
        val childCount: Long,
        val runningChildCount: Long,
        val directChildren: List<ChildSummary>,
        val directChildrenTruncated: Boolean,
        /** Auto-detected relationship between caller and target. */
        val relationship: AgentRelationship,
    )

    /**
     * Slim child-session row returned inside
     * [AgentStatusInfo.directChildren].  Mirrors the columns
     * the daemon returns in its `directChildren` array; kept as
     * its own data class so the tool response stays narrow.
     */
    data class ChildSummary(
        val sessionId: String,
        val sessionName: String?,
        val runtimeState: String,
        val lifecycle: String?,
        val parentSessionId: String?,
        val binaryId: Int?,
        val moduleName: String?,
        val modelName: String?,
        val createdAt: String?,
        val updatedAt: String?,
    )

    /**
     * Snapshot of a single agent session's runtime state plus the
     * aggregated counters the calling agent needs.  See
     * [org.iotsplab.akiba.dbDaemon.operations.AgentOps.GetAgentStatus]
     * for the wire format.
     *
     * The relationship between [callerSessionId] and
     * [targetSessionId] is auto-detected server-side from
     * `parent_session_id`; the framework's [getAgentStatus] client
     * does not expose a `scope` knob.  The default policy admits
     * `self` and `direct_child` reads only; the daemon returns
     * a structured `forbidden` payload (wrapped in
     * [AgentStatusResult.AccessDenied]) for every other
     * relationship, and [AgentStatusResult.Ok.relationship] tells
     * the caller WHY the response was admitted (so the LLM can
     * tell "this is my own status" from "this is my direct
     * child's status" without parsing the UUID).
     *
     * @param callerSessionId the session id of the agent performing the query.
     * @param targetSessionId the session id of the agent to inspect.
     * @param childLimit      hard cap on the number of direct children
     *                        included in the response (default 64, max 256).
     * @return parsed snapshot; callers should switch on the
     *         [AgentStatusResult] sealed-class branches to pick
     *         the right LLM-facing wording.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun getAgentStatus(
        callerSessionId: String,
        targetSessionId: String,
        childLimit: Int = 64,
    ): AgentStatusResult = runBlocking {
        val body = mapOf(
            "callerSessionId" to callerSessionId,
            "targetSessionId" to targetSessionId,
            "childLimit" to childLimit.coerceIn(1, 256),
        )
        val response = dbClient.post("/agent/session/agent_status", body)
        when (response.first) {
            HttpStatusCode.OK -> {
                val node = mapper.readTree(response.second)
                val status = node["status"]?.asText()
                if (status != "ok") {
                    // Should not happen — daemon only returns 200 for
                    // "ok", but be defensive.
                    return@runBlocking AgentStatusResult.NotFound(
                        "Daemon returned 200 but status='$status'",
                    )
                }
                val target = node["target"]
                val childrenNode = node["directChildren"]
                val children = if (childrenNode != null && childrenNode.isArray) {
                    childrenNode.map { c ->
                        ChildSummary(
                            sessionId = c["sessionId"]?.asText() ?: "",
                            sessionName = c["sessionName"]?.takeIf { !it.isNull }?.asText(),
                            runtimeState = c["runtimeState"]?.asText() ?: "unknown",
                            lifecycle = c["lifecycle"]?.takeIf { !it.isNull }?.asText(),
                            parentSessionId = c["parentSessionId"]?.takeIf { !it.isNull }?.asText(),
                            binaryId = c["binaryId"]?.takeIf { !it.isNull }?.asInt(),
                            moduleName = c["moduleName"]?.takeIf { !it.isNull }?.asText(),
                            modelName = c["modelName"]?.takeIf { !it.isNull }?.asText(),
                            createdAt = c["createdAt"]?.takeIf { !it.isNull }?.asText(),
                            updatedAt = c["updatedAt"]?.takeIf { !it.isNull }?.asText(),
                        )
                    }
                } else emptyList()
                val relationship = AgentRelationship.fromWire(
                    node["relationship"]?.asText()
                ) ?: AgentRelationship.OTHER
                AgentStatusResult.Ok(
                    AgentStatusInfo(
                        targetSessionId = target["sessionId"]?.asText() ?: targetSessionId,
                        targetRuntimeState = target["runtimeState"]?.asText() ?: "unknown",
                        targetLifecycle = target["lifecycle"]?.takeIf { !it.isNull }?.asText(),
                        targetParentSessionId = target["parentSessionId"]?.takeIf { !it.isNull }?.asText(),
                        targetBinaryId = target["binaryId"]?.takeIf { !it.isNull }?.asInt(),
                        targetModuleName = target["moduleName"]?.takeIf { !it.isNull }?.asText(),
                        targetModelName = target["modelName"]?.takeIf { !it.isNull }?.asText(),
                        targetClosingReason = target["closingReason"]?.takeIf { !it.isNull }?.asText(),
                        targetCreatedAt = target["createdAt"]?.takeIf { !it.isNull }?.asText(),
                        targetUpdatedAt = target["updatedAt"]?.takeIf { !it.isNull }?.asText(),
                        targetCompletedAt = target["completedAt"]?.takeIf { !it.isNull }?.asText(),
                        lastMessageAt = target["lastMessageAt"]?.takeIf { !it.isNull }?.asText(),
                        totalInputTokens = target["totalInputTokens"]?.asLong() ?: 0L,
                        totalOutputTokens = target["totalOutputTokens"]?.asLong() ?: 0L,
                        totalToolCalls = target["totalToolCalls"]?.asLong() ?: 0L,
                        childCount = target["childCount"]?.asLong() ?: 0L,
                        runningChildCount = target["runningChildCount"]?.asLong() ?: 0L,
                        directChildren = children,
                        directChildrenTruncated = node["directChildrenTruncated"]?.asBoolean() ?: false,
                        relationship = relationship,
                    )
                )
            }
            HttpStatusCode.NotFound -> AgentStatusResult.NotFound(
                response.second.take(500),
            )
            HttpStatusCode.Forbidden -> {
                val node = mapper.readTree(response.second)
                AgentStatusResult.AccessDenied(
                    error = node["error"]?.asText() ?: "forbidden",
                    relationship = AgentRelationship.fromWire(
                        node["relationship"]?.asText()
                    ) ?: AgentRelationship.OTHER,
                    hint = node["hint"]?.asText(),
                )
            }
            HttpStatusCode.BadRequest -> {
                val node = mapper.readTree(response.second)
                AgentStatusResult.BadRequest(
                    error = node["error"]?.asText() ?: response.second,
                )
            }
            else -> throw DatabaseClient.DatabaseDaemonException(
                response.first, response.first.description
            )
        }
    }

    /**
     * Tagged union returned by [getAgentStatus].  The
     * `get_agent_status` tool branches on this to pick the
     * right LLM-facing wording:
     *
     *   * [Ok]            — return the snapshot.
     *   * [NotFound]      — surface as a structured "not found" error.
     *   * [AccessDenied]  — surface the daemon's hint so the LLM
     *                        knows the relationship was detected
     *                        but access is not granted yet.
     *   * [BadRequest]    — surface as a validation error.
     */
    sealed class AgentStatusResult {
        data class Ok(val info: AgentStatusInfo) : AgentStatusResult()
        data class NotFound(val detail: String) : AgentStatusResult()
        data class AccessDenied(
            val error: String,
            val relationship: AgentRelationship,
            val hint: String?,
        ) : AgentStatusResult()
        data class BadRequest(val error: String) : AgentStatusResult()
    }

    // ============================================================
    //  Mailbox
    // ============================================================

    data class MailboxMessageInfo(
        val messageId: Long,
        val senderSessionId: String,
        val recipientSessionId: String,
        val kind: String,
        val subject: String?,
        val body: String,
        val relatedArtifactId: Long?,
        val inReplyToMessageId: Long?,
        val priority: Int,
        val readAt: String?,
        val ackedAt: String?,
        val createdAt: String?,
    )

    private fun mapMailbox(node: JsonNode): MailboxMessageInfo = MailboxMessageInfo(
        messageId = node["messageId"].asLong(),
        senderSessionId = node["senderSessionId"].asText(),
        recipientSessionId = node["recipientSessionId"].asText(),
        kind = node["kind"].asText(),
        subject = node["subject"]?.takeIf { !it.isNull }?.asText(),
        body = node["body"].asText(),
        relatedArtifactId = node["relatedArtifactId"]?.takeIf { !it.isNull }?.asLong(),
        inReplyToMessageId = node["inReplyToMessageId"]?.takeIf { !it.isNull }?.asLong(),
        priority = node["priority"].asInt(),
        readAt = node["readAt"]?.takeIf { !it.isNull }?.asText(),
        ackedAt = node["ackedAt"]?.takeIf { !it.isNull }?.asText(),
        createdAt = node["createdAt"]?.takeIf { !it.isNull }?.asText(),
    )

    /**
     * Send a mailbox message. The framework-layer access policy
     * (`InteractionPolicy` + lifecycle rules) MUST be enforced before
     * calling; the daemon route re-checks the lifecycle invariant as
     * defense-in-depth. @return the new message id.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun sendMailboxMessage(
        senderSessionId: String,
        recipientSessionId: String,
        kind: String = "note",
        subject: String? = null,
        body: String,
        relatedArtifactId: Long? = null,
        inReplyToMessageId: Long? = null,
        priority: Int = 0,
    ): Long = runBlocking {
        val body = mapOf(
            "senderSessionId" to senderSessionId,
            "recipientSessionId" to recipientSessionId,
            "kind" to kind,
            "subject" to subject,
            "body" to body,
            "relatedArtifactId" to relatedArtifactId,
            "inReplyToMessageId" to inReplyToMessageId,
            "priority" to priority,
        )
        val response = dbClient.post("/agent/mailbox/send", body)
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<Long>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /** Peek incoming messages without marking them read. */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun listMailboxMessages(
        sessionId: String,
        limit: Int = 50,
        includeRead: Boolean = false,
        /** When true, return only read-but-unacked messages (the
         *  "pending" set).  Takes precedence over [includeRead]. */
        onlyPending: Boolean = false,
    ): List<MailboxMessageInfo> = runBlocking {
        val body = mapOf(
            "sessionId" to sessionId,
            "limit" to limit,
            "includeRead" to includeRead,
            "onlyPending" to onlyPending,
        )
        val response = dbClient.post("/agent/mailbox/list", body)
        if (response.first == HttpStatusCode.OK) {
            val arr = mapper.readTree(response.second)
            arr.map { mapMailbox(it) }
        } else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * Atomically read and mark a batch of incoming messages as
     * `read_at = now()`. The SQL `FOR UPDATE SKIP LOCKED` clause keeps
     * concurrent drains from double-delivering the same message.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun drainMailboxMessages(
        sessionId: String,
        limit: Int = 50,
    ): List<MailboxMessageInfo> = runBlocking {
        val body = mapOf("sessionId" to sessionId, "limit" to limit)
        val response = dbClient.post("/agent/mailbox/drain", body)
        if (response.first == HttpStatusCode.OK) {
            val node = mapper.readTree(response.second)
            val msgs = node["messages"] ?: return@runBlocking emptyList()
            msgs.map { mapMailbox(it) }
        } else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun ackMailboxMessage(sessionId: String, messageId: Long) = runBlocking {
        val body = mapOf("sessionId" to sessionId, "messageId" to messageId)
        val response = dbClient.post("/agent/mailbox/ack", body)
        if (response.first != HttpStatusCode.OK) {
            val detail = response.second.takeIf { it.isNotBlank() } ?: response.first.description
            throw DatabaseClient.DatabaseDaemonException(response.first, detail)
        }
    }

    /** Cheap unread-count check (zero unread short-circuits the drain). */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun countUnreadMailbox(sessionId: String): Int = runBlocking {
        val body = mapOf("sessionId" to sessionId)
        val response = dbClient.post("/agent/mailbox/count_unread", body)
        if (response.first == HttpStatusCode.OK) {
            val node = mapper.readTree(response.second)
            node["unread"]?.asInt() ?: 0
        } else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * Fetch a single message by id, scoped to sender OR recipient
     * session. Used by the LLM to follow up on a thread it is part of
     * (typically via `in_reply_to_message_id`).
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun getMailboxMessage(sessionId: String, messageId: Long): MailboxMessageInfo? = runBlocking {
        val body = mapOf("sessionId" to sessionId, "messageId" to messageId)
        val response = dbClient.post("/agent/mailbox/get", body)
        if (response.first == HttpStatusCode.OK) {
            val node = mapper.readTree(response.second)
            if (node.isObject && node.has("messageId")) mapMailbox(node) else null
        } else if (response.first == HttpStatusCode.NotFound)
            null
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    // ============================================================
    //  Artifacts
    // ============================================================

    data class ArtifactInfo(
        val artifactId: Long,
        val ownerSessionId: String,
        val name: String,
        val version: Int,
        val kind: String,
        val content: String,
        val summary: String?,
        val metadata: String?,
        val isPublic: Boolean,
        val supersededBy: Long?,
        val createdAt: String?,
    )

    private fun mapArtifact(node: JsonNode): ArtifactInfo = ArtifactInfo(
        artifactId = node["artifactId"].asLong(),
        ownerSessionId = node["ownerSessionId"].asText(),
        name = node["name"].asText(),
        version = node["version"].asInt(),
        kind = node["kind"].asText(),
        content = node["content"].asText(),
        summary = node["summary"]?.takeIf { !it.isNull }?.asText(),
        metadata = node["metadata"]?.takeIf { !it.isNull }?.asText(),
        isPublic = node["isPublic"].asBoolean(),
        supersededBy = node["supersededBy"]?.takeIf { !it.isNull }?.asLong(),
        createdAt = node["createdAt"]?.takeIf { !it.isNull }?.asText(),
    )

    /** Upsert an artifact keyed by `(owner_session_id, name, version)`. */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun publishArtifact(
        ownerSessionId: String,
        name: String,
        kind: String = "data",
        content: String,
        summary: String? = null,
        metadata: String? = null,
        isPublic: Boolean = false,
        version: Int = 1,
    ): Long = runBlocking {
        val body = mapOf(
            "ownerSessionId" to ownerSessionId,
            "name" to name,
            "kind" to kind,
            "content" to content,
            "summary" to summary,
            "metadata" to metadata,
            "isPublic" to isPublic,
            "version" to version,
        )
        val response = dbClient.post("/agent/artifact/publish", body)
        if (response.first == HttpStatusCode.OK) {
            val node = mapper.readTree(response.second)
            node["artifactId"].asLong()
        } else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * Read a single artifact. Pass the caller's session id so the route
     * can enforce the `is_public` / same-binary visibility rule;
     * passing null disables that check (tests only).
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun getArtifact(
        callerSessionId: String?,
        artifactId: Long? = null,
        ownerSessionId: String? = null,
        name: String? = null,
        version: Int? = null,
    ): ArtifactInfo? = runBlocking {
        val body = mutableMapOf<String, Any?>("sessionId" to callerSessionId)
        if (artifactId != null) body["artifactId"] = artifactId
        if (ownerSessionId != null) body["ownerSessionId"] = ownerSessionId
        if (name != null) body["name"] = name
        if (version != null) body["version"] = version
        val response = dbClient.post("/agent/artifact/get", body)
        if (response.first == HttpStatusCode.OK) {
            val node = mapper.readTree(response.second)
            if (node.isObject && node.has("artifactId")) mapArtifact(node) else null
        } else if (response.first == HttpStatusCode.NotFound)
            null
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /** List artifacts owned by `ownerSessionId` (or the caller's own). */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun listArtifacts(
        callerSessionId: String,
        ownerSessionId: String? = null,
        name: String? = null,
        includePublic: Boolean = true,
        limit: Int = 50,
    ): List<ArtifactInfo> = runBlocking {
        val body = mutableMapOf<String, Any?>(
            "sessionId" to callerSessionId,
            "includePublic" to includePublic,
            "limit" to limit,
        )
        if (ownerSessionId != null) body["ownerSessionId"] = ownerSessionId
        if (name != null) body["name"] = name
        val response = dbClient.post("/agent/artifact/list", body)
        if (response.first == HttpStatusCode.OK) {
            val arr = mapper.readTree(response.second)
            arr.map { mapArtifact(it) }
        } else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun deleteArtifact(callerSessionId: String, artifactId: Long) = runBlocking {
        val body = mapOf("sessionId" to callerSessionId, "artifactId" to artifactId)
        val response = dbClient.post("/agent/artifact/delete", body)
        if (response.first != HttpStatusCode.OK)
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
        val response = dbClient.post("/agent/graph/create", body)
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
        val response = dbClient.post("/agent/graph/add_node", body)
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
        val response = dbClient.post("/agent/graph/add_edge", body)
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
        val response = dbClient.post("/agent/graph/load", mapOf("graphId" to graphId))
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
        val response = dbClient.post("/agent/execution/record", body)
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
        val response = dbClient.post("/agent/execution/get", body)
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
        val response = dbClient.post("/agent/human/request", body)
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
        val response = dbClient.post("/agent/human/respond", body)
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
        val response = dbClient.post("/agent/human/poll", body)
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
        val author: String?,
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
        author: String = "",
        code: String,
        language: String = "kotlin",
        saveResult: Boolean = true,
        maxOutputSize: Long = 10485760
    ): Int = runBlocking {
        val body = mapOf(
            "name" to name,
            "description" to description,
            "author" to author,
            "code" to code,
            "language" to language,
            "saveResult" to saveResult,
            "maxOutputSize" to maxOutputSize
        )
        val response = dbClient.post("/script/create", body)
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
        val response = dbClient.post("/script/get", mapOf("scriptId" to scriptId))
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
        val response = dbClient.post("/script/list", body)
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
        val response = dbClient.post("/script/update", body)
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
        val response = dbClient.post("/script/update_output", body)
        if (response.first != HttpStatusCode.OK)
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * Delete a script by ID.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun deleteScript(scriptId: Int) = runBlocking {
        val response = dbClient.post("/script/delete", mapOf("scriptId" to scriptId))
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
        val response = dbClient.post("/script/execution/create", body)
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
        val response = dbClient.post("/script/execution/get", mapOf("executionId" to executionId))
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
        val response = dbClient.post("/script/execution/list", mapOf("scriptId" to scriptId))
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
        val response = dbClient.post("/script/execution/update", body)
        if (response.first != HttpStatusCode.OK)
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    // ============================================================
    //  Tool Call Audit
    // ============================================================

    data class ToolCallResultInfo(
        val resultUuid: String,
        val callId: Long?,
        val sessionId: String,
        val messageId: Long?,
        val toolCallId: String?,
        val toolName: String,
        val toolArgs: String?,
        val content: String,
        val offset: Int,
        val returnedChars: Int,
        val storedChars: Int,
        val originalBytes: Int,
        val storedBytes: Int,
        val truncated: Boolean,
        val sha256: String?,
        val storagePolicy: String?,
        val createdAt: String?
    )

    data class ToolCallResultSummaryInfo(
        val resultUuid: String,
        val callId: Long?,
        val sessionId: String,
        val messageId: Long?,
        val toolCallId: String?,
        val toolName: String,
        val toolArgs: String?,
        val originalBytes: Int,
        val storedBytes: Int,
        val truncated: Boolean,
        val sha256: String?,
        val storagePolicy: String?,
        val createdAt: String?
    )

    /**
     * Record a tool call for auditing.
     * @return the generated call ID
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun recordToolCall(
        sessionId: String,
        messageId: Long? = null,
        nodeId: String? = null,
        toolCallId: String? = null,
        toolName: String,
        toolArgs: String? = null,
        resultUuid: String? = null,
        resultSummary: String? = null,
        resultContent: String? = null,
        resultOriginalBytes: Int? = null,
        resultStoredBytes: Int? = null,
        resultTruncated: Boolean? = null,
        resultSha256: String? = null,
        storagePolicy: String? = null,
        success: Boolean = true,
        durationMs: Long? = null
    ): Long = runBlocking {
        val body = mapOf(
            "sessionId" to sessionId,
            "messageId" to messageId,
            "nodeId" to nodeId,
            "toolCallId" to toolCallId,
            "toolName" to toolName,
            "toolArgs" to toolArgs,
            "resultUuid" to resultUuid,
            "resultSummary" to resultSummary,
            "resultContent" to resultContent,
            "resultOriginalBytes" to resultOriginalBytes,
            "resultStoredBytes" to resultStoredBytes,
            "resultTruncated" to resultTruncated,
            "resultSha256" to resultSha256,
            "storagePolicy" to storagePolicy,
            "success" to success,
            "durationMs" to durationMs
        )
        val response = dbClient.post("/agent/tool_call/record", body)
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<Long>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun findToolCallResults(
        sessionId: String,
        resultSha256: String? = null,
        toolName: String? = null,
        toolArgs: String? = null,
        limit: Int = 20
    ): List<ToolCallResultSummaryInfo> = runBlocking {
        val body = mapOf(
            "sessionId" to sessionId,
            "resultSha256" to resultSha256,
            "toolName" to toolName,
            "toolArgs" to toolArgs,
            "limit" to limit
        )
        val response = dbClient.post("/agent/tool_call/result/find", body)
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<List<ToolCallResultSummaryInfo>>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    /**
     * Search tool call results by tool name, args text, or result UUID.
     * Unlike [findToolCallResults], this performs a case-insensitive
     * substring search on tool args (ILIKE) and does not require a
     * sessionId — it searches across all sessions in the current database.
     */
    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun searchToolCallResults(
        sessionId: String? = null,
        toolName: String? = null,
        toolArgsContains: String? = null,
        resultUuid: String? = null,
        limit: Int = 20
    ): List<ToolCallResultSummaryInfo> = runBlocking {
        val body = mapOf(
            "sessionId" to sessionId,
            "toolName" to toolName,
            "toolArgsContains" to toolArgsContains,
            "resultUuid" to resultUuid,
            "limit" to limit
        )
        val response = dbClient.post("/agent/tool_call/result/search", body)
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<List<ToolCallResultSummaryInfo>>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }

    @Throws(DatabaseClient.DatabaseDaemonException::class)
    fun getToolCallResult(
        resultUuid: String,
        offset: Int = 0,
        limit: Int = 40000,
        grep: String? = null,
        around: Int = 3
    ): ToolCallResultInfo = runBlocking {
        val body = mapOf(
            "resultUuid" to resultUuid,
            "offset" to offset,
            "limit" to limit,
            "grep" to grep,
            "around" to around
        )
        val response = dbClient.post("/agent/tool_call/result/get", body)
        if (response.first == HttpStatusCode.OK)
            mapper.readValue<ToolCallResultInfo>(response.second)
        else
            throw DatabaseClient.DatabaseDaemonException(response.first, response.first.description)
    }
}
