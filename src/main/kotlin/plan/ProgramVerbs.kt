package org.iotsplab.akiba.plan

/** Moving programs between projects and folders. */
interface ProgramVerbs {

    /** Imports every program in [programs] into [target]. */
    fun importAll(programs: List<ProgramHandle>, target: ImportTarget): Outcome<Int>
}

internal class ProgramOps(
    private val runtime: PlanRuntime,
    private val support: ScopeSupport,
) : ProgramVerbs {

    override fun importAll(programs: List<ProgramHandle>, target: ImportTarget): Outcome<Int> =
        support.step(
            "import",
            mapOf(
                "project" to target.project.name,
                "folder" to (target.folder ?: ""),
                "count" to programs.size,
            ),
        ) {
            if (programs.isEmpty()) throw ProgramMissingException("<none matched>")
            runtime.importPrograms(programs.map { it.id }, target)
        }
}
