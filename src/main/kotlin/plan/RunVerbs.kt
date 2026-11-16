package org.iotsplab.akiba.plan

import org.iotsplab.akiba.module.ModuleRun

/** Running analysis work over a set of programs, and deciding what happens to what it found. */
interface RunVerbs {

    /**
     * Runs [spec] over [programs] and returns what each one produced, keyed by program id.
     *
     * Nothing is written to a database: the script decides what is worth keeping.
     */
    fun runAll(programs: List<ProgramHandle>, spec: ModuleSpec): Outcome<Map<String, ModuleRun>> =
        runAll(programs) { spec }

    /**
     * Runs the module over [programs], asking [specFor] what each program needs.
     *
     * This is the overload to reach for when one setting cannot serve every file — an
     * output path, a size limit, a flag that depends on the architecture.
     */
    fun runAll(
        programs: List<ProgramHandle>,
        specFor: (ProgramHandle) -> ModuleSpec,
    ): Outcome<Map<String, ModuleRun>>

    /**
     * Stores one run's result in the module's table for [binaryId].
     *
     * Returns false when the module returned nothing, or when [binaryId] is not a real row
     * (a program that was never imported has none).
     */
    fun saveResult(spec: ModuleSpec, binaryId: Int, run: ModuleRun): Outcome<Boolean>
}

internal class RunOps(
    private val runtime: PlanRuntime,
    private val support: ScopeSupport,
) : RunVerbs {

    override fun runAll(
        programs: List<ProgramHandle>,
        specFor: (ProgramHandle) -> ModuleSpec,
    ): Outcome<Map<String, ModuleRun>> =
        support.step(
            "runModule",
            mapOf(
                "module" to (programs.firstOrNull()?.let { specFor(it).className } ?: "?"),
                "count" to programs.size,
            ),
        ) { runtime.runModule(programs, specFor) }

    override fun saveResult(spec: ModuleSpec, binaryId: Int, run: ModuleRun): Outcome<Boolean> =
        support.step(
            "saveResult",
            mapOf("module" to spec.className, "binaryId" to binaryId),
        ) { runtime.saveResult(spec, binaryId, run) }
}
