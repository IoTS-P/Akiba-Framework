@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.iotsplab.akiba.llm.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.agent.AgentModule
import org.iotsplab.akiba.llm.agent.AgentRuntime
import org.iotsplab.akiba.llm.agent.AwaitPredicate
import org.iotsplab.akiba.llm.agent.JobHandle
import org.iotsplab.akiba.llm.agent.RuntimeState

// ============================================================
//  AwaitMultipleChildrenTool — batch-wait for N child agents
// ============================================================
//
// Companion to `spawn_sub_agent` and `await_agent`.  Lets a
// parent agent fan out several sub-agents and wait for them in
// one tool call instead of N serial `await_agent` calls.  The
// tool supports two modes:
//
//   - `any`  : return as soon as at least one child reaches the
//              target state.  The remaining children are still
//              running; the response surfaces them in `pending`
//              so the parent can call the tool again (with the
//              pending list) to wait for the next batch.
//   - `all`  : wait until every child reaches the target state.
//
// Internally the tool is two-stage:
//
//   1. **In-process children** (registered in the current
//      JVM's scheduler): each handle's `state` flow is
//      collected in parallel with `select` so the first
//      `any`-mode child resolves immediately.  Sibling awaits
//      are cancelled in `any` mode, kept in `all` mode.
//
//   2. **Cross-process children** (registered in a different
//      process or already closed in memory but the DB row
//      still exists): the tool polls the DB column once per
//      tick, same shape as `await_agent`.
//
// Both stages share the same predicate and timeout.

/**
 * Build the batch-await tool.  See [AwaitAgentTool] for the
 * single-child equivalent.
 */
fun AwaitMultipleChildrenTool(
    parent: AgentModule,
    agentDbClient: AgentDatabaseClient,
    runtimeOverride: AgentRuntime? = null,
): Tool = Tool(
    name = "await_multiple_children",
    description = buildString {
        appendLine("Wait for MULTIPLE async child agents to reach a target runtime state")
        appendLine("in a single tool call — the LLM-friendly alternative to serial `await_agent`")
        appendLine("calls when several sub-agents are in flight.")
        appendLine()
        appendLine("Required:")
        appendLine("  childIds  — JSON array or comma-separated list of child sessionIds")
        appendLine("              returned by `spawn_sub_agent` (or another spawn tool).")
        appendLine()
        appendLine("Optional:")
        appendLine("  mode      — 'any' (default) | 'all' | 'any_idle' | 'all_idle'.")
        appendLine("              'any'    : return as soon as AT LEAST ONE child reaches the target state.")
        appendLine("              'all'    : wait until EVERY child reaches the target state.")
        appendLine("              'any_idle': return as soon as any child enters STANDBY or CLOSED.")
        appendLine("              'all_idle': wait until every child is in STANDBY or CLOSED.")
        appendLine("  until     — target state name (same enum as `await_agent`).")
        appendLine("              Default 'closed' for `any`/`all` modes, 'idle' for `*_idle` modes.")
        appendLine("  timeoutMs — max wait for the FIRST settle. Default 600000 (10 min).")
        appendLine("              Pass 0 to wait forever.  In `all` mode the timeout is the total")
        appendLine("              budget; in `any` mode it's the first-settle budget.")
        appendLine("  pollMs    — DB poll interval for cross-process children. Default 250.")
        appendLine()
        appendLine("Returns:")
        appendLine("  status     — 'ok' (at least one child settled) | 'all_terminal' (every")
        appendLine("                child in the call reached the target) | 'timeout' | 'error'.")
        appendLine("  completed  — array of `{childId, finalState, elapsedMs, lastError, source}`")
        appendLine("                for the children that reached the target.")
        appendLine("  pending    — array of `{childId, runtimeState, elapsedMs}` for children that")
        appendLine("                had not yet reached the target when the tool returned.")
        appendLine("  hint       — when pending is non-empty, suggests calling the tool again with")
        appendLine("                the pending childIds.")
        appendLine()
        appendLine("The order of `completed` and `pending` is the same as the input `childIds`,")
        appendLine("so the parent can keep a stable mapping across multiple calls.")
    },
    parameters = listOf(
        ToolParameter(
            "childIds", "string",
            "JSON array (e.g. `[\"u1\",\"u2\"]`) or comma-separated list of child sessionIds. " +
                "Required.",
            required = true,
        ),
        ToolParameter(
            "mode", "string",
            "Wait strategy. One of: any | all | any_idle | all_idle. Default 'any'.",
            required = false,
            enum = listOf("any", "all", "any_idle", "all_idle"),
        ),
        ToolParameter(
            "until", "string",
            "Target state name. Same enum as `await_agent`. Default depends on mode.",
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
            "DB poll interval for cross-process children. Default 250.",
            required = false,
        ),
    ),
    execute = { args -> handleAwaitMany(args, parent, agentDbClient, runtimeOverride) },
)/**
 * Internal: mode + default-until combo.  Encapsulates the
 * "if you said any_idle, 'until' defaults to 'idle'" rule so
 * the LLM does not have to remember it.
 */
