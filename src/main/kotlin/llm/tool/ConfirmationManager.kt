package org.iotsplab.akiba.llm.tool

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// ============================================================
//  ConfirmationManager — in-process human-in-the-loop gate
// ============================================================

/**
 * A pending confirmation request waiting for user approval.
 *
 * Stored in [ConfirmationManager] keyed by session ID. The frontend
 * polls `GET /agent/sessions/{id}` which includes `pendingConfirmation`
 * when one exists; the user approves or denies via
 * `POST /agent/sessions/{id}/confirmation/respond`.
 */
data class PendingConfirmation(
    /** Unique ID for this request (used by the respond endpoint). */
    val requestId: String,
    /** The session that owns this confirmation. */
    val sessionId: String,
    /** Tool name that triggered the confirmation (e.g. "run_shell"). */
    val toolName: String,
    /** The command or action summary the user is being asked to approve. */
    val command: String,
    /** Working directory context for the command. */
    val workingDirectory: String,
    /** Timeout (seconds) configured for the command. */
    val timeout: Int,
    /**
     * Action type — tells the frontend what kind of confirmation this is,
     * so it can render an appropriate UI (e.g. a path-access warning for
     * workspace-outside operations vs. a generic shell-command prompt).
     *
     *  - `"shell_command"` — run_shell executing a command
     *  - `"file_access"`   — a file tool accessing a path outside the workspace
     */
    val action: String = "shell_command",
    /**
     * The target path for `file_access` actions (null for `shell_command`).
     * The frontend uses this to prominently display which file the agent
     * wants to read/write/grep outside the workspace.
     */
    val targetPath: String? = null,
    /** Epoch millis when the request was created. */
    val createdAt: Long = System.currentTimeMillis()
)

private data class PendingEntry(
    val confirmation: PendingConfirmation,
    val deferred: CompletableDeferred<Boolean>
)

/**
 * Process-level singleton that manages pending human-confirmation requests.
 *
 * Design:
 * - Tools that need user approval call [requestConfirmationBlocking],
 *   which stores a [CompletableDeferred] and blocks the calling thread
 *   until the user responds (or the confirmation timeout expires).
 * - The frontend discovers the pending request via the session status
 *   poll (`GET /agent/sessions/{id}` — the response includes
 *   `pendingConfirmation` when one exists).
 * - The frontend responds via `POST /agent/sessions/{id}/confirmation/respond`,
 *   which calls [respond] to complete the deferred.
 *
 * Thread-safety: backed by [ConcurrentHashMap], safe for concurrent
 * access from multiple sessions.
 *
 * Limitation: this is an in-process singleton. When tools execute in a
 * separate worker process (manual-agent mode), the worker must use the
 * HTTP callback `POST /agent/internal/confirmation/request` to reach this
 * singleton. The route handler calls [requestConfirmation] (suspend) which
 * registers the pending request here and blocks until the user responds.
 */
object ConfirmationManager {

    /** Default time to wait for user response before timing out (5 minutes). */
    const val DEFAULT_CONFIRMATION_TIMEOUT_MS: Long = 300_000L

    private val pending = ConcurrentHashMap<String, PendingEntry>()

    /**
     * **Suspend** variant of [requestConfirmationBlocking].
     *
     * Intended for use inside Ktor route handlers (which are coroutines).
     * Registers the pending request and suspends until the user responds
     * or the timeout expires — **without blocking the thread**.
     */
    suspend fun requestConfirmation(
        sessionId: String,
        toolName: String,
        command: String,
        workingDirectory: String,
        timeout: Int,
        action: String = "shell_command",
        targetPath: String? = null,
        confirmationTimeoutMs: Long = DEFAULT_CONFIRMATION_TIMEOUT_MS
    ): Boolean {
        if (sessionId.isBlank()) return false

        val requestId = UUID.randomUUID().toString()
        val confirmation = PendingConfirmation(
            requestId = requestId,
            sessionId = sessionId,
            toolName = toolName,
            command = command,
            workingDirectory = workingDirectory,
            timeout = timeout,
            action = action,
            targetPath = targetPath
        )
        val deferred = CompletableDeferred<Boolean>()
        pending[sessionId] = PendingEntry(confirmation, deferred)

        return try {
            withTimeout(confirmationTimeoutMs) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            false
        } catch (_: Exception) {
            false
        } finally {
            pending.remove(sessionId)
        }
    }

