package org.iotsplab.akiba.llm.agent

// ============================================================
//  AgentConstants — well-known cross-cutting constants
// ============================================================
//
//  Single source of truth for values that are:
//   - referenced from multiple files / packages, OR
//   - conceptually part of the agent subsystem's public contract
//     (wire formats, protocol markers, policy thresholds).
//
//  Purely internal constants (used only within a single class /
//  file) stay where they are — moving everything here would make
//  this file unreadable without improving discoverability.
//
//  ## Organisation
//
//  1. Session identity
//  2. Standby-resume wire protocol
//  3. LLM retry policy
//  4. Tool-call batching
//  5. Mailbox backpressure & priority
//  6. Context compaction
//  7. Tool result truncation

// ----------------------------------------------------------
// 1. Session identity
// ----------------------------------------------------------

/**
 * Well-known UUID of the synthetic "system" session.
 *
 * The `akiba_db_daemon` lazily creates this row in `agent_sessions`
 * (`session_name='system'`) so that wake-notification mailbox messages
 * satisfy the `sender_session_id` FK constraint.  It is never a real
 * agent — it has no transcript and no messages of its own.
 *
 * Used to:
 *  - filter the system session out of session-list API responses;
 *  - register conversation participants for system-sent wake messages;
 *  - reject mail addressed *to* the system session.
 *
 * The daemon (`MailboxOps.kt`) keeps its own `private const` copy of this
 * value because it is an independently deployed module that cannot import
 * framework code.  Both copies must stay in sync.
 */
const val SYSTEM_SESSION_UUID = "00000000-0000-0000-0000-000000000000"

// ----------------------------------------------------------
// 2. Standby-resume wire protocol
// ----------------------------------------------------------

/**
 * Prefix of the synthetic user-message marker injected by
 * [AgentRuntime.resumeStandby] to tell the agent it is being
 * resumed from a STANDBY park (rather than receiving a genuine
 * user message).
 *
 * The full marker has the format:
 * `[[AKIBA_INTERNAL:STANDBY_RESUME:<uuid>]]`
 *
 * [AkibaAgent] detects this prefix in the last user message to
 * decide whether to strip the marker from memory before the LLM
 * sees it.  The protocol is shared between [AgentRuntime]
 * (producer) and [AkibaAgent] (consumer), so it lives here.
 */
const val STANDBY_RESUME_PROMPT_PREFIX: String = "[[AKIBA_INTERNAL:STANDBY_RESUME:"

/** Suffix of the synthetic standby-resume marker (see [STANDBY_RESUME_PROMPT_PREFIX]). */
const val STANDBY_RESUME_PROMPT_SUFFIX: String = "]]"

// ----------------------------------------------------------
// 3. LLM retry policy
// ----------------------------------------------------------

/**
 * Backoff schedule (in milliseconds) for LLM call retries.
 *
 * Both timeouts and other errors trigger retries with this schedule.
 * The gap is a flat 2 minutes for the first 5 retries, then 3 minutes
 * for retries 6-10. From retry 11 onward the interval grows linearly:
 * 5min, 7min, 9min, 11min, … (each subsequent retry adds 2 minutes).
 *
 * After the last entry, the pattern continues to grow by 2 minutes
 * per retry (computed dynamically — see `AgentStrategy.callLLM`).
 *
 * Referenced by [StrategyContext.callLLM] in `AgentStrategy.kt`.
 */
val LLM_RETRY_BACKOFF_MS: List<Long> = listOf(
    120_000L,    // retry 1  — 2 minutes
    120_000L,    // retry 2  — 2 minutes
    120_000L,    // retry 3  — 2 minutes
    120_000L,    // retry 4  — 2 minutes
    120_000L,    // retry 5  — 2 minutes
    180_000L,    // retry 6  — 3 minutes
    180_000L,    // retry 7  — 3 minutes
    180_000L,    // retry 8  — 3 minutes
    180_000L,    // retry 9  — 3 minutes
    180_000L,    // retry 10 — 3 minutes
    300_000L,    // retry 11 — 5 minutes (start of linear growth: +2min each)
    420_000L,    // retry 12 — 7 minutes
    540_000L,    // retry 13 — 9 minutes
    660_000L,    // retry 14 — 11 minutes
    780_000L,    // retry 15 — 13 minutes
    900_000L,    // retry 16 — 15 minutes
    1_020_000L,  // retry 17 — 17 minutes
    1_140_000L,  // retry 18 — 19 minutes
    1_260_000L,  // retry 19 — 21 minutes
    1_380_000L,  // retry 20 — 23 minutes (beyond this: continue +2min/retry)
)