private enum class AwaitManyMode(
    val wire: String,
    val defaultUntil: String,
) {
    ANY("any", "closed"),
    ALL("all", "closed"),
    ANY_IDLE("any_idle", "idle"),
    ALL_IDLE("all_idle", "idle");

    val isAny: Boolean get() = this == ANY || this == ANY_IDLE
    val isAll: Boolean get() = this == ALL || this == ALL_IDLE
}

/**
 * Result row for a single child, used to build both
 * `completed` and `pending` arrays.
 */
private data class ChildResult(
    val childId: String,
    val finalState: String,        // closed | standby | msghandle | cancelling | running | unknown
    val elapsedMs: Long,
    val lastError: String? = null,
    val source: String,             // "in_process" | "db_poll" | "db_initial" | "still_running"
    val settled: Boolean,           // true → in completed, false → in pending
)

/**
 * Top-level tool handler.  Parses args, fans out, builds the
 * response.
 */
private fun handleAwaitMany(
    args: Map<String, Any?>,
    parent: AgentModule,
    agentDbClient: AgentDatabaseClient,
    runtimeOverride: AgentRuntime?,
): String = runBlocking {
    val mapper = jacksonObjectMapper()
    val raw = args["childIds"]
        ?: return@runBlocking "Error: 'childIds' is required"
    val childIds = try {
        parseChildIds(raw)
    } catch (e: Exception) {
        return@runBlocking "Error: cannot parse 'childIds': ${e.message}"
    }
    if (childIds.isEmpty()) {
        return@runBlocking "Error: 'childIds' must contain at least one sessionId"
    }
    // de-duplicate, preserve order
    val orderedUnique = LinkedHashSet(childIds).toList()

    val modeRaw = (args["mode"] as? String)?.lowercase()?.takeIf { it.isNotEmpty() }
        ?: AwaitManyMode.ANY.wire
    val mode = try {
        AwaitManyMode.valueOf(modeRaw.uppercase())
    } catch (_: Exception) {
        return@runBlocking "Error: unknown 'mode' value '$modeRaw'. " +
            "Must be one of: ${AwaitManyMode.entries.joinToString { it.wire }}"
    }
    val untilRaw = (args["until"] as? String)?.lowercase()?.takeIf { it.isNotEmpty() }
        ?: mode.defaultUntil
    val predicate: (RuntimeState) -> Boolean = when (untilRaw) {
        "closed", "terminal" -> AwaitPredicate.TERMINAL
        "standby" -> AwaitPredicate.ofState(RuntimeState.STANDBY)
        "msghandle" -> AwaitPredicate.ofState(RuntimeState.MSGHANDLE)
        "cancelling" -> AwaitPredicate.ofState(RuntimeState.CANCELLING)
        "running" -> AwaitPredicate.ofState(RuntimeState.RUNNING)
        "idle" -> AwaitPredicate.IDLE_OR_TERMINAL
        "any_live" -> { s: RuntimeState -> s != RuntimeState.CLOSED && s != RuntimeState.ERROR }
        else -> { _: RuntimeState -> false }
    }
    if (predicate === AwaitPredicate.IDLE_OR_TERMINAL && untilRaw != "idle") {
        return@runBlocking "Error: unknown 'until' value '$untilRaw'"
    }

    val timeoutMs = (args["timeoutMs"] as? Number)?.toLong()?.coerceAtLeast(0L) ?: 600_000L
    val pollMs = (args["pollMs"] as? Number)?.toLong()?.coerceIn(50L, 10_000L) ?: 250L
    val effectiveTimeout = if (timeoutMs == 0L) null else timeoutMs

    val runtime: AgentRuntime = runtimeOverride
        ?: AgentRuntime.forBinary(parent.id, agentDbClient)

    val start = System.currentTimeMillis()

    val results: List<ChildResult> = runMany(
        runtime = runtime,
        agentDbClient = agentDbClient,
        childIds = orderedUnique,
        predicate = predicate,
        mode = mode,
        timeoutMs = effectiveTimeout,
        pollMs = pollMs,
    )

    val completed = results.filter { it.settled }
    val pending = results.filter { !it.settled }

    val status = when {
        // overall error: nothing settled and nothing pending → shouldn't happen
        completed.isEmpty() && pending.isEmpty() -> "error"
        // all done (mode=any: means at least one, mode=all: means every)
        mode.isAll && pending.isEmpty() -> "all_terminal"
        mode.isAny && pending.isEmpty() -> "all_terminal"  // every child settled anyway
        // any child in a failure state counts as completed
        completed.isEmpty() -> "timeout"
        else -> "ok"
    }

    val hint = if (pending.isNotEmpty()) {
        "Call again with childIds=${pending.map { it.childId }} to wait for the rest. " +
            "(${pending.size} of ${orderedUnique.size} still pending)"
    } else null

    val payload = mapOf(
        "status" to status,
        "mode" to mode.wire,
        "until" to untilRaw,
        "elapsedMs" to (System.currentTimeMillis() - start),
        "completedCount" to completed.size,
        "pendingCount" to pending.size,
        "completed" to completed.map { child ->
            mapOf(
                "childId" to child.childId,
                "finalState" to child.finalState,
                "elapsedMs" to child.elapsedMs,
                "lastError" to child.lastError,
                "source" to child.source,
            )
        },
        "pending" to pending.map { child ->
            mapOf(
                "childId" to child.childId,
                "runtimeState" to child.finalState,
                "elapsedMs" to child.elapsedMs,
            )
        },
        "hint" to hint,
    )
    mapper.writeValueAsString(payload)
}