    /**
     * Request user confirmation and **block the calling thread** until
     * the user responds or the timeout expires.
     *
     * This is designed to be called from a synchronous [Tool.execute]
     * lambda. It uses `runBlocking` internally to suspend on the
     * [CompletableDeferred] — consistent with how [RunShellTool] already
     * uses `runBlocking` for process execution.
     *
     * @param sessionId           The agent session ID (from [AgentModule.agentSessionId]).
     * @param toolName            The name of the tool requesting confirmation.
     * @param command             The command or action summary to display.
     * @param workingDirectory    The CWD context.
     * @param timeout             The command timeout (seconds).
     * @param confirmationTimeoutMs How long to wait for the user's response
     *                             before giving up (default 5 minutes).
     * @return `true` if the user approved, `false` if denied or timed out.
     */
    fun requestConfirmationBlocking(
        sessionId: String,
        toolName: String,
        command: String,
        workingDirectory: String,
        timeout: Int,
        action: String = "shell_command",
        targetPath: String? = null,
        confirmationTimeoutMs: Long = DEFAULT_CONFIRMATION_TIMEOUT_MS
    ): Boolean {
        if (sessionId.isBlank()) {
            // No session context — auto-deny as a safety measure.
            return false
        }

        val requestId = UUID.randomUUID().toString()
        val confirmation = PendingConfirmation(
            requestId = requestId,
            sessionId = sessionId,
            toolName = toolName,
            command = command,
            workingDirectory = workingDirectory,
            timeout = timeout,
            action = action,
            targetPath = targetPath
        )
        val deferred = CompletableDeferred<Boolean>()
        pending[sessionId] = PendingEntry(confirmation, deferred)

        return try {
            runBlocking {
                withTimeout(confirmationTimeoutMs) {
                    deferred.await()
                }
            }
        } catch (_: TimeoutCancellationException) {
            false
        } catch (_: Exception) {
            false
        } finally {
            pending.remove(sessionId)
        }
    }

    /**
     * Request user confirmation for a **file operation outside the workspace**.
     *
     * Convenience wrapper around [requestConfirmationBlocking] with
     * `action = "file_access"` and a human-readable description built
     * from the tool name, operation, and target path.
     *
     * @param sessionId        Agent session ID.
     * @param toolName         Tool name (e.g. "read_workspace_file").
     * @param operation        Operation verb (e.g. "read", "write", "grep").
     * @param targetPath       The absolute path outside the workspace.
     * @return `true` if approved, `false` if denied or timed out.
     */
    fun requestFileAccessConfirmationBlocking(
        sessionId: String,
        toolName: String,
        operation: String,
        targetPath: String
    ): Boolean {
        if (sessionId.isBlank()) return false

        val requestId = UUID.randomUUID().toString()
        val confirmation = PendingConfirmation(
            requestId = requestId,
            sessionId = sessionId,
            toolName = toolName,
            command = "$operation $targetPath",
            workingDirectory = "(outside workspace)",
            timeout = 0,
            action = "file_access",
            targetPath = targetPath
        )
        val deferred = CompletableDeferred<Boolean>()
        pending[sessionId] = PendingEntry(confirmation, deferred)

        return try {
            runBlocking {
                withTimeout(DEFAULT_CONFIRMATION_TIMEOUT_MS) {
                    deferred.await()
                }
            }
        } catch (_: TimeoutCancellationException) {
            false
        } catch (_: Exception) {
            false
        } finally {
            pending.remove(sessionId)
        }
    }

