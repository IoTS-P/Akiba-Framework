package org.iotsplab.akiba.llm.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.runBlocking
import org.iotsplab.akiba.llm.agent.AgentModule
import org.iotsplab.akiba.llm.agent.AgentRuntime
import org.iotsplab.akiba.llm.agent.AwaitPredicate
import org.iotsplab.akiba.llm.agent.RuntimeState

// ============================================================
//  AwaitAgentTool — wait for a child Job's runtime state
// ============================================================
//
// Companion to `spawn_sub_agent` (template or freeform mode). The
// parent calls this after a spawn to block until the child reaches
// a chosen state.
//
// Supported `until` values:
//   - `closed`     (terminal — most common for one-shot children)
//   - `standby`    (lifecycle=standby child parked for mailbox)
//   - `msghandle`  (currently in a mailbox-handling run)
//   - `cancelling` (grace period in progress)
//   - `running`    (active run in progress — unusual target)
//   - `idle`       (standby OR closed — convenience)
//   - `any_live`   (any non-closed state)
//   - `terminal`   (== closed)

/**
 * Build the await tool. The runtime is looked up per-binary
 * lazily; if no handle is currently registered for [childId] in
 * the in-process scheduler we still return a structured response
 * by reading the DB state, so the tool works after a process
 * restart as long as the child session row still exists.
 */
fun AwaitAgentTool(
    parent: AgentModule,
    agentDbClient: org.iotsplab.akiba.data.database.AgentDatabaseClient,
): Tool = Tool(
    name = "await_agent",
    description = buildString {
        appendLine("Wait for an async child agent to reach a target runtime state.")
        appendLine()
        appendLine("Required:")
        appendLine("  childId    — sessionId returned by spawn_sub_agent")
        appendLine()
        appendLine("Optional:")
        appendLine("  until      — target state. One of: closed | standby | msghandle | cancelling |")
        appendLine("               running | idle (=standby|closed) | any_live | terminal. Default 'closed'.")
        appendLine("  timeoutMs  — max wait. Default 600000 (10 minutes). Pass 0 to wait forever.")
        appendLine("  pollMs     — poll interval. Default 250.")
        appendLine()
        appendLine("Returns `{finalState, timedOut, elapsedMs, lastError}`.")
        appendLine("If the child is not registered in this process (e.g. spawned in another process),")
        appendLine("the tool still resolves by polling the database — useful for cross-process awaits.")
    },
    parameters = listOf(
        ToolParameter(
            "childId", "string",
            "Child session id (UUID). Required.",
            required = true,
        ),
        ToolParameter(
            "until", "string",
            "Target state name (see description). Default 'closed'.",
            required = false,
            enum = listOf(
                "closed", "standby", "msghandle", "cancelling", "running",
                "idle", "any_live", "terminal",
            ),
        ),
        ToolParameter(
            "timeoutMs", "integer",
            "Max wait in milliseconds. Default 600000. 0 = no timeout.",
            required = false,
        ),
        ToolParameter(
            "pollMs", "integer",
            "Poll interval in milliseconds. Default 250.",
            required = false,
        ),
    ),
) { args ->
    val childId = (args["childId"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        ?: return@Tool "Error: 'childId' is required"
    val until = (args["until"] as? String)?.lowercase()?.takeIf { it.isNotEmpty() } ?: "closed"
    val timeoutMs = (args["timeoutMs"] as? Number)?.toLong()?.coerceAtLeast(0L) ?: 600_000L
    val pollMs = (args["pollMs"] as? Number)?.toLong()?.coerceIn(50L, 10_000L) ?: 250L

    val predicate: (RuntimeState) -> Boolean = when (until) {
        "closed", "terminal" -> AwaitPredicate.TERMINAL
        "standby" -> AwaitPredicate.ofState(RuntimeState.STANDBY)
        "msghandle" -> AwaitPredicate.ofState(RuntimeState.MSGHANDLE)
        "cancelling" -> AwaitPredicate.ofState(RuntimeState.CANCELLING)
        "running" -> AwaitPredicate.ofState(RuntimeState.RUNNING)
        "idle" -> AwaitPredicate.IDLE_OR_TERMINAL
        "any_live" -> { s: RuntimeState -> s != RuntimeState.CLOSED && s != RuntimeState.ERROR }
        else -> { _: RuntimeState -> false }
    }
    if (until != "closed" && until != "standby" && until != "msghandle" &&
        until != "cancelling" && until != "running" && until != "idle" &&
        until != "any_live" && until != "terminal"
    ) {
        return@Tool "Error: unknown 'until' value '$until'"
    }

    // 1. In-process: ask the scheduler directly.
    val runtime = AgentRuntime.forBinary(parent.id, agentDbClient)
    val handle = runtime.scheduler.get(childId)
    if (handle != null) {
        val effTimeout = if (timeoutMs == 0L) null else timeoutMs
        val result = runBlocking { handle.await(predicate, timeoutMs = effTimeout, pollIntervalMs = pollMs) }
        return@Tool jacksonObjectMapper().writeValueAsString(mapOf(
            "source" to "in_process",
            "childId" to childId,
            "until" to until,
            "finalState" to result.finalState.wire(),
            "timedOut" to result.timedOut,
            "elapsedMs" to result.elapsedMs,
            "lastError" to result.lastError,
        ))
    }

    // 2. Cross-process: poll the DB.
    val deadline = if (timeoutMs == 0L) Long.MAX_VALUE
    else System.currentTimeMillis() + timeoutMs
    val start = System.currentTimeMillis()
    var lastState: RuntimeState? = null
    while (System.currentTimeMillis() < deadline) {
        val row: org.iotsplab.akiba.data.database.AgentDatabaseClient.RuntimeStateInfo? = try {
            agentDbClient.getRuntimeState(childId)
        } catch (e: Exception) {
            return@Tool "Error: getRuntimeState failed: ${e.message ?: e.javaClass.simpleName}"
        }
        if (row == null) return@Tool "Error: child session '$childId' not found"
        val s = RuntimeState.fromWire(row.runtimeState)
        if (s == null) {
            return@Tool "Error: child session has unknown runtime_state '${row.runtimeState}'"
        }
        lastState = s
        if (predicate(s)) {
            return@Tool jacksonObjectMapper().writeValueAsString(mapOf(
                "source" to "db_poll",
                "childId" to childId,
                "until" to until,
                "finalState" to s.wire(),
                "timedOut" to false,
                "elapsedMs" to System.currentTimeMillis() - start,
                "lastError" to row.closingReason,
            ))
        }
        try { Thread.sleep(pollMs) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }
    jacksonObjectMapper().writeValueAsString(mapOf(
        "source" to "db_poll",
        "childId" to childId,
        "until" to until,
        "finalState" to (lastState?.wire() ?: "unknown"),
        "timedOut" to true,
        "elapsedMs" to System.currentTimeMillis() - start,
        "lastError" to null,
    ))
}