/**
 * Core wait routine.  Splits children into in-process / DB-only
 * and runs the per-mode wait logic on each side.
 */
private suspend fun runMany(
    runtime: AgentRuntime,
    agentDbClient: AgentDatabaseClient,
    childIds: List<String>,
    predicate: (RuntimeState) -> Boolean,
    mode: AwaitManyMode,
    timeoutMs: Long?,
    pollMs: Long,
): List<ChildResult> = coroutineScope {
    val start = System.currentTimeMillis()

    // 1. Snapshot in-process handles; everything else is DB-only.
    val inProcess: List<Pair<String, JobHandle>> = childIds.mapNotNull { id ->
        runtime.scheduler.get(id)?.let { id to it }
    }
    val dbOnly: List<String> = childIds.filter { id -> inProcess.none { it.first == id } }

    val inProcessResults: List<ChildResult> = if (inProcess.isEmpty()) emptyList() else {
        awaitInProcess(inProcess, predicate, mode, timeoutMs, start)
    }
    val dbResults: List<ChildResult> = if (dbOnly.isEmpty()) emptyList() else {
        awaitCrossProcess(agentDbClient, dbOnly, predicate, mode, timeoutMs, pollMs, start)
    }

    // Merge, restore input order.
    val byId: Map<String, ChildResult> =
        (inProcessResults + dbResults).associateBy { it.childId }
    childIds.map { id ->
        byId[id] ?: ChildResult(
            childId = id,
            finalState = "unknown",
            elapsedMs = 0,
            source = "still_running",
            settled = false,
        )
    }
}

/**
 * Wait for in-process handles concurrently.  `any` mode uses
 * a `select` to resolve on the first; `all` mode joins all.
 *
 * In `any` mode the in-flight sibling awaits are NOT cancelled
 * when the first child resolves — the parent can re-call the
 * tool with the still-pending childIds, and the next call will
 * find those handles already settled.  Cancelling here would
 * lose track of which child actually completed and complicate
 * the "next round" flow.
 */
private suspend fun awaitInProcess(
    pairs: List<Pair<String, JobHandle>>,
    predicate: (RuntimeState) -> Boolean,
    mode: AwaitManyMode,
    timeoutMs: Long?,
    start: Long,
): List<ChildResult> = coroutineScope {
    val deferredById: Map<String, kotlinx.coroutines.Deferred<ChildResult>> = pairs.associate { (id, h) ->
        id to async {
            val res = h.await(predicate, timeoutMs = timeoutMs, pollIntervalMs = 250L)
            ChildResult(
                childId = id,
                finalState = res.finalState.wire(),
                elapsedMs = res.elapsedMs,
                lastError = res.lastError,
                source = "in_process",
                settled = !res.timedOut,
            )
        }
    }
    if (mode.isAny) {
        // Resolve on the first settled child. Pending awaits must be
        // cancelled; otherwise coroutineScope waits for them anyway and
        // `any` degenerates into `all` until timeout.
        select<Unit> {
            deferredById.forEach { (_, d) ->
                d.onAwait { /* first done */ }
            }
        }
        deferredById.map { (id, d) ->
            if (d.isCompleted) {
                d.getCompleted()
            } else {
                d.cancel()
                ChildResult(
                    childId = id,
                    finalState = "running",
                    elapsedMs = System.currentTimeMillis() - start,
                    source = "still_running",
                    settled = false,
                )
            }
        }
    } else {
        // mode.isAll: wait for every child.
        deferredById.values.map { d -> d.await() }
    }
}

