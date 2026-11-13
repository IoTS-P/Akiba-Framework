package org.iotsplab.akiba.plan

/** Running analysis work over a set of programs. */
interface RunVerbs {

    /** Runs [spec] over [programs]; an empty list means "whatever the task opened". */
    fun runAll(programs: List<ProgramHandle>, spec: ModuleSpec): Outcome<Int>
}

internal class RunOps(
    private val runtime: PlanRuntime,
    private val support: ScopeSupport,
) : RunVerbs {

    override fun runAll(programs: List<ProgramHandle>, spec: ModuleSpec): Outcome<Int> =
        support.step(
            "runTask",
            mapOf(
                "module" to spec.className,
                "count" to programs.size,
                "timeout" to (spec.timeoutSeconds ?: 0),
            ),
        ) { runtime.runModule(spec, programs.map { it.id }) }
}
