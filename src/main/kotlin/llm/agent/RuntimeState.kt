package org.iotsplab.akiba.llm.agent

/**
 * Runtime state of a sub-agent dispatch. Orthogonal to [Lifecycle]:
 *
 *  - [Lifecycle] describes the *policy* (one-shot vs standby) — what
 *    should happen after the run() loop exits.
 *  - [RuntimeState] describes the *current* state in the async
 *    scheduler — what the agent is doing right now.
 *
 * Both columns are written to `agent_sessions` (`lifecycle` /
 * `runtime_state`) and exposed via the `set_runtime_state` /
 * `get_runtime_state` daemon routes.
 *
 * State graph:
 * ```
 *                        ┌──────────┐  lifecycle=standby
 *                        │          │ ────────────────┐
 *       spawn ──►   running  ◄──────┤                 ▼
 *                    │ ▲            │              standby
 *                    │ │            │                 │
 *                    │ │            │                 │ dispatcher picks up
 *                    │ │            │                 │ mailbox messages
 *                    ▼ │            │                 ▼
 *                  msghandle ◄──────┘              msghandle
 *                    │                               │
 *                    │ any non-terminal state       │
 *                    │ can be cancelled OR error    │
 *                    │ can also be paused (user)    │
 *                    ▼                               ▼
 *                cancelling ──────────────────────────┘
 *                    │                               │
 *                    ▼                               ▼
 *                  closed  (terminal)        error  (terminal)
 *
 *  PAUSED is a user-only state: the agent loop blocks after the
 *  current LLM response completes and waits for the user to resume.
 *  Other agents and the system can still send messages, but they
 *  accumulate in the mailbox until the user un-pauses.
 * ```
 *
 * `cancelling` and the terminal states (`closed` / `error`) are
 * reachable from every non-terminal state. `closed` and `error` are
 * terminal — `error` is used when the run threw (e.g. model service
 * unavailable) so the parent can decide whether to respawn or recover.
 */
enum class RuntimeState {
    /** The agent is in an active `run()` call. */
    RUNNING,

    /** The last `run()` returned cleanly with `lifecycle=standby` and
     *  no Job currently owns the session. Awaiting a mailbox dispatch. */
    STANDBY,

    /** The dispatcher started a Job to consume mailbox traffic for
     *  this session. Conceptually still inside `run()`. */
    MSGHANDLE,

    /**
     * User-requested pause: the agent loop blocks after the current
     * LLM response completes and waits for the user to resume.
     *
     * Only reachable via explicit user action (API call); the automated
     * flow never enters this state. Other agents and the system can
     * still send mailbox messages while a session is paused, but they
     * accumulate until the user un-pauses.
     */
    PAUSED,

    /** A parent / dispatcher is closing the session; in the grace
     *  period before hard cancel. */
    CANCELLING,

    /** Terminal: one-shot completed cleanly, or cancelled. */
    CLOSED,

    /** Terminal: the run threw (model service error, OOM, etc.) and
     *  the framework surfaced the failure. `closing_reason` carries
     *  the human-readable cause. */
    ERROR;

    /** Wire value used in the DB column. */
    fun wire(): String = name.lowercase()

    companion object {
        /** Parse a wire value; returns null when [raw] is unknown. */
        fun fromWire(raw: String?): RuntimeState? = when (raw?.lowercase()) {
            "running" -> RUNNING
            "standby" -> STANDBY
            "msghandle" -> MSGHANDLE
            "paused" -> PAUSED
            "cancelling" -> CANCELLING
            "closed" -> CLOSED
            "error" -> ERROR
            else -> null
        }

        /**
         * Returns true when [next] is a legal successor of [from].
         *
         *  - `RUNNING → STANDBY` (lifecycle=standby, clean exit)
         *  - `RUNNING → CLOSED` (lifecycle=one_shot, clean exit)
         *  - `RUNNING → MSGHANDLE` (dispatcher took over mid-run, reserved)
         *  - `STANDBY → MSGHANDLE` (dispatcher wakes the session)
         *  - `MSGHANDLE → STANDBY` (run() returned cleanly)
         *  - `MSGHANDLE → MSGHANDLE` (another mailbox batch on the same Job)
         *  - any non-terminal → `CANCELLING` (cascade / explicit cancel)
         *  - any non-terminal → `ERROR` (run-time failure)
         *  - `CANCELLING → CLOSED` (Job exited)
         *  - `* → CLOSED` / `* → ERROR` is also allowed as a shortcut for
         *    hard cancel / hard-fail paths that skip the grace period.
         *
         *  Self-transitions return false (caller should not be writing
         *  the same state twice in a row). Transitions out of a terminal
         *  state are rejected EXCEPT for `CLOSED → RUNNING` and
         *  `ERROR → RUNNING`, which are allowed for user-injection
         *  resume (the user sends a hint message to a finished/failed
         *  agent and the runtime restarts it in the same session).
         */
        fun canTransition(from: RuntimeState, next: RuntimeState): Boolean = when {
            from == next -> false
            from == CLOSED || from == ERROR -> next == RuntimeState.RUNNING
            next == CLOSED || next == ERROR -> true
            // Any non-terminal state can be paused by the user
            next == PAUSED -> from == RuntimeState.RUNNING ||
                from == RuntimeState.MSGHANDLE
            // Paused can resume back to running
            from == PAUSED -> next == RuntimeState.RUNNING ||
                next == RuntimeState.CANCELLING
            from == RuntimeState.RUNNING -> next == RuntimeState.STANDBY ||
                next == RuntimeState.MSGHANDLE ||
                next == RuntimeState.CANCELLING
            from == RuntimeState.STANDBY -> next == RuntimeState.MSGHANDLE ||
                next == RuntimeState.CANCELLING
            from == RuntimeState.MSGHANDLE -> next == RuntimeState.RUNNING ||
                next == RuntimeState.STANDBY ||
                next == RuntimeState.CANCELLING
            from == RuntimeState.CANCELLING -> next == RuntimeState.CLOSED
            else -> false
        }

        /**
         * Throw [IllegalStateException] when the transition is illegal.
         * Use at boundaries where a bug would silently corrupt the
         * state machine.
         */
        fun requireTransition(from: RuntimeState, next: RuntimeState) {
            require(canTransition(from, next)) {
                "Illegal RuntimeState transition: $from -> $next"
            }
        }
    }
}
