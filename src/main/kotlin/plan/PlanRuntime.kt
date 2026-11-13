package org.iotsplab.akiba.plan

/**
 * The work a plan can ask for, split by what it touches.
 *
 * The scope is tested without Ghidra, a database or a real project on disk, and a test
 * that needs one domain should not have to stub the other two — so the contract is three
 * interfaces and [PlanRuntime] is only their union.
 */
interface PlanRuntime : ProjectRuntime, ProgramRuntime, ModuleRuntime

/** What a plan can do to a project. */
interface ProjectRuntime {
    fun projectExists(name: String): Boolean
    fun createProject(name: String, overwrite: Boolean): ProjectRef
    fun openProject(name: String, createIfMissing: Boolean): ProjectRef
}

/** What a plan can do to the programs on disk. */
interface ProgramRuntime {

    /** Copies each program out of the project it lives in and into [target]. */
    fun copyPrograms(programs: List<ProgramHandle>, target: ImportTarget): Int

    /**
     * Programs matching [filter], a bare SQL condition over the program index.
     *
     * Exposed as SQL on purpose: the index is a table the operator already knows, and a
     * typed query language would have to grow a parser before it grew a single use.
     */
    fun findPrograms(filter: String): List<ProgramHandle>
}

/** What a plan can run. */
interface ModuleRuntime {
    fun runModule(spec: ModuleSpec, programIds: List<String>): Int
}
