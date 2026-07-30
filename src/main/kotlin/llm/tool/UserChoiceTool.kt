package org.iotsplab.akiba.llm.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.iotsplab.akiba.llm.agent.AgentModule
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

// ============================================================
//  UserChoiceManager — in-process "ask the user to pick one" gate
// ============================================================

/**
 * A pending multiple-choice question waiting for the user's answer.
 *
 * Stored in [UserChoiceManager] keyed by session ID; the frontend
 * discovers it via `GET /agent/pending-user-choices` (or the
 * `pendingUserChoice` field on `GET /agent/sessions/{id}`). The user
 * may pick an option, type a custom answer ([allowCustomInput]), or
 * ask follow-up ("BTW") questions first (see [BtwAssistant]).
 */
data class PendingUserChoice(
    /** Unique ID for this request. */
    val requestId: String,
    /** The session that owns this choice request. */
    val sessionId: String,
    /** The question the agent is asking the user. */
    val question: String,
    /** Candidate answers offered by the agent. */
    val options: List<String>,
    /** Whether the user may type a custom free-form answer. */
    val allowCustomInput: Boolean,
    /** Epoch millis when the request was created. */
    val createdAt: Long = System.currentTimeMillis()
)

/** One completed follow-up (BTW) question/answer pair. */
data class BtwQA(
    val question: String,
    val answer: String,
    val answeredAt: Long = System.currentTimeMillis()
)

/** A follow-up question submitted by the user, awaiting the side assistant's answer. */
class BtwExchange(
    val btwId: String,
    val question: String,
    val deferred: CompletableDeferred<String>
)

/**
 * Event returned by [UserChoiceManager.awaitEvent]: [Resolved] carries
 * the final answer (`null` = cancelled); [TimedOut] marks inactivity
 * timeout; [Btw] is a follow-up question the consumer must answer and
 * report back via [UserChoiceManager.completeBtw].
 */
sealed interface UserChoiceEvent {
    data class Resolved(val answer: String?) : UserChoiceEvent
    data object TimedOut : UserChoiceEvent
    data class Btw(val exchange: BtwExchange) : UserChoiceEvent
}

/** Internal state for one pending choice request. */
class PendingChoiceEntry(
    val choice: PendingUserChoice,
    /** Completed with the user's final answer, or `null` on cancel/close. */
    val answerDeferred: CompletableDeferred<String?> = CompletableDeferred(),
    /** Follow-up questions submitted by the user (unbounded, never suspends on send). */
    val btwChannel: Channel<BtwExchange> = Channel(Channel.UNLIMITED),
    /** Completed follow-up Q&A pairs, in order (exposed to the frontend). */
    val btwHistory: CopyOnWriteArrayList<BtwQA> = CopyOnWriteArrayList(),
    /** BTW exchanges handed out via [UserChoiceEvent.Btw] but not yet completed. */
    val pendingBtw: ConcurrentHashMap<String, BtwExchange> = ConcurrentHashMap()
) {
    /**
     * Last user-activity timestamp. Submitting a BTW question refreshes
     * it, so an actively-engaged user never hits the overall timeout
     * while reading follow-up answers.
     */
    @Volatile
    var lastActivityAt: Long = choice.createdAt

    /** Complete every unconsumed BTW exchange with a fallback message. */
    fun failPendingBtw(message: String) {
        pendingBtw.values.forEach { it.deferred.complete(message) }
        pendingBtw.clear()
    }
}

/** Read-only snapshot of a pending choice for DTO mapping. */
data class PendingChoiceSnapshot(
    val choice: PendingUserChoice,
    val btwHistory: List<BtwQA>,
    val lastActivityAt: Long
)

/**
 * Process-level singleton managing pending user-choice requests.
 *
 * Like [ConfirmationManager] but the response is a string, and the wait
 * is an event loop: while blocked, the user may submit follow-up (BTW)
 * questions answered via [BtwAssistant]. Worker processes reach it via
 * `POST /agent/internal/user-choice/request`, re-entering [awaitEvent]
 * after each BTW round-trip.
 */
object UserChoiceManager {

    /** Default inactivity timeout before the request is abandoned (10 minutes). */
    const val DEFAULT_CHOICE_TIMEOUT_MS: Long = 600_000L

