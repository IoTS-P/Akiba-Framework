package org.iotsplab.akiba.utils

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Generic progress reporter that sends structured status messages to the
 * parent server process via HTTP.
 *
 * ### How it works
 *
 * When the server spawns a child process (e.g. an import subprocess or a
 * workflow task), it sets two environment variables:
 * - `AKIBA_PROGRESS_URL`   — e.g. `http://127.0.0.1:8080/api/progress`
 * - `AKIBA_PROGRESS_TOKEN` — unique token identifying this task
 *
 * Any code that wants to report progress simply calls:
 * ```kotlin
 * ProgressReporter.report("Importing file 3/5")
 * ```
 *
 * If the environment variables are not set (e.g. CLI-only mode without a
 * server), the call is silently ignored — no progress is reported.
 *
 * ### JSON payload format
 *
 * ```json
 * {"token": "<token>", "type": "progress", "message": "..."}
 * ```
 *
 * The server's `POST /api/progress` endpoint validates the token and pushes
 * the message into the corresponding SSE stream for the frontend.
 */
object ProgressReporter {

    private val mapper = jacksonObjectMapper()
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    /** Whether a progress URL has been configured (env var set). */
    val isActive: Boolean by lazy {
        System.getenv("AKIBA_PROGRESS_URL") != null
    }

    /** Progress URL extracted from env, cached after first read. */
    private val progressUrl: String? by lazy {
        System.getenv("AKIBA_PROGRESS_URL")?.trim()?.ifBlank { null }
    }

    /** Progress token extracted from env, cached after first read. */
    private val progressToken: String? by lazy {
        System.getenv("AKIBA_PROGRESS_TOKEN")?.trim()?.ifBlank { null }
    }

    /**
     * Send a progress message to the parent server.
     *
     * This is a **best-effort** fire-and-forget call:
     * - Returns immediately if no progress URL is configured.
     * - Swallows all exceptions silently so progress reporting never
     *   blocks the caller's business logic.
     *
     * @param message Human-readable progress description.
     * @param type    Message type: `"progress"` (default), `"status"`, or `"result"`.
     */
    fun report(message: String, type: String = "progress") {
        val url = progressUrl ?: return
        val token = progressToken ?: return

        try {
            val body = mapper.writeValueAsString(mapOf(
                "token" to token,
                "type" to type,
                "message" to message
            ))
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(5))
                .build()
            client.send(request, HttpResponse.BodyHandlers.discarding())
        } catch (_: Exception) {
            // Best-effort: never let progress reporting disrupt the actual work.
        }
    }

    /**
     * Report a final result message.
     * Shorthand for `report(message, type = "result")`.
     */
    fun result(message: String) = report(message, type = "result")

    /**
     * Update task status.
     * Shorthand for `report(message, type = "status")`.
     * Valid status values: `"running"`, `"completed"`, `"failed"`.
     */
    fun status(message: String) = report(message, type = "status")
}
