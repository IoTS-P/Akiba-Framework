package org.iotsplab.akiba.managers

/**
 * The one place that decides who is allowed to run an agent turn, and what that turn
 * should be told about the program's recent past.
 *
 * Two sessions may share a project — that is what [ProjectWorkerPool] is for — and they
 * may take turns on a program, which [ProgramScheduler] enforces. What this class adds is
 * the connection between the two: a turn borrows the project's worker, waits for the
 * program, and leaves with the note describing what other sessions did in between, so
 * the model is not reasoning about a program that changed under it.
 */
class ProjectTurnDispatcher(
    private val workers: ProjectWorkerPool,
    private val programs: ProgramScheduler,
) {

    /** What an agent needs to know before it starts working. */
    data class Turn(
        val project: String,
        val programId: String,
        val sessionId: String,
        /** Another session touched the program since this one last did; inject as a note. */
        val interleavingNote: String?,
        /** True when someone else's change means earlier observations may be stale. */
        val stateChanged: Boolean,
    )

    /**
     * Runs [block] for [sessionId] against [programId] in [project].
     *
     * The worker lease lasts the whole turn (so the project is not evicted mid-turn);
     * the program lock is held for the turn's tool work only — the caller should hand
     * control back between model calls, which is what keeps two sessions interleaving
     * rather than queueing.
     */
    fun <R> run(
        project: String,
        programId: String,
        sessionId: String,
        operation: String = "agent-turn",
        mutated: Boolean = true,
        timeoutMs: Long = 120_000L,
        block: (Turn) -> R,
    ): R = workers.acquire(project).use {
        programs.withProgram(
            programId = programId,
            sessionId = sessionId,
            operation = operation,
            mutated = mutated,
            timeoutMs = timeoutMs,
        ) { lease ->
            block(
                Turn(
                    project = project,
                    programId = programId,
                    sessionId = sessionId,
                    interleavingNote = lease.note(),
                    stateChanged = lease.stateChanged,
                ),
            )
        }
    }
}
