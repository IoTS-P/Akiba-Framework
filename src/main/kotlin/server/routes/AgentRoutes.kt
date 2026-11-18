package org.iotsplab.akiba.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.LogManager
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.data.database.AgentDatabaseClient.SessionInfo
import org.iotsplab.akiba.data.database.DatabaseClient
import org.iotsplab.akiba.llm.agent.context.ModelContextWindows
import org.iotsplab.akiba.llm.agent.core.AgentPrompts
import org.iotsplab.akiba.llm.agent.core.LLM_PROGRESS_PREFIX
import org.iotsplab.akiba.llm.agent.core.LLM_RETRY_STATUS_PREFIX
import org.iotsplab.akiba.llm.agent.core.SYSTEM_SESSION_UUID
import org.iotsplab.akiba.llm.agent.mailbox.AgentMailboxService
import org.iotsplab.akiba.llm.agent.module.AgentModule
import org.iotsplab.akiba.llm.agent.module.tool
import org.iotsplab.akiba.llm.agent.runtime.AgentRuntime
import org.iotsplab.akiba.llm.agent.runtime.RuntimeState
import org.iotsplab.akiba.llm.agent.stream.StreamChunkBus
import org.iotsplab.akiba.llm.config.LLMKeyFileStore
import org.iotsplab.akiba.llm.tool.core.BuiltInTools
import org.iotsplab.akiba.llm.tool.core.ConfirmationManager
import org.iotsplab.akiba.llm.tool.core.Tool
import org.iotsplab.akiba.llm.tool.core.ToolInterrupts
import org.iotsplab.akiba.llm.tool.core.ToolRunner
import org.iotsplab.akiba.llm.tool.ui.PendingChoiceSnapshot
import org.iotsplab.akiba.llm.tool.ui.UserChoiceEvent
import org.iotsplab.akiba.llm.tool.ui.UserChoiceManager
import org.iotsplab.akiba.managers.ConfigManager
import org.iotsplab.akiba.managers.ProjectIndex
import org.iotsplab.akiba.managers.ProjectPrograms
import org.iotsplab.akiba.managers.ProjectLock
import org.iotsplab.akiba.managers.ProjectWorkers
import org.iotsplab.akiba.managers.WorkspaceManager
import org.iotsplab.akiba.utils.ProcedureArgumentsDeserializer
import org.iotsplab.akiba.utils.AkibaJson

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

/** Fields a client may change on an existing session. */
data class SessionPatchRequest(
    val sessionName: String? = null,
    val programName: String? = null,
)

