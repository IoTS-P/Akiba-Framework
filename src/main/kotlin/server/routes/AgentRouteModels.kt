package org.iotsplab.akiba.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.nio.file.Path
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.data.database.AgentDatabaseClient.SessionInfo
import org.iotsplab.akiba.llm.agent.module.tool
import org.iotsplab.akiba.llm.tool.core.ConfirmationManager
import org.iotsplab.akiba.llm.tool.ui.UserChoiceManager

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

data class ChatResponse(
    val sessionId: String,
    val userMessage: AgentMessageResponse,
    val assistantMessage: AgentMessageResponse,
    val iterations: Int,
    val tokenUsage: Map<String, Int>?
)

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

/** What a worker process needs to start: its config file, and the model for this session. */
internal data class ManualAgentWorkerConfig(
    val file: Path,
    val environment: Map<String, String>,
    /** Model and key for this session; the worker is told per turn, so config changes apply at once. */
    val llm: org.iotsplab.akiba.managers.TurnBroker.TurnLlm? = null,
)

