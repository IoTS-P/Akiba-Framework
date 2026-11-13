package org.iotsplab.akiba.plan

/**
 * What a plan script talks to.
 *
 * Verbs run immediately — the script is the program, not a description of one — and each
 * one returns an [Outcome], so a single failure does not end the run. Every call is
 * appended to the journal as it happens; that log replaces the old preview.
 *
 * The verbs live one file per domain ([ProjectVerbs], [ProgramVerbs], [RunVerbs]) and are
 * delegated here, so this type stays a table of contents: a script still sees a flat
 * receiver, while each domain grows on its own.
 */
class PlanScope private constructor(
    runtime: PlanRuntime,
    support: ScopeSupport,
) : ProjectVerbs by ProjectOps(runtime, support),
    ProgramVerbs by ProgramOps(runtime, support),
    RunVerbs by RunOps(runtime, support) {

    constructor(runtime: PlanRuntime, journal: PlanJournal = PlanJournal.disabled())
        : this(runtime, ScopeSupport(journal))
}
