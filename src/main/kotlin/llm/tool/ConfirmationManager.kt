package org.iotsplab.akiba.llm.tool

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
            timeout = timeout
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
            timeout = timeout
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