    /** How long the BTW endpoint waits for the side assistant's answer (4 minutes). */
    const val BTW_AWAIT_TIMEOUT_MS: Long = 240_000L

    private val pending = ConcurrentHashMap<String, PendingChoiceEntry>()

    /**
     * Register a pending choice for [sessionId] (idempotent — the
     * worker's long-poll re-registers after every BTW round-trip).
     *
     * @return the entry, or `null` when [sessionId] is blank.
     */
    fun register(
        sessionId: String,
        question: String,
        options: List<String>,
        allowCustomInput: Boolean
    ): PendingChoiceEntry? {
        if (sessionId.isBlank()) return null
        return pending.computeIfAbsent(sessionId) {
            PendingChoiceEntry(
                PendingUserChoice(
                    requestId = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    question = question,
                    options = options,
                    allowCustomInput = allowCustomInput
                )
            )
        }
    }

    /**
     * Wait for the next event on [entry]. On inactivity timeout the
     * entry is removed so the frontend's poll sees it disappear.
     */
    suspend fun awaitEvent(
        entry: PendingChoiceEntry,
        timeoutMs: Long = DEFAULT_CHOICE_TIMEOUT_MS
    ): UserChoiceEvent {
        val remaining = timeoutMs - (System.currentTimeMillis() - entry.lastActivityAt)
        if (remaining <= 0) {
            removeEntry(entry, "Choice request timed out.")
            return UserChoiceEvent.TimedOut
        }
        return try {
            withTimeout(remaining) {
                select<UserChoiceEvent> {
                    entry.answerDeferred.onAwait { UserChoiceEvent.Resolved(it) }
                    entry.btwChannel.onReceive { exchange ->
                        entry.pendingBtw[exchange.btwId] = exchange
                        UserChoiceEvent.Btw(exchange)
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            removeEntry(entry, "Choice request timed out.")
            UserChoiceEvent.TimedOut
        } catch (_: Exception) {
            removeEntry(entry, "Choice request closed.")
            UserChoiceEvent.Resolved(null)
        }
    }

    /**
     * Full choice wait loop for the in-process path: registers the
     * request and loops until resolved, answering BTW questions via
     * [btwHandler]. Returns the final answer, or `null` on cancel/timeout.
     */
    suspend fun requestChoice(
        sessionId: String,
        question: String,
        options: List<String>,
        allowCustomInput: Boolean,
        timeoutMs: Long = DEFAULT_CHOICE_TIMEOUT_MS,
        btwHandler: (suspend (String) -> String)? = null
    ): String? {
        val entry = register(sessionId, question, options, allowCustomInput) ?: return null
        while (true) {
            when (val ev = awaitEvent(entry, timeoutMs)) {
                is UserChoiceEvent.Resolved -> return ev.answer
                UserChoiceEvent.TimedOut -> return null
                is UserChoiceEvent.Btw -> {
                    val answer = if (btwHandler != null) {
                        runCatching { btwHandler(ev.exchange.question) }
                            .getOrElse { "Error while answering the follow-up: ${it.message}" }
                    } else {
                        "(follow-up questions are not supported in this context)"
                    }
                    completeBtw(sessionId, ev.exchange.btwId, answer)
                }
            }
        }
    }

    /** Blocking variant of [requestChoice] for synchronous [Tool.execute] lambdas. */
    fun requestChoiceBlocking(
        sessionId: String,
        question: String,
        options: List<String>,
        allowCustomInput: Boolean,
        timeoutMs: Long = DEFAULT_CHOICE_TIMEOUT_MS,
        btwHandler: ((String) -> String)? = null
    ): String? {
        if (sessionId.isBlank()) return null
        return runBlocking {
            requestChoice(
                sessionId, question, options, allowCustomInput, timeoutMs,
                btwHandler = btwHandler?.let { h ->
                    { q: String -> withContext(Dispatchers.IO) { h(q) } }
                }
            )
        }
    }

    /**
     * Submit a follow-up (BTW) question and suspend until answered (or
     * [awaitTimeoutMs] expires). Refreshes the entry's activity
     * timestamp, extending the overall choice timeout for engaged users.
     *
     * @return the assistant's answer, or `null` when nothing is pending.
     */
    suspend fun submitBtw(
        sessionId: String,
        question: String,
        awaitTimeoutMs: Long = BTW_AWAIT_TIMEOUT_MS
    ): String? {
        val entry = pending[sessionId] ?: return null
        entry.lastActivityAt = System.currentTimeMillis()
        val exchange = BtwExchange(UUID.randomUUID().toString(), question, CompletableDeferred())
        entry.btwChannel.send(exchange)
        return try {
            withTimeout(awaitTimeoutMs) { exchange.deferred.await() }
        } catch (_: Exception) {
            "(the assistant did not answer in time — you may still pick an option)"
        }
    }

    /**
     * Complete a previously-dispatched BTW exchange and record the Q&A
     * pair in the entry's history. Returns false when no matching
     * unconsumed exchange existed.
     */
    fun completeBtw(sessionId: String, btwId: String, answer: String): Boolean {
        val entry = pending[sessionId] ?: return false
        val exchange = entry.pendingBtw.remove(btwId) ?: return false
        entry.btwHistory.add(BtwQA(exchange.question, answer))
        return exchange.deferred.complete(answer)
    }

    /**
     * Deliver the user's final answer (`null` = cancelled). Returns
     * false when no pending request existed or it was already completed.
     */
    fun respond(sessionId: String, answer: String?): Boolean {
        val entry = pending[sessionId] ?: return false
        return entry.answerDeferred.complete(answer)
    }

    /** Get a snapshot of the current pending choice for a session, if any. */
    fun getPending(sessionId: String): PendingChoiceSnapshot? =
        pending[sessionId]?.let {
            PendingChoiceSnapshot(it.choice, it.btwHistory.toList(), it.lastActivityAt)
        }

    /** Snapshot of every session that currently has a pending choice. */
    fun getAllPending(): Map<String, PendingChoiceSnapshot> =
        pending.entries.associate { (sid, entry) ->
            sid to PendingChoiceSnapshot(entry.choice, entry.btwHistory.toList(), entry.lastActivityAt)
        }

    /** Cancel and clear any pending choice, releasing the blocked tool thread with `null`. */
    fun clear(sessionId: String) {
        val entry = pending.remove(sessionId) ?: return
        entry.answerDeferred.complete(null)
        entry.failPendingBtw("(the choice request was cancelled)")
    }

    private fun removeEntry(entry: PendingChoiceEntry, btwFallback: String) {
        if (pending.remove(entry.choice.sessionId, entry)) {
            entry.failPendingBtw(btwFallback)
        }
    }
}

// ============================================================
//  Cross-process helper (worker → server HTTP long-poll)
// ============================================================

/** Wire response from the internal user-choice endpoint. */
private data class InternalChoiceResponse(
    val status: String,
    val choice: String?,
    val btwId: String?,
    val btwQuestion: String?
)

/**
 * Request a user choice via HTTP callback to the AkibaServer (worker
 * process path). The worker long-polls `POST /agent/internal/user-choice/request`;
 * when the server responds `status="btw"`, the worker answers locally
 * via [btwHandler] and re-POSTs with the answer attached.
 *
 * @param btwHistoryOut Accumulates locally-answered BTW pairs for the tool result.
 * @return the user's final answer, or `null` when cancelled/timed out/failed.
 */
fun requestChoiceViaHttp(
    serverPort: Int,
    sessionId: String,
    question: String,
    options: List<String>,
    allowCustomInput: Boolean,
    btwHandler: ((String) -> String)? = null,
    btwHistoryOut: MutableList<BtwQA>? = null
): String? {
    val mapper = jacksonObjectMapper()
    var pendingBtwId: String? = null
    var pendingBtwAnswer: String? = null

    while (true) {
        val bodyMap = mutableMapOf<String, Any>(
            "sessionId" to sessionId,
            "question" to question,
            "options" to options,
            "allowCustomInput" to allowCustomInput
        )
        if (pendingBtwId != null && pendingBtwAnswer != null) {
            bodyMap["btwId"] = pendingBtwId!!
            bodyMap["btwAnswer"] = pendingBtwAnswer!!
        }
        pendingBtwId = null
        pendingBtwAnswer = null

        val response = try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:$serverPort/api/agent/internal/user-choice/request"))
                .timeout(Duration.ofMinutes(11))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(bodyMap)))
                .build()
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString())
        } catch (_: Exception) {
            return null
        }
        if (response.statusCode() !in 200..299) return null

