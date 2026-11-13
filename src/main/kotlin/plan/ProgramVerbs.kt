package org.iotsplab.akiba.plan

/** Moving programs between projects and folders. */
interface ProgramVerbs {

    /** Copies every program in [programs] into [target]; the sources are untouched. */
    fun importAll(programs: List<ProgramHandle>, target: ImportTarget): Outcome<Int>
}

internal class ProgramOps(
    private val runtime: PlanRuntime,
    private val support: ScopeSupport,
) : ProgramVerbs {

    override fun importAll(programs: List<ProgramHandle>, target: ImportTarget): Outcome<Int> =
        support.step(
            "importPrograms",
            mapOf(
                "project" to target.project.name,
                "folder" to (target.folder ?: ""),
                "count" to programs.size,
            ),
        ) {
            if (programs.isEmpty()) throw ProgramMissingException("<none matched>")
            runtime.copyPrograms(programs, target)
        }
}