/**
 * Prefix for LLM retry-status messages written to the agent_messages
 * table during the unlimited retry loop in `StrategyContext.callLLM`.
 *
 * These messages use `role="system"` so they are visible to the
 * frontend (via message polling) but are **filtered out** by
 * [PersistentChatMemory] before entering the LLM context buffer.
 *
 * Format: `"$LLM_RETRY_STATUS_PREFIX retry=2 backoffMs=60000 nextRetryAt=..."`
 */
const val LLM_RETRY_STATUS_PREFIX = "__llm_retry_status__"

/**
 * Prefix for LLM "still working" progress messages written to the
 * agent_messages table while a single LLM call is in-flight.
 *
 * Motivation: a long-form LLM response (e.g. a detailed analysis)
 * can take 60+ seconds to generate.  During that window the
 * frontend has no signal that the agent is alive — only the
 * eventual assistant message.  The retry-status path covers the
 * "previous call failed" case but says nothing while the
 * current call is still running.  These progress heartbeats
 * close that gap: a small system message is appended every
 * [LLM_PROGRESS_HEARTBEAT_MS] while the call is in-flight so
 * the frontend can render "LLM still working (Ns elapsed)".
 *
 * Like [LLM_RETRY_STATUS_PREFIX], these use `role="system"` and
 * are filtered out of the LLM context by [PersistentChatMemory].
 *
 * Format: `"$LLM_PROGRESS_PREFIX elapsedMs=12345 status=in_flight"`
 * and a final `"$LLM_PROGRESS_PREFIX elapsedMs=N status=done"` when
 * the call returns (success or failure).
 */
const val LLM_PROGRESS_PREFIX = "__llm_progress__"

/**
 * How often the in-flight heartbeat is written while an LLM
 * call is running.  15 seconds is a sweet spot:
 *  - fast enough that the UI feels alive (user sees updates)
 *  - slow enough that we don't spam the agent_messages table
 *    with hundreds of rows for a single LLM call
 */
const val LLM_PROGRESS_HEARTBEAT_MS: Long = 15_000L

/**
 * Prefix marking an assistant message that contains the PARTIAL
 * output of an LLM streaming call that was interrupted mid-stream
 * (provider stall / connection drop).
 *
 * Written by `AgentStrategy.invokeChatStreaming`'s catch block into
 * chat memory (and therefore the DB for persistent memories) right
 * before the exception propagates to the retry loop.  Two consumers:
 *  - The LLM itself: the next retry rebuilds its message list from
 *    the same memory, so the model sees its own interrupted partial
 *    answer in context and can build on it instead of the work
 *    vanishing.
 *  - The frontend: renders the row with a dimmed background and an
 *    "interrupted partial" badge instead of a normal assistant
 *    bubble.
 *
 * Unlike the retry-status / progress markers, this row MUST stay
 * in the LLM context (it is real model output), so it uses
 * `role="assistant"` and is NOT filtered by [PersistentChatMemory].
 */
const val INTERRUPTED_PARTIAL_PREFIX = "__interrupted_partial__"

/**
 * Prefix of the continuation-instruction message written right
 * after an [INTERRUPTED_PARTIAL_PREFIX] row.
 *
 * Observed behaviour this prevents: when a retry follows an
 * interrupted stream, some models try to SEAMLESSLY continue the
 * partial output — if the interruption happened inside a tool_call
 * JSON block, the model then emits only the remaining tail, and
 * NEITHER half parses as valid JSON.  The instruction text is
 * chosen by where the stream was cut (see
 * `ToolCallParser.endsWithTruncatedToolCall`):
 *  - inside a tool_call JSON block → MUST re-emit the entire
 *    tool call as one complete block (continuing prose is fine);
 *  - anywhere else → continue seamlessly from where it stopped
 *    (saves tokens; tool calls must still be complete blocks).
 *
 * Written with `role="user"` (the strongest instruction channel
 * across providers) and MUST stay in the LLM context; the frontend
 * hides the row as an internal directive.
 */
const val RETRY_INSTRUCTION_PREFIX = "__retry_instruction__"

// ----------------------------------------------------------
// 4. Tool-call batching
// ----------------------------------------------------------

