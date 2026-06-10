package org.iotsplab.akiba.server.routes

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton manager for tracking import / workflow task progress.
 *
 * The flow:
 * 1. Server launches a child process with AKIBA_PROGRESS_URL and AKIBA_PROGRESS_TOKEN env vars
 * 2. Child process HTTP POSTs progress messages to POST /api/progress
 * 3. This manager receives the messages and pushes them to per-task SharedFlows
 * 4. The flows are consumed by SSE endpoints; multiple consumers can subscribe independently
 *
 * Messages received via [onProgress] are also written to the optional log file
 * (set via [registerTask]) for persistence — this allows new SSE subscribers
 * to read historical messages by fetching the log file first.
 */
object ProgressManager {

    /** Per-task flows: taskId → MutableSharedFlow<String> */
    private val flows = ConcurrentHashMap<String, MutableSharedFlow<String>>()

    /** Valid tokens for this server session: token → taskId */
    private val tokens = ConcurrentHashMap<String, String>()

    /** Task statuses: taskId → "running"|"completed"|"failed" */
    private val statuses = ConcurrentHashMap<String, String>()

    /** Task results: taskId → result message */
    private val results = ConcurrentHashMap<String, String>()

    /** Optional persistent log file per task */
    private val logFiles = ConcurrentHashMap<String, Path>()

    /**
     * Register a new task.
     * @param logFile Optional path to a file where ALL progress messages
     *                (both from stdout and from HTTP POST) will be persisted.
     * @return The progress token that will be passed to the child process.
     */
    fun registerTask(taskId: String, logFile: Path? = null): String {
        val token = java.util.UUID.randomUUID().toString()
        tokens[token] = taskId
        flows[taskId] = MutableSharedFlow<String>(
            replay = 0,
            extraBufferCapacity = 1024,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        if (logFile != null) {
            Files.createDirectories(logFile.parent)
            logFiles[taskId] = logFile
        }
        statuses[taskId] = "running"
        return token
    }

    /**
     * Feed a progress message into the task's SharedFlow.
     * Called by the POST /progress endpoint after token validation.
     * Also persists to the log file if configured.
     */
    fun onProgress(token: String, message: String): Boolean {
        val taskId = tokens[token] ?: return false
        flows[taskId]?.tryEmit(message)
        // Persist to log file so new SSE subscribers can read history
        logFiles[taskId]?.let { f ->
            try {
                Files.writeString(f, message + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            } catch (_: Exception) { }
        }
        return true
    }

    /**
     * Update the status of a task (running/completed/failed).
     */
    fun updateStatus(token: String, status: String): Boolean {
        val taskId = tokens[token] ?: return false
        statuses[taskId] = status
        val msg = "[STATUS] $status"
        flows[taskId]?.tryEmit(msg)
        logFiles[taskId]?.let { f ->
            try {
                Files.writeString(f, msg + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            } catch (_: Exception) { }
        }
        return true
    }

    /**
     * Set the final result for a task.
     */
    fun setResult(token: String, result: String): Boolean {
        val taskId = tokens[token] ?: return false
        results[taskId] = result
        val msg = "[RESULT] $result"
        flows[taskId]?.tryEmit(msg)
        logFiles[taskId]?.let { f ->
            try {
                Files.writeString(f, msg + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            } catch (_: Exception) { }
        }
        return true
    }

    /** Get a task's SharedFlow for SSE consumption. */
    fun getFlow(taskId: String): SharedFlow<String>? = flows[taskId]?.asSharedFlow()

    /** Get a task's current status. */
    fun getStatus(taskId: String): String = statuses[taskId] ?: "unknown"

    /** Get a task's result. */
    fun getResult(taskId: String): String? = results[taskId]

    /** Clean up a completed/failed task. */
    fun finish(taskId: String) {
        flows.remove(taskId)
        tokens.values.removeAll { it == taskId }
        statuses.remove(taskId)
        results.remove(taskId)
        logFiles.remove(taskId)
    }
}
