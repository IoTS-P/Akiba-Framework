package org.iotsplab.akiba.server.routes

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton manager for tracking import task progress.
 *
 * The flow:
 * 1. Server launches a child process with AKIBA_PROGRESS_URL and AKIBA_PROGRESS_TOKEN env vars
 * 2. Child process HTTP POSTs progress messages to POST /api/progress
 * 3. This manager receives the messages and pushes them to per-task channels
 * 4. The channels are consumed by the SSE endpoint (/api/files/import/stream/{taskId})
 */
object ProgressManager {

    /** Per-task channels: taskId → Channel<String> */
    private val channels = ConcurrentHashMap<String, Channel<String>>()

    /** Valid tokens for this server session: token → taskId */
    private val tokens = ConcurrentHashMap<String, String>()

    /** Task statuses: taskId → "running"|"completed"|"failed" */
    private val statuses = ConcurrentHashMap<String, String>()

    /** Task results: taskId → result message */
    private val results = ConcurrentHashMap<String, String>()

    /**
     * Register a new import task.
     * @return The progress token that will be passed to the child process.
     */
    fun registerTask(taskId: String): String {
        val token = java.util.UUID.randomUUID().toString()
        tokens[token] = taskId
        channels[taskId] = Channel<String>(Channel.BUFFERED)
        statuses[taskId] = "running"
        return token
    }

    /**
     * Feed a progress message into the task's channel.
     * Called by the POST /progress endpoint after token validation.
     */
    fun onProgress(token: String, message: String): Boolean {
        val taskId = tokens[token] ?: return false
        channels[taskId]?.trySend(message)
        return true
    }

    /**
     * Update the status of a task (running/completed/failed).
     */
    fun updateStatus(token: String, status: String): Boolean {
        val taskId = tokens[token] ?: return false
        statuses[taskId] = status
        channels[taskId]?.trySend("[STATUS] $status")
        return true
    }

    /**
     * Set the final result for a task.
     */
    fun setResult(token: String, result: String): Boolean {
        val taskId = tokens[token] ?: return false
        results[taskId] = result
        channels[taskId]?.trySend("[RESULT] $result")
        return true
    }

    /** Get a task's channel for SSE consumption. */
    fun getChannel(taskId: String): Channel<String>? = channels[taskId]

    /** Get a task's current status. */
    fun getStatus(taskId: String): String = statuses[taskId] ?: "unknown"

    /** Get a task's result. */
    fun getResult(taskId: String): String? = results[taskId]

    /** Clean up a completed/failed task. */
    fun finish(taskId: String) {
        channels.remove(taskId)
        tokens.values.removeAll { it == taskId }
        statuses.remove(taskId)
        results.remove(taskId)
    }
}