/**
 * Maximum number of tool calls the LLM is allowed to emit in a single
 * response.  Extras are silently dropped (the [batchToolCallHint] in
 * `AgentHarness.kt` informs the LLM that calls were capped).
 *
 * This value is also interpolated into the system prompt text in
 * `AgentPrompts.kt` so the LLM is told the limit up-front.
 *
 * Referenced by:
 *  - `AgentPrompts.kt` (prompt text + `batchTruncatedNote`)
 *  - `AgentStrategy.kt` (`ReActStrategy` batch capping)
 *  - `AgentHarness.kt` (`batchToolCallHint` maxBatch parameter)
 */
const val MAX_BATCH_TOOL_CALLS: Int = 5

// ----------------------------------------------------------
// 5. Mailbox backpressure & priority
// ----------------------------------------------------------

/**
 * When a session's pending (seen-but-unhandled) mailbox message count
 * reaches this threshold, new non-urgent messages are rejected with a
 * structured backpressure error.
 *
 * Referenced by:
 *  - `WakeCondition.kt` (`BackpressureTracker`)
 *  - `AgentMailboxService.kt` (wake-board panel rendering)
 */
const val BACKPRESSURE_THRESHOLD: Int = 20

/**
 * Messages with `priority >= this value` bypass the backpressure
 * check so emergencies can still get through even when the
 * recipient's backlog is full.
 *
 * Referenced by:
 *  - `WakeCondition.kt` (`BackpressureTracker`)
 *  - `AgentMailboxService.kt` (comments + backpressure bypass logic)
 */
const val URGENT_PRIORITY_THRESHOLD: Int = 8

/**
 * A conversation whose top unhandled message has `priority >= this
 * value` preempts the currently-active conversation in the
 * scratchpad scheduler.
 *
 * Referenced by `ConversationScratchpad.kt`.
 */
const val PREEMPTION_THRESHOLD: Int = 5

/**
 * Number of wake cycles a "seen" (drained but not handled) mailbox
 * message survives before being flagged as "escalated" in the
 * wake-board panel — the LLM is told it can no longer defer these.
 *
 * Referenced by `AgentMailboxService.kt` (`applyMailboxDrain`).
 */
const val ESCALATE_AFTER_WAKES: Int = 3

// ----------------------------------------------------------
// 6. Context compaction
// ----------------------------------------------------------

/**
 * When the duplicate-tool-call detector flags this many consecutive
 * WARNING-level duplicates, the ReAct strategy forces a context
 * compaction (up to [MAX_FORCED_COMPACTIONS] times) to break the loop.
 *
 * Referenced by `AgentStrategy.kt` (`ReActStrategy`).
 */
const val DUPLICATE_LOOP_FORCE_COMPACT_THRESHOLD: Int = 5

/**
 * Hard cap on forced compactions within a single agent run.
 * Prevents infinite compaction loops when the context is already
 * minimal but the LLM keeps repeating.
 *
 * Referenced by `AgentStrategy.kt` (`ReActStrategy`).
 */
const val MAX_FORCED_COMPACTIONS: Int = 2

/**
 * Consecutive provider context-length rejections after which the LLM
 * retry loop invokes `StrategyContext.onContextOverflowHook` to compact
 * the context. Two (not one) because the one-shot `onLLMErrorHook` may
 * already have compacted for the first; a second rejection proves the
 * context is still too large. Referenced by `AgentStrategy.kt`.
 */
const val CONTEXT_OVERFLOW_COMPACT_THRESHOLD: Int = 2

/**
 * Failed overflow-compaction attempts after which the retry loop aborts
 * instead of spinning forever on a request the provider will never
 * accept (escape hatch for the "context full → infinite retry" deadlock
 * with models unknown to `ModelContextLengthService`).
 * Referenced by `AgentStrategy.kt`.
 */
const val MAX_FAILED_OVERFLOW_COMPACTIONS: Int = 3

/**
 * Token budget of NEWEST tool outputs preserved during compaction
 * pruning (opencode-style): before the summary pass, tool outputs in
 * the rounds being summarised are replaced with a short placeholder,
 * walking from newest to oldest until this budget is exhausted. The
 * pruning shrinks the compression request so a SINGLE summary pass
 * fits the provider window — no multi-round compression needed.
 *
 * Referenced by `AkibaAgent.kt` (`pruneToolOutputs`).
 */
const val PRUNE_PROTECT_TOKENS: Int = 40_000

// ----------------------------------------------------------
// 7. Tool result truncation
// ----------------------------------------------------------

/**
 * Maximum length (in characters) of a tool result string stored in
 * chat memory before truncation.  Longer results are truncated to
 * this length with a suffix noting the original size.
 *
 * Referenced by `AkibaAgent.kt`.
 */
const val MAX_TOOL_RESULT_LENGTH: Int = 40000
