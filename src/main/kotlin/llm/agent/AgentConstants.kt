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
 * Exponential backoff schedule (in milliseconds) for LLM call retries.
 *
 * Both timeouts and other errors trigger retries with this schedule.
 * The gap roughly doubles each time, capping at 6 hours for prolonged
 * outages so an unattended long-running agent can survive a provider
 * downtime without manual intervention.
 *
 * After the last entry, all subsequent retries also wait 6 hours.
 *
 * Referenced by [StrategyContext.callLLM] in `AgentStrategy.kt`.
 */
val LLM_RETRY_BACKOFF_MS: List<Long> = listOf(
    30_000L,     // 30 seconds
    60_000L,     // 1 minute
    120_000L,    // 2 minutes
    300_000L,    // 5 minutes
    900_000L,    // 15 minutes
    1_800_000L,  // 30 minutes
    3_600_000L,  // 1 hour
    7_200_000L,  // 2 hours
    14_400_000L, // 4 hours
    21_600_000L, // 6 hours (cap)
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
const val MAX_TOOL_RESULT_LENGTH: Int = 8000