data class AgentSessionResponse(
    val sessionId: String,
    val sessionName: String?,
    val status: String,
    val modelName: String?,
    val projectName: String?,
    /** Program the session is working on inside [projectName]. */
    val currentProgram: String? = null,
    val moduleName: String?,
    /** Workflow run that spawned this session (null for interactive sessions). */
    val workflowId: String? = null,
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
     * Set by [ConfirmationManager] when a tool (e.g. `run_shell`) is blocked
     * waiting for the user to approve/deny an action; the frontend surfaces it
     * as a modal from the session status poll. `null` when nothing is pending.
     */
    val pendingConfirmation: PendingConfirmationDto? = null,
    /**
     * Pending user-choice request for this session, if any (populated
     * from [UserChoiceManager] when `ask_user_choice` is blocked).
     */
    val pendingUserChoice: PendingUserChoiceDto? = null,
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

/** Wire DTO for a pending user-choice request (see [UserChoiceManager]). */
data class PendingUserChoiceDto(
    val requestId: String,
    val question: String,
    val options: List<String>,
    val allowCustomInput: Boolean,
    val createdAt: Long,
    /** Follow-up (BTW) Q&A pairs already answered by the side assistant. */
    val btwHistory: List<BtwQADto> = emptyList(),
    /** Last user-activity timestamp; the frontend renders the inactivity countdown from it. */
    val lastActivityAt: Long = createdAt
)

/** One follow-up (BTW) question/answer pair. */
data class BtwQADto(
    val question: String,
    val answer: String,
    val answeredAt: Long
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

/** Request body for `POST /agent/internal/user-choice/request` (worker → server). */
data class InternalUserChoiceRequest(
    val sessionId: String,
    val question: String,
    val options: List<String>,
    val allowCustomInput: Boolean = true,
    /** Set (with [btwAnswer]) when the worker re-enters the long-poll after answering a BTW question. */
    val btwId: String? = null,
    /** The worker's locally-generated answer to the BTW question. */
    val btwAnswer: String? = null
)

/** Request body for `POST /agent/sessions/{id}/user-choice/respond`. */
data class UserChoiceRespondRequest(
    /** The selected option text or the user's custom free-form answer. */
    val choice: String? = null,
    /** When true, the user dismissed the question without answering. */
    val cancelled: Boolean = false
)

/** Request body for `POST /agent/sessions/{id}/user-choice/btw`. */
data class UserChoiceBtwRequest(
    /** The follow-up question the user wants answered before deciding. */
    val question: String
)

/** Map a manager snapshot to its wire DTO. */
internal fun org.iotsplab.akiba.llm.tool.ui.PendingChoiceSnapshot.toDto() = PendingUserChoiceDto(
    requestId = choice.requestId,
    question = choice.question,
    options = choice.options,
    allowCustomInput = choice.allowCustomInput,
    createdAt = choice.createdAt,
    btwHistory = btwHistory.map { BtwQADto(it.question, it.answer, it.answeredAt) },
    lastActivityAt = lastActivityAt
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
    val systemPrompt: String? = null,
    /**
     * Optional model override for this and subsequent turns.  When it
     * differs from the session's stored model, the session row is
     * updated first so the switch survives restarts — a session keeps
     * using one model until the user explicitly picks another.
     */
    val modelName: String? = null
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

/**
 * Payload sent by a worker (or the in-process runtime) to
 * `POST /agent/internal/stream-chunk`.
 *
 * A single chunk may still be posted flat (the fields below); the pusher sends
 * a whole burst as `chunks`, which keeps a fast response to one round-trip so
 * its tail — the `done` marker included — cannot be lost at worker exit.
 */
data class StreamChunkRequest(
    val sessionId: String = "",
    val delta: String = "",
    val chunkCount: Int = 0,
    val byteCount: Int = 0,
    val done: Boolean = false,
    val error: String? = null,
    val newGeneration: Boolean = false,
    /** `text` for answer deltas, `tool_start` / `tool_end` for tool events. */
    val kind: String = "text",
    val chunks: List<StreamChunkPayload> = emptyList(),
)

/** One chunk inside a batched [StreamChunkRequest]. */
data class StreamChunkPayload(
    /** Overrides the request's sessionId when set (batches may mix sessions). */
    val sessionId: String = "",
    val delta: String = "",
    val chunkCount: Int = 0,
    val byteCount: Int = 0,
    val done: Boolean = false,
    val error: String? = null,
    val newGeneration: Boolean = false,
    /** `text` for answer deltas, `tool_start` / `tool_end` for tool events. */
    val kind: String = "text",
)

/** Sessions whose last turn the user stopped, so /chat answers calmly. */
internal object ChatCancellations {
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    /** A new turn is starting — drop any stale marker. */
    fun clear(sessionId: String) {
        cancelled.remove(sessionId)
    }

    fun mark(sessionId: String) {
        cancelled.add(sessionId)
    }

    /** True (exactly once) when [sessionId]'s last turn was stopped by the user. */
    fun consume(sessionId: String): Boolean = cancelled.remove(sessionId)
}

/** Stream-bus error label used when a chat turn is cancelled by the user.
 *  The frontend recognises this prefix to render a calm "cancelled"
 *  marker instead of a scary interruption error. */
internal const val USER_CANCELLED_STREAM_ERROR = "cancelled by user"

/**
 * A manual-agent worker JVM failed (non-zero exit / timeout).  Kept
 * distinct from [IllegalStateException] (reserved for configuration
 * problems) so the /chat handler does not prefix a worker crash with
 * the misleading "Agent is not configured" label.
 */
internal class ManualAgentWorkerException(message: String) : Exception(message)

internal val agentRouteLogger = LogManager.getLogger("AgentRoutes")
internal val agentRouteMapper = AkibaJson.mapper
internal const val MANUAL_AGENT_TIMEOUT_MINUTES: Long = 10
internal const val AGENT_MESSAGES_PAGE_SIZE = 500
internal const val AGENT_MESSAGES_MAX_RETURN = 20_000

/**
 * Fetch a complete session transcript from the daemon despite the daemon's
 * `/agent/message/get` hard cap of 500 rows per request.
 *
 * The frontend wake selector needs all `[[AKIBA_WAKE_EVENT]]` markers and the
 * messages between them.  Fetching only the first page can make later wake
 * cycles unselectable or make a selected wake appear to have missing messages
 * once a long multi-wake agent session grows beyond 500 rows.
 */
internal fun fetchAllAgentMessages(
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

internal val DEFAULT_AGENT_SYSTEM_PROMPT: String = """
    ${AgentPrompts.DEFAULT_SYSTEM_PROMPT}

    <interactive_session>
    You are Akiba's interactive assistant. Help the user reason about binary
    analysis, scripting, and the Akiba platform. Answer concisely, but keep
    conclusions evidence-backed. If the selected session has a binary, assume
    that binary is the current analysis target.
    </interactive_session>
""".trimIndent()

internal fun withCommonAgentRules(prompt: String): String =
    if (prompt.contains("<tools_usage_policy>")) prompt else "$prompt\n\n${AgentPrompts.DEFAULT_AGENT_RULES}"

// ============================================================
//  Routes
// ============================================================


/**
 * Statistics computed from a session's message history. Used in both the
 * "Agent Tree" overview and the per-agent sections of the tree-export
 * document.
 */
internal data class SessionStats(
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
internal data class ExportTreeNode(
    val info: AgentDatabaseClient.SessionInfo,
    val stats: SessionStats,
    val depth: Int,
    val children: List<ExportTreeNode>
)

/**
 * Build the Markdown export of an agent hierarchy: a metadata header, an
 * "Agent Tree" section with per-layer statistics (tokens, tool calls) and an
 * ASCII tree, then one section per agent in DFS pre-order holding that
 * session's statistics and full transcript.
 *
 * Cycles are detected via a `seen` set and silently skipped so a malformed
 * hierarchy cannot crash the export.
 */
internal fun renderTreeExport(
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
internal fun collectExportTree(
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
internal fun ExportTreeNode.descendantsFlattened(): List<ExportTreeNode> =
    listOf(this) + children.flatMap { it.descendantsFlattened() }

/**
 * Compute aggregate statistics for a session by walking its message
 * history. Assistant messages carry `inputTokenCount` / `tokenCount`;
 * tool invocations are messages with `role = "tool"`.
 *
 * Context-compaction rebuilds persist kept messages as NEW rows that
 * carry their original token counts, so a runaway compaction loop can
 * leave the same logical message duplicated thousands of times in
 * `agent_messages`. Raw SUMs over the table are therefore meaningless;
 * this function collapses exact rewrite copies before aggregating:
 * assistant rows by (content, token counts), tool rows by toolCallId.
 */
internal fun computeSessionStats(
    agentDbClient: AgentDatabaseClient,
    sessionId: String
): SessionStats {
    var inputTokens = 0L
    var outputTokens = 0L
    var toolCalls = 0
    var totalMessages = 0
    val toolCounts = HashMap<String, Int>()
    val seenAssistant = HashSet<String>()
    val seenToolCalls = HashSet<String>()
    try {
        // Paginate through the FULL transcript — a runaway session can
        // accumulate far more rows than any fixed cap.
        var nextIndex = 0
        while (true) {
            val page = agentDbClient.getMessages(sessionId, nextIndex, AGENT_MESSAGES_PAGE_SIZE)
            if (page.isEmpty()) break
            nextIndex = (page.maxOfOrNull { it.messageIndex } ?: nextIndex) + 1
            for (m in page) {
                when (m.role) {
                    "assistant" -> {
                        val key = "in:${m.inputTokenCount}:out:${m.tokenCount}:len:${m.content?.length}:${m.content}"
                        if (seenAssistant.add(key)) {
                            totalMessages++
                            inputTokens += m.inputTokenCount ?: 0
                            outputTokens += m.tokenCount ?: 0
                        }
                    }
                    "tool" -> {
                        val key = m.toolCallId ?: "content:${m.content}"
                        if (seenToolCalls.add(key)) {
                            totalMessages++
                            toolCalls++
                            val name = m.toolName ?: "unknown"
                            toolCounts.merge(name, 1) { a, b -> a + b }
                        }
                    }
                    else -> totalMessages++
                }
            }
            if (page.size < AGENT_MESSAGES_PAGE_SIZE) break
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
internal fun renderAsciiTree(
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
internal fun renderAsciiTreeLine(
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

internal fun nodeDepthLabel(depth: Int): String = when (depth) {
    0 -> "(root)"
    else -> "($depth)"
}

internal fun nowString(): String =
    java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

internal fun markManualChatFailed(
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
                    org.iotsplab.akiba.llm.agent.runtime.RuntimeState.ERROR.wire(),
                    reason?.take(200) ?: "manual_chat_failed",
                )
            }
        }
    }
}

/** What a worker process needs to start: its config file, and the model for this session. */
internal data class ManualAgentWorkerConfig(
    val file: Path,
    val environment: Map<String, String>,
    /** Model and key for this session; the worker is told per turn, so config changes apply at once. */
    val llm: org.iotsplab.akiba.managers.TurnBroker.TurnLlm? = null,
)

internal fun createManualAgentConfig(
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
    // The server's own projects root, made absolute. Resolving it against the configured
    // root put the worker into an empty project that happened to share the name.
    val projectRoot = projectDirectory.toAbsolutePath().normalize()
        .toAbsolutePath()
        .normalize()
    val projectName = sessionInfo.projectName ?: "manual-agent-${sessionInfo.sessionId.take(8)}"

    // The spawned worker talks to the daemon on its own, as the instance's
    // owner: there is no shared service account any more. It is handed a
    // derived credential (not the account password), which the daemon expires
    // on its own.
    val daemonIdentity = DaemonIdentities.ownerOf(instanceName)
        ?.let { DaemonIdentities.forUser(it) }
        ?: throw IllegalStateException(
            "No database credentials cached for instance '$instanceName'; " +
                "sign in again so the server can authenticate on your behalf"
        )

    val mainConfig = linkedMapOf<String, Any?>(
        "username" to daemonIdentity.username,
        "password" to daemonIdentity.secret,
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
    return ManualAgentWorkerConfig(
        file = file,
        environment = mapOf(llmEnvName to keyEntry.apiKey),
        llm = org.iotsplab.akiba.managers.TurnBroker.TurnLlm(
            provider = keyEntry.provider,
            modelName = modelName,
            apiKey = keyEntry.apiKey,
            baseUrl = keyEntry.baseUrl,
        ),
    )
}

/**
 * The worker that owns a project, kept alive across turns.
 *
 * Starting a JVM per turn meant reopening the Ghidra project every time, and it made two
 * sessions on one project impossible. One worker per project is started on demand and
 * then reused; if it dies, the next turn starts a fresh one.
 */
internal object ResidentWorkers {

    private val running = java.util.concurrent.ConcurrentHashMap<String, Process>()

    fun ensure(
        project: String,
        workerConfig: ManualAgentWorkerConfig,
        serverPort: Int,
        instance: String,
    ) {
        synchronized(this) {
            running[project]?.takeIf { it.isAlive }?.let { return }

            val scriptPath = findAkibaScriptForAgent()
            val pb = ProcessBuilder(scriptPath, "-c", "${workerConfig.file}@/main")
            pb.directory(File(System.getProperty("user.dir", ".")))
            val env = pb.environment()
            env.putAll(workerConfig.environment)
            env["AKIBA_PROJECT_WORKERS"] = "1"
            env["AKIBA_MANUAL_AGENT_SERVER_PORT"] = serverPort.toString()
            env["AKIBA_MANUAL_AGENT_INSTANCE"] = instance
            env["AKIBA_MANUAL_AGENT_PROJECT_NAME"] = project
            pb.redirectErrorStream(true)
            val logFile = File(System.getProperty("user.home", "."), ".akiba/logs/worker-$project.log")
            logFile.parentFile?.mkdirs()
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            agentRouteLogger.info("Starting the resident worker for project {} (log {})", project, logFile)
            running[project] = pb.start()
        }
    }

    fun forget(project: String) {
        synchronized(this) { running.remove(project) }
    }
}

/**
 * Queues one turn for the project's worker and waits for it.
 *
 * The wait is on the turn's future rather than on a process, so several sessions can be
 * waiting on one worker at the same time without any of them owning the process.
 */
internal fun runPooledTurn(
    project: String,
    sessionId: String,
    programId: String?,
    prompt: String,
    systemPrompt: String,
    username: String,
    programName: String?,
    llm: org.iotsplab.akiba.managers.TurnBroker.TurnLlm?,
) {
    val turn = org.iotsplab.akiba.managers.TurnBroker.Turn(
        id = java.util.UUID.randomUUID().toString(),
        project = project,
        sessionId = sessionId,
        programId = programId,
        prompt = prompt,
        systemPrompt = systemPrompt,
        username = username,
        programName = programName,
        llm = llm,
    )
    if (!ProjectWorkers.broker.submit(turn)) {
        throw ManualAgentWorkerException(
            "Too many turns are already queued for this conversation. Wait for the current one to finish.")
    }
    val future = ProjectWorkers.broker.resultOf(turn.id)
        ?: throw ManualAgentWorkerException("The turn was dropped before it could run.")
    ChatCancellations.clear(sessionId)
    val result = try {
        future.get(MANUAL_AGENT_TIMEOUT_MINUTES, TimeUnit.MINUTES)
    } catch (e: Exception) {
        ResidentWorkers.forget(project)
        throw ManualAgentWorkerException(
            "The agent worker stopped before this turn finished. It will be restarted on the next message.")
    }
    if (!result.ok) {
        throw ManualAgentWorkerException(result.error ?: "The agent worker failed this turn.")
    }
}

internal fun findFirstProgramName(projectName: String, projectDir: java.nio.file.Path): String? {
    val grpFile = projectDir.resolve("$projectName.gpr")
    val repFile = projectDir.resolve("$projectName.rep")
    if (!Files.isRegularFile(grpFile) || !Files.isDirectory(repFile)) return null
    return try {
        WorkspaceManager.openOrCreateInteractiveProject(projectName, false, projectDir)
        val project = WorkspaceManager.project
        project.projectData.refresh(true)
        val first = findFirstProgramRecursive(project.projectData.rootFolder)
        WorkspaceManager.releaseActiveProject()
        first?.pathname
    } catch (e: Exception) {
        agentRouteLogger.warn("findFirstProgramName('$projectName') failed to open project: ${e.message}")
        null
    }
}

internal fun findFirstProgramRecursive(
    folder: ghidra.framework.model.DomainFolder
): ghidra.framework.model.DomainFile? {
    folder.files.firstOrNull { f ->
        val doc = f.domainObjectClass
        doc != null && ghidra.program.model.listing.Program::class.java.isAssignableFrom(doc)
    }?.let { return it }
    for (sub in folder.folders) {
        findFirstProgramRecursive(sub)?.let { return it }
    }
    return null
}

internal fun findAkibaScriptForAgent(): String {
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

internal fun SessionInfo.toResponse(
    pendingConfirmation: PendingConfirmationDto? = null,
    pendingUserChoice: PendingUserChoiceDto? = null
) = AgentSessionResponse(
    sessionId = sessionId,
    sessionName = sessionName,
    status = status,
    modelName = modelName,
    projectName = projectName,
    currentProgram = currentProgram,
    moduleName = moduleName,
    workflowId = workflowId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    parentSessionId = parentSessionId,
    lifecycle = lifecycle,
    runtimeState = runtimeState,
    closingReason = closingReason,
    errorMessage = if (runtimeState == "error" || status == "error") closingReason else null,
    pendingConfirmation = pendingConfirmation,
    pendingUserChoice = pendingUserChoice,
    // token totals omitted here for performance; use GET /agent/sessions/{id}/messages
)

internal fun AgentDatabaseClient.MessageInfo.toMessageResponse() = AgentMessageResponse(
    messageId = messageId,
    messageIndex = messageIndex,
    role = role,
    content = if (role == "tool") toolResult ?: content else content,
    createdAt = createdAt,
    toolName = if (role == "tool") toolName else null,
    toolResult = if (role == "tool") (toolResult ?: content) else null
)

/**
 * Every agent route, assembled from one file per domain.
 *
 * Each domain keeps its own file; this function is the table of contents, and the one
 * place the server looks to know which routes exist.
 */
fun Route.agentRoutes(daemonHost: String, daemonPort: Int) {
    agentSessionRoutes(daemonHost, daemonPort)
    agentStreamRoutes(daemonHost, daemonPort)
    agentHumanRoutes(daemonHost, daemonPort)
    agentChatRoutes(daemonHost, daemonPort)

    // A resident worker asks for its next turn here and reports the outcome after.
    workerTurnRoutes()
}