        val parsed = try {
            @Suppress("UNCHECKED_CAST")
            val raw = mapper.readValue(response.body(), Map::class.java) as Map<String, Any?>
            InternalChoiceResponse(
                status = raw["status"] as? String ?: "cancelled",
                choice = raw["choice"] as? String,
                btwId = raw["btwId"] as? String,
                btwQuestion = raw["btwQuestion"] as? String
            )
        } catch (_: Exception) {
            return null
        }

        when (parsed.status) {
            "answered" -> return parsed.choice
            "btw" -> {
                val btwQuestion = parsed.btwQuestion
                val btwId = parsed.btwId
                if (btwQuestion.isNullOrBlank() || btwId.isNullOrBlank()) return null
                val answer = if (btwHandler != null) {
                    runCatching { btwHandler(btwQuestion) }
                        .getOrElse { "Error while answering the follow-up: ${it.message}" }
                } else {
                    "(follow-up questions are not supported in this context)"
                }
                btwHistoryOut?.add(BtwQA(btwQuestion, answer))
                pendingBtwId = btwId
                pendingBtwAnswer = answer
            }
            else -> return null  // "cancelled" / "timeout" / unknown
        }
    }
}

// ============================================================
//  AskUserChoiceTool — the LLM-facing tool
// ============================================================

