package org.iotsplab.akiba.llm.tool.core

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import org.iotsplab.akiba.utils.AgentSettings
import org.iotsplab.akiba.utils.GlobalConfig

/**
 * Runs one tool call under a time limit, and lets a person cut it short.
 *
 * A tool is a plain lambda, so it blocks whichever thread runs it — a coroutine cannot
 * suspend inside it. [runInterruptible] is the bridge: the block still blocks a thread, but
 * cancelling the coroutine interrupts that thread, which is what stops `waitFor`, a socket
 * read, or a `sleep`. A tool that ignores interrupts keeps its thread; the agent is not
 * waiting on it any more, so the turn carries on.
 *
 * A call may ask for more than the default limit — a long build, a slow emulator. Above the
 * configured threshold that is the user's decision, not the agent's, so the call waits for
 * an approval first and is refused when none comes.
 */
object ToolRunner {

    /** What the agent is told, and how long it took. */
    data class Outcome(val output: String, val durationMs: Long)

    private val running = ConcurrentHashMap<String, Deferred<*>>()
    private val interrupted = ConcurrentHashMap.newKeySet<String>()

    private val policy: AgentSettings
        get() = GlobalConfig.load().agent

    /** Seconds this call may take: what the tool asked for, otherwise the default. */
    fun limitSeconds(tool: Tool, arguments: Map<String, Any?>): Int {
        val settings = policy
        val asked = tool.requestedTimeoutSeconds(arguments) ?: settings.toolTimeoutSeconds
        return asked.coerceIn(1, settings.maxToolTimeoutSeconds)
    }

    /**
     * Runs [tool] with [arguments], refusing calls that ask for more time than the user
     * allowed to hand out without being asked.
     */
    suspend fun run(tool: Tool, arguments: Map<String, Any?>, sessionId: String?): Outcome {
        val seconds = limitSeconds(tool, arguments)
        deniedBecause(tool, seconds, sessionId)?.let { return Outcome(it, 0L) }

        val startedAt = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - startedAt

        // supervisorScope: stopping one call must not take the turn down with it.
        return supervisorScope {
            val work = async(Dispatchers.IO) {
                runInterruptible { tool.safeExecute(arguments) }
            }
            if (sessionId != null) running[sessionId] = work
            try {
                val output = withTimeoutOrNull(seconds * 1000L) { work.await() }
                if (output != null) {
                    Outcome(output, elapsed())
                } else {
                    // The work is abandoned, not merely ignored: cancel interrupts the thread.
                    work.cancel()
                    Outcome(ranOutOfTime(tool, seconds), elapsed())
                }
            } catch (_: CancellationException) {
                val cut = sessionId != null && interrupted.remove(sessionId)
                Outcome(if (cut) cutByUser(tool) else "Tool '${tool.name}' was cancelled", elapsed())
            } finally {
                if (sessionId != null) running.remove(sessionId)
            }
        }
    }

    /**
     * Stops the call this session is running, if there is one.
     *
     * The tool is interrupted rather than killed — code that blocks on a process or a sleep
     * stops, code that never checks simply finishes on its own thread.
     */
    fun interrupt(sessionId: String): Boolean {
        val work = running[sessionId] ?: return false
        interrupted.add(sessionId)
        work.cancel()
        return true
    }

    /** True when this session has a tool call in flight. */
    fun isRunning(sessionId: String): Boolean = running.containsKey(sessionId)

    private fun deniedBecause(tool: Tool, seconds: Int, sessionId: String?): String? {
        val settings = policy
        if (seconds <= settings.toolTimeoutConfirmThresholdSeconds) return null

        val window = settings.toolTimeoutApprovalSeconds.coerceAtLeast(1)
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
            "${settings.toolTimeoutConfirmThresholdSeconds / 60}-minute limit that needs approval. " +
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