    /**
     * Respond to a pending confirmation request.
     *
     * Called by the HTTP route when the user clicks Approve/Deny in the
     * frontend. Completes the deferred with the user's decision.
     *
     * @return `true` if the response was delivered (a pending request
     *         existed and was not already completed), `false` otherwise.
     */
    fun respond(sessionId: String, approved: Boolean): Boolean {
        val entry = pending[sessionId] ?: return false
        return entry.deferred.complete(approved)
    }

    /**
     * Get the current pending confirmation for a session, if any.
     *
     * Used by the session status route to include the confirmation
     * in the response so the frontend can detect it via polling.
     */
    fun getPending(sessionId: String): PendingConfirmation? =
        pending[sessionId]?.confirmation

    /**
     * Snapshot of **every** session that currently has a pending
     * confirmation, keyed by sessionId.
     *
     * Used by the global `/agent/pending-confirmations` poll to surface
     * confirmation requests to the user **even when they are viewing a
     * different session** — most importantly when a sub-agent
     * (`spawn_sub_agent` / `RunFreeAnalyzersTool`) is the one blocked
     * on confirmation.  Without this, the user on the root session
     * would never see the modal and the request would silently
     * time out after 5 minutes, with the only visible trace being
     * the worker's stdout log file (which the user perceives as
     * "the request was redirected to stdio").
     */
    fun getAllPending(): Map<String, PendingConfirmation> =
        pending.entries.associate { (sid, entry) -> sid to entry.confirmation }

    /**
     * Cancel and clear any pending confirmation for a session.
     *
     * Called when a session is cancelled or deleted to ensure the
     * blocked tool thread is released.
     */
    fun clear(sessionId: String) {
        val entry = pending.remove(sessionId)
        entry?.deferred?.complete(false)
    }

    /** Number of sessions currently awaiting confirmation. */
    fun pendingCount(): Int = pending.size
}

// ============================================================
//  Cross-process confirmation helpers
// ============================================================

/**
 * Request user confirmation via HTTP callback to the AkibaServer.
 *
 * Used when a tool executes in a **separate worker process** and needs
 * to reach the server's [ConfirmationManager] (which lives in the
 * server process). The worker POSTs to the server's
 * `POST /agent/internal/confirmation/request` endpoint and blocks on
 * the HTTP response (long-poll) until the user responds.
 *
 * This is a generalised version of the function that was previously
 * in `RunShellTool.kt`. It now supports both `shell_command` and
 * `file_access` action types.
 *
 * @return `true` if the user approved, `false` if denied, timed out,
 *         or the HTTP call failed.
 */
fun requestConfirmationViaHttp(
    serverPort: Int,
    sessionId: String,
    toolName: String,
    command: String,
    workingDirectory: String,
    timeout: Int,
    action: String = "shell_command",
    targetPath: String? = null
): Boolean {
    val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
    val bodyMap = mutableMapOf<String, Any>(
        "sessionId" to sessionId,
        "toolName" to toolName,
        "command" to command,
        "workingDirectory" to workingDirectory,
        "timeout" to timeout,
        "action" to action
    )
    if (targetPath != null) bodyMap["targetPath"] = targetPath
    val body = mapper.writeValueAsString(bodyMap)
    return try {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:$serverPort/api/agent/internal/confirmation/request"))
            .timeout(Duration.ofMinutes(6))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()
            .send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return false
        val result = mapper.readValue(response.body(), Map::class.java)
        result["approved"] == true
    } catch (_: Exception) {
        false
    }
}

/**
 * Detect whether the current process is a manual-agent worker and, if
 * so, return the server's HTTP port (from the `AKIBA_MANUAL_AGENT_SERVER_PORT`
 * environment variable set by `AgentRoutes.runManualAgentWorker`).
 *
 * Returns `null` when not in worker mode (tools execute in-process).
 */
fun detectWorkerServerPort(): Int? =
    System.getenv("AKIBA_MANUAL_AGENT_SERVER_PORT")?.toIntOrNull()