/**
 * Create a tool that lets the agent ask the user to pick one value out
 * of several candidates (e.g. "which of these 3 functions is the entry
 * point?").
 *
 * The frontend modal shows the options plus an optional "Custom answer…"
 * field. Before deciding, the user may ask follow-up ("BTW") questions,
 * answered by [BtwAssistant]; the transcript is embedded in the result.
 *
 * Blocks until the user answers, cancels, or the request times out
 * (10 minutes of inactivity; BTW activity resets the clock). Backends:
 * worker HTTP long-poll / in-process [UserChoiceManager] / stdin
 * fallback, mirroring [RunShellTool]'s confirmation gate.
 *
 * Result JSON: `{"selected", "custom", "btw": [...]}` on success,
 * `{"cancelled": true, "message"}` on dismiss/timeout.
 */
fun AskUserChoiceTool(
    parent: AgentModule
): Tool = Tool(
    name = "ask_user_choice",
    description = buildString {
        appendLine("Ask the USER to pick ONE value out of several candidates and wait for the answer.")
        appendLine()
        appendLine("Use this when you reach a decision point that genuinely requires human input, e.g.:")
        appendLine("  - several plausible targets/functions and the analysis goal depends on which one to focus on")
        appendLine("  - multiple output formats / next steps and the user's preference matters")
        appendLine()
        appendLine("Do NOT use it for questions you can answer yourself — every call blocks the")
        appendLine("agent until the user responds (10-minute inactivity timeout).")
        appendLine()
        appendLine("The user may ask follow-up questions before deciding (a side assistant")
        appendLine("answers them; the transcript is returned in 'btw'), and may type a custom")
        appendLine("answer instead of the offered options — always treat 'selected' as free text.")
    },
    parameters = listOf(
        ToolParameter(
            "question", "string",
            "The question to show the user, e.g. 'Which function should I analyse first?'.",
            required = true
        ),
        ToolParameter(
            "options", "string",
            "JSON array of 2-10 candidate answers, e.g. `[\"FUN_00102340\",\"main\",\"entry0\"]`.",
            required = true
        ),
        ToolParameter(
            "allowCustomInput", "boolean",
            "Whether the user may type a custom answer instead of picking an option. Default true.",
            required = false
        )
    )
) { args ->
    val mapper = jacksonObjectMapper()
    val question = (args["question"] as? String)?.trim()
        ?: return@Tool "Error: 'question' parameter is required"
    if (question.isEmpty()) {
        return@Tool "Error: question is empty"
    }

    // Parse options — accept a real list or a JSON-array string.
    val rawOptions = args["options"]
    val options: List<String> = when (rawOptions) {
        is List<*> -> rawOptions.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
        is String -> {
            val trimmed = rawOptions.trim()
            if (trimmed.startsWith("[")) {
                try {
                    mapper.readValue(trimmed, Array<String>::class.java)
                        .map { it.trim() }.filter { it.isNotEmpty() }
                } catch (_: Exception) {
                    return@Tool "Error: 'options' is not a valid JSON array of strings"
                }
            } else {
                // Tolerate newline/comma separated lists
                trimmed.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
            }
        }
        else -> return@Tool "Error: 'options' parameter is required (JSON array of strings)"
    }
    if (options.size < 2) {
        return@Tool "Error: provide at least 2 options"
    }
    if (options.size > 10) {
        return@Tool "Error: provide at most 10 options (got ${options.size})"
    }
    // De-duplicate while preserving order.
    val distinctOptions = options.distinct()

    val allowCustomInput = when (val v = args["allowCustomInput"]) {
        is Boolean -> v
        is String -> v.equals("true", ignoreCase = true)
        else -> true
    }

    val sessionId = parent.agentSessionId
    val serverPort = detectWorkerServerPort()

    // Side assistant for follow-up (BTW) questions. Created lazily; if
    // the LLM config cannot be resolved, BTW questions get a graceful
    // "not supported" answer instead of failing the whole choice.
    val btwAssistant = runCatching {
        BtwAssistant(parent, question, distinctOptions)
    }.getOrNull()
    val btwPairs = mutableListOf<BtwQA>()
    val btwHandler: (String) -> String = handler@{ q ->
        val assistant = btwAssistant
            ?: return@handler "(follow-up questions are unavailable: assistant not initialised)"
        assistant.answer(q).also { ans ->
            btwPairs.add(BtwQA(q, ans))
        }
    }

    val answer: String? = when {
        // Cross-process: worker → HTTP long-poll → server → UserChoiceManager
        !sessionId.isNullOrBlank() && serverPort != null -> {
            requestChoiceViaHttp(
                serverPort = serverPort,
                sessionId = sessionId,
                question = question,
                options = distinctOptions,
                allowCustomInput = allowCustomInput,
                btwHandler = btwHandler,
                btwHistoryOut = null  // btwPairs already tracks locally
            )
        }
        // In-process: tool and HTTP server share the same JVM
        !sessionId.isNullOrBlank() -> {
            UserChoiceManager.requestChoiceBlocking(
                sessionId = sessionId,
                question = question,
                options = distinctOptions,
                allowCustomInput = allowCustomInput,
                btwHandler = btwHandler
            )
        }
        // Stdin fallback for CLI / dev mode (no BTW support)
        else -> {
            System.err.println()
            System.err.println("┌─────────────────────────────────────────────────────────")
            System.err.println("│ [ask_user_choice] Agent asks:")
            System.err.println("│   $question")
            distinctOptions.forEachIndexed { i, opt ->
                System.err.println("│   ${i + 1}. $opt")
            }
            System.err.println("├─────────────────────────────────────────────────────────")
            System.err.print("│ Pick a number (or type a custom answer, empty = cancel): ")
            System.err.flush()
            val response = try {
                System.`in`.bufferedReader().readLine()?.trim()
            } catch (_: Exception) {
                null
            }
            when {
                response.isNullOrEmpty() -> null
                response.toIntOrNull() in 1..distinctOptions.size ->
                    distinctOptions[response.toInt() - 1]
                else -> response
            }
        }
    }

    if (answer == null) {
        return@Tool mapper.writeValueAsString(mapOf(
            "cancelled" to true,
            "message" to "The user did not pick an option (cancelled or timed out). " +
                "Decide on your own instead of asking again immediately."
        ))
    }

    val result = mutableMapOf<String, Any?>(
        "selected" to answer,
        "custom" to (answer !in distinctOptions)
    )
    if (btwPairs.isNotEmpty()) {
        // Surface the follow-up transcript so the main agent can factor
        // it into its decision.
        result["btw"] = btwPairs.map { mapOf("question" to it.question, "answer" to it.answer) }
    }
    mapper.writeValueAsString(result)
}
