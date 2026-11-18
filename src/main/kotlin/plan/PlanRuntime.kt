package org.iotsplab.akiba.plan

import org.iotsplab.akiba.module.ModuleRun

/**
 * The work a plan can ask for, split by what it touches.
 *
 * The scope is tested without Ghidra, a database or a real project on disk, and a test
 * that needs one domain should not have to stub the other two — so the contract is three
 * interfaces and [PlanRuntime] is only their union.
 */
interface PlanRuntime : ProjectRuntime, ProgramRuntime, ModuleRuntime, BinaryRuntime

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

/** What a plan can do with the stored files themselves. */
interface BinaryRuntime {

    /** Binaries matching [filter], a bare SQL condition over the `binaries` table. */
    fun findBinaries(filter: String): List<BinaryHandle>

    /**
     * Registers [path] and returns its row.
     *
     * The file is copied under the binaries root and made read-only, which is the same
     * deal a configured import gets: one canonical copy, no module editing it in place.
     */
    fun importBinary(path: String): BinaryHandle
}

/** What a plan can run. */
interface ModuleRuntime {

    /**
     * Runs [spec] on each program, keyed by program id in the result.
     *
     * Programs a plan knows about have no binary row, so the modules run with db writes
     * off and hand their data back instead of storing it.
     */
    fun runModule(programs: List<ProgramHandle>, specFor: (ProgramHandle) -> ModuleSpec): Map<String, ModuleRun>

    /**
     * Writes one run's result into the module's own table, for the binary [binaryId].
     * Returns false when there is nothing to write or no row to write it against.
     */
    fun saveResult(spec: ModuleSpec, run: ModuleRun): Boolean
}
