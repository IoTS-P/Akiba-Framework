package org.iotsplab.akiba.llm.agent.core

// ============================================================
//  AgentConstants — well-known cross-cutting constants
// ============================================================
//
//  Single source of truth for values referenced from multiple
//  files/packages or that are part of the subsystem's public
//  contract (wire formats, protocol markers, policy thresholds).
//  Purely internal constants stay in the file that uses them.

// ----------------------------------------------------------
// 1. Session identity
// ----------------------------------------------------------

/**
 * Well-known UUID of the synthetic "system" session.
 *
 * The `akiba_db_daemon` lazily creates this row in `agent_sessions` so that
 * wake-notification mailbox messages satisfy the `sender_session_id` FK
 * constraint; it is never a real agent and has no transcript of its own.
 *
 * `MailboxOps.kt` keeps a private copy because the daemon cannot import
 * framework code — both copies must stay in sync.
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
 * Backoff schedule (in milliseconds) for LLM call retries: the wait before
 * retry N is `LLM_RETRY_BACKOFF_MS[N-1]` (3 s, 5 s, 7 s), with at most
 * [org.iotsplab.akiba.llm.client.LLMConfig.maxRetries] retries per message and
 * [org.iotsplab.akiba.llm.client.LLM_ATTEMPT_TIMEOUT_MS] per attempt.
 *
 * A stuck provider must surface, not hang: once the budget is exhausted
 * `callLLM` returns null, the strategy ends the run with [StopReason.ERROR],
 * and the failure propagates up the agent tree to fail the module at the root.
 *
 * Referenced by [StrategyContext.callLLM] in `AgentStrategy.kt`.
 */
val LLM_RETRY_BACKOFF_MS: List<Long> = listOf(
    3_000L,    // before retry 1 — 3 s
    5_000L,    // before retry 2 — 5 s
    7_000L,    // before retry 3 — 7 s
)

/**
 * Prefix for LLM retry-status messages written to the agent_messages
 * table by `StrategyContext.callLLM` while it works through its retry
 * budget (`1 + LLMConfig.maxRetries` attempts).
 *
 * These messages use `role="system"` so they are visible to the
 * frontend (via message polling) but are **filtered out** by
 * [PersistentChatMemory] before entering the LLM context buffer.
 *
 * Format: `"$LLM_RETRY_STATUS_PREFIX retry=2 backoffMs=60000 nextRetryAt=..."`
 */
const val LLM_RETRY_STATUS_PREFIX = "__llm_retry_status__"

/**
 * Prefix for LLM "still working" progress messages written to
 * agent_messages every [LLM_PROGRESS_INTERVAL_MS] while an LLM call
 * is in-flight, so the frontend can render liveness during long
 * generations. Uses `role="system"`; filtered out of the LLM context
 * by [PersistentChatMemory].
 *
 * Format: `"$LLM_PROGRESS_PREFIX status=in_flight elapsedMs=12345
 * chunkCount=42 byteCount=1024 preview=..."`, followed by a final
 * `"... status=done|failed"` row when the attempt returns.
 */
const val LLM_PROGRESS_PREFIX = "__llm_progress__"

/**
 * Interval between in-flight progress rows while an LLM call is running.
 *
 * One writer (``LlmProgressReporter``) covers a whole attempt, replacing the
 * previous 15 s heartbeat + 3 s chunk-writer pair that described the same
 * generation twice.  3 s keeps the "still working" indicator responsive for
 * long reasoning models without flooding `agent_messages`.
 */
const val LLM_PROGRESS_INTERVAL_MS: Long = 3_000L

/**
 * Prefix marking an assistant message containing the PARTIAL output
 * of an interrupted streaming call. Written by
 * `AgentStrategy.invokeChatStreaming`'s catch block before the
 * exception propagates to the retry loop, so the model sees its own
 * partial answer in the retry's context (and the frontend can render
 * the row dimmed). Kept in the LLM context (`role="assistant"`).
 */
const val INTERRUPTED_PARTIAL_PREFIX = "__interrupted_partial__"

/**
 * Prefix of the continuation-instruction message written right after
 * an [INTERRUPTED_PARTIAL_PREFIX] row. The instruction depends on
 * where the stream was cut (see `ToolCallParser.endsWithTruncatedToolCall`):
 *  - inside a tool_call JSON block → re-emit the entire tool call as
 *    one complete block (seamless continuation would leave two
 *    unparseable halves);
 *  - anywhere else → continue seamlessly without repeating content.
 *
 * Written with `role="user"` and kept in the LLM context; the
 * frontend hides the row as an internal directive.
 */
const val RETRY_INSTRUCTION_PREFIX = "__retry_instruction__"

// ----------------------------------------------------------
// 4. Tool-call batching
// ----------------------------------------------------------

/**
 * Consecutive no-action iterations after which a **text-protocol** provider's
 * plain-prose answer is accepted as the final answer.  Native tool calling
 * needs no such fallback — an empty tool-call list plus a normal stop already
 * means "done" — whereas text-protocol models get one format reminder (they
 * can stop mid-tool-call) and then their prose is taken at face value instead
 * of looping until maxIterations.
 *
 * Referenced by `strategy/ReActStrategy.kt` (`plainTextAnswer`).
 */
const val MAX_NO_ACTION_TURNS: Int = 2

/**
 * Maximum number of tool calls the LLM is allowed to emit in a single
 * response; extras are silently dropped and the [batchToolCallHint] tells the
 * LLM that calls were capped.
 *
 * The value is also interpolated into the system prompt text in
 * `AgentPrompts.kt` so the LLM is told the limit up-front.
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
 * Referenced by `strategy/ReActStrategy.kt`.
 */
const val DUPLICATE_LOOP_FORCE_COMPACT_THRESHOLD: Int = 5

/**
 * Hard cap on forced compactions within a single agent run.
 * Prevents infinite compaction loops when the context is already
 * minimal but the LLM keeps repeating.
 *
 * Referenced by `strategy/ReActStrategy.kt`.
 */
const val MAX_FORCED_COMPACTIONS: Int = 2

/**
 * Consecutive provider context-length rejections after which the LLM
 * retry loop invokes `StrategyContext.onContextOverflowHook` to compact
 * the context. Two (not one) because the one-shot `onLLMErrorHook` may
 * already have compacted for the first; a second rejection proves the
 * context is still too large. Referenced by `strategy/ReActStrategy.kt`.
 */
const val CONTEXT_OVERFLOW_COMPACT_THRESHOLD: Int = 2

/**
 * Failed overflow-compaction attempts after which the current call aborts
 * early instead of spending the rest of its retry budget on a request the
 * provider will never accept — the escape hatch for a context that simply
 * cannot be shrunk enough.
 * Referenced by `strategy/ReActStrategy.kt`.
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

/**
 * Window space kept free before compaction fires, so the summarisation prompt
 * and its reply still fit inside the same window.
 *
 * Deliberately not configurable: it describes how compaction works, not how a
 * deployment happens to be tuned, and windows smaller than it keep the
 * percentage rule instead of collapsing the trigger to zero.
 */
const val COMPACT_HEADROOM_TOKENS: Int = 32_000

// ----------------------------------------------------------
// 7. Tool result truncation
// ----------------------------------------------------------
