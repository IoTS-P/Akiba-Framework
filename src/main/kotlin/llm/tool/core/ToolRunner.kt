package org.iotsplab.akiba.llm.tool.core

import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.iotsplab.akiba.managers.ConfigManager
import org.iotsplab.akiba.utils.AgentTuning

/**
 * Runs one tool call under a time limit, and lets a person cut it short.
 *
 * A tool is a plain lambda, so it blocks whichever thread calls it: a coroutine timeout
 * cannot stop it. Every call therefore runs on its own virtual thread, and when the limit
 * passes the call is abandoned and its thread interrupted. A tool that ignores interrupts
 * keeps its thread, but the agent gets an answer back and carries on instead of waiting.
 *
 * A call may ask for more than the default limit — a long analysis, a slow emulator. Above
 * the configured threshold that is the user's decision, not the agent's, so the call waits
 * for an approval first and is refused when there is none.
 */
object ToolRunner {

    /** What the agent is told, and how long it took. */
    data class Outcome(val output: String, val durationMs: Long)

    private val threads = Executors.newVirtualThreadPerTaskExecutor()
    private val running = ConcurrentHashMap<String, Future<*>>()
    private val interrupted = ConcurrentHashMap.newKeySet<String>()

    private const val POLL_INTERVAL_MS = 1500L

    private val policy: AgentTuning
        get() = if (ConfigManager.isConfigInitialized) {
            ConfigManager.config.agent ?: AgentTuning()
        } else {
            AgentTuning()
        }

    /** Seconds this call may take: what the tool asked for, otherwise the default. */
    fun limitSeconds(tool: Tool, arguments: Map<String, Any?>): Int {
        val tuning = policy
        val asked = tool.requestedTimeoutSeconds(arguments) ?: tuning.toolTimeoutSeconds
        return asked.coerceIn(1, tuning.maxToolTimeoutSeconds)
    }

    /**
     * Runs [tool] with [arguments], refusing calls that ask for more time than the user
     * allowed to hand out without being asked.
     */
    fun run(tool: Tool, arguments: Map<String, Any?>, sessionId: String?): Outcome {
        val seconds = limitSeconds(tool, arguments)
        deniedBecause(tool, seconds, sessionId)?.let { return Outcome(it, 0L) }

        val startedAt = System.currentTimeMillis()
        val future = threads.submit<String> { tool.safeExecute(arguments) }
        if (sessionId != null) {
            running[sessionId] = future
            watchForInterrupt(sessionId, future)
        }
        return try {
            val output = future.get(seconds.toLong(), TimeUnit.SECONDS)
            Outcome(output, System.currentTimeMillis() - startedAt)
        } catch (_: TimeoutException) {
            future.cancel(true)
            Outcome(ranOutOfTime(tool, seconds), System.currentTimeMillis() - startedAt)
        } catch (_: CancellationException) {
            val cut = sessionId != null && interrupted.remove(sessionId)
            Outcome(if (cut) cutByUser(tool) else "$tool.name was cancelled", System.currentTimeMillis() - startedAt)
        } catch (e: ExecutionException) {
            Outcome("Tool '${tool.name}' failed: ${e.cause?.message ?: e.message}", System.currentTimeMillis() - startedAt)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Outcome(cutByUser(tool), System.currentTimeMillis() - startedAt)
        } finally {
            if (sessionId != null) running.remove(sessionId, future)
        }
    }

    /**
     * Stops the call this session is running, if there is one.
     *
     * The tool is interrupted rather than killed — code that blocks on a process or a
     * sleep stops, code that never checks simply finishes on its own thread.
     */
    fun interrupt(sessionId: String): Boolean {
        val future = running[sessionId] ?: return false
        interrupted.add(sessionId)
        future.cancel(true)
        return true
    }

    /** True when this session has a tool call in flight. */
    fun isRunning(sessionId: String): Boolean = running.containsKey(sessionId)

    /**
     * In a worker process the tool call lives here, but the press happens in the server, so
     * the wish has to be fetched. In the server itself [interrupt] reaches us directly.
     */
    private fun watchForInterrupt(sessionId: String, future: Future<*>) {
        val port = detectWorkerServerPort() ?: return
        Thread.ofVirtual().start {
            while (!future.isDone) {
                if (pollInterrupt(port, sessionId)) {
                    interrupted.add(sessionId)
                    future.cancel(true)
                    return@start
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }
    }

    private fun pollInterrupt(serverPort: Int, sessionId: String): Boolean = try {
        val request = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create("http://127.0.0.1:$serverPort/api/agent/internal/tool/interrupt?sessionId=$sessionId"))
            .timeout(java.time.Duration.ofSeconds(5))
            .GET()
            .build()
        val response = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build()
            .send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
        response.statusCode() == 200 && response.body().contains("true")
    } catch (_: Exception) {
        false
    }

    private fun deniedBecause(tool: Tool, seconds: Int, sessionId: String?): String? {
        val tuning = policy
        if (seconds <= tuning.toolTimeoutConfirmThresholdSeconds) return null

        val window = tuning.toolTimeoutApprovalSeconds.coerceAtLeast(1)
        val minutes = seconds / 60
        val startedAt = System.currentTimeMillis()
        val approved = sessionId != null && askForApproval(
            sessionId = sessionId,
            tool = tool,
            minutes = minutes,
            seconds = seconds,
            windowSeconds = window,
        )
        if (approved) return null

        val waited = (System.currentTimeMillis() - startedAt) / 1000
        val why = if (waited < window - 2) {
            "The user declined this call when asked."
        } else {
            "Nobody answered within ${window}s when asked, so it counts as declined."
        }
        return "$why '${tool.name}' asked to run for up to $minutes minutes, which is over the " +
            "${tuning.toolTimeoutConfirmThresholdSeconds / 60}-minute limit that needs approval. " +
            "Nothing was run. Either ask for less time or explain why this call needs that long."
    }

    private fun askForApproval(
        sessionId: String,
        tool: Tool,
        minutes: Int,
        seconds: Int,
        windowSeconds: Int,
    ): Boolean {
        val command = "'${tool.name}' for up to $minutes minutes"
        val detail = "This call asked for a ${seconds}s timeout, over the approval limit."
        val workerPort = detectWorkerServerPort()
        return if (workerPort != null) {
            requestConfirmationViaHttp(
                serverPort = workerPort,
                sessionId = sessionId,
                toolName = tool.name,
                command = command,
                workingDirectory = detail,
                timeout = seconds,
                action = "long_timeout",
            )
        } else {
            ConfirmationManager.requestConfirmationBlocking(
                sessionId = sessionId,
                toolName = tool.name,
                command = command,
                workingDirectory = detail,
                timeout = seconds,
                action = "long_timeout",
                confirmationTimeoutMs = windowSeconds * 1000L,
            )
        }
    }

    private fun ranOutOfTime(tool: Tool, seconds: Int): String =
        "Tool '${tool.name}' ran past its ${seconds}s limit and was interrupted. " +
            "Whatever it produced may be incomplete. If the work genuinely needs longer, " +
            "say so and ask for a bigger timeout — above the approval limit that is the user's call."

    private fun cutByUser(tool: Tool): String =
        "The user interrupted '${tool.name}' while it was running. It was asked to stop, so its " +
            "output may be missing or partial — do not assume the work was done."
}