/**
 * Wait for cross-process children by polling the DB.  Both
 * `any` and `all` use the same poll loop; the difference is
 * when the function returns.
 */
private suspend fun awaitCrossProcess(
    agentDbClient: AgentDatabaseClient,
    ids: List<String>,
    predicate: (RuntimeState) -> Boolean,
    mode: AwaitManyMode,
    timeoutMs: Long?,
    pollMs: Long,
    start: Long,
): List<ChildResult> {
    // Read initial state so even a "timed out" call reports
    // current runtime state per child.
    val rowsById: Map<String, AgentDatabaseClient.RuntimeStateInfo?> =
        ids.associateWith { id ->
            try { agentDbClient.getRuntimeState(id) } catch (_: Exception) { null }
        }
    val initialResults: Map<String, ChildResult> = ids.associateWith { id ->
        val row = rowsById[id]
        if (row == null) {
            ChildResult(id, "unknown", 0, source = "db_initial", settled = false)
        } else {
            val s = RuntimeState.fromWire(row.runtimeState) ?: RuntimeState.CLOSED
            val matched = predicate(s)
            ChildResult(
                childId = id,
                finalState = row.runtimeState,
                elapsedMs = 0,
                lastError = row.closingReason,
                source = "db_poll",
                settled = matched,
            )
        }
    }
    val alreadySettled = initialResults.filterValues { it.settled }.keys
    if (mode.isAll && alreadySettled.size == ids.size) return initialResults.values.toList()
    if (mode.isAny && alreadySettled.isNotEmpty()) {
        // At least one was already settled on first read; do
        // one more poll to catch siblings that may have
        // settled at the same time, then return.
        delay(pollMs)
        return refresh(agentDbClient, ids, predicate, initialResults, start)
    }
    val deadline = if (timeoutMs == null) Long.MAX_VALUE else start + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        delay(pollMs)
        val refreshed = refresh(agentDbClient, ids, predicate, initialResults, start)
        val settledNow = refreshed.count { it.settled }
        if (mode.isAll && settledNow == ids.size) return refreshed
        if (mode.isAny && settledNow > 0) return refreshed
        if (System.currentTimeMillis() >= deadline) return refreshed
    }
    // Timed out — return whatever state we have.
    return refresh(agentDbClient, ids, predicate, initialResults, start)
}

private suspend fun refresh(
    agentDbClient: AgentDatabaseClient,
    ids: List<String>,
    predicate: (RuntimeState) -> Boolean,
    previous: Map<String, ChildResult>,
    start: Long,
): List<ChildResult> {
    val now = System.currentTimeMillis()
    return ids.map { id ->
        val row: AgentDatabaseClient.RuntimeStateInfo? = try {
            agentDbClient.getRuntimeState(id)
        } catch (_: Exception) { null }
        if (row == null) {
            previous[id] ?: ChildResult(id, "unknown", 0, source = "db_poll", settled = false)
        } else {
            val s = RuntimeState.fromWire(row.runtimeState) ?: RuntimeState.CLOSED
            val matched = predicate(s)
            ChildResult(
                childId = id,
                finalState = row.runtimeState,
                elapsedMs = now - start,
                lastError = row.closingReason,
                source = "db_poll",
                settled = matched,
            )
        }
    }
}

/**
 * Parse the `childIds` argument — accepts either a JSON
 * array string or a comma-separated list.  Trimmed, de-blanked.
 * Public so [AwaitAgentTool] can reuse the same normaliser if
 * we ever extend single-child mode to inherit the same parser.
 */
internal fun parseChildIds(raw: Any?): List<String> {
    val s = when (raw) {
        is String -> raw
        is List<*> -> raw.joinToString(",")
        else -> throw IllegalArgumentException(
            "childIds must be a string (JSON array or comma list), got ${raw?.javaClass?.simpleName}"
        )
    }
    // First try as JSON array
    val trimmed = s.trim()
    if (trimmed.startsWith("[")) {
        val mapper = jacksonObjectMapper()
        val arr = try { mapper.readValue(trimmed, Array<String>::class.java) } catch (_: Exception) {
            null
        }
        if (arr != null) return arr.map { it.trim() }.filter { it.isNotEmpty() }
    }
    // Fall back to comma-separated
    return trimmed.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
