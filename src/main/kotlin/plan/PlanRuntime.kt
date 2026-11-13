package org.iotsplab.akiba.plan

/**
 * The work a plan can ask for.
 *
 * Kept behind an interface so the scope can be tested without Ghidra, a database or a
 * real project on disk: the production implementation maps these onto
 * `ProjectPrograms` and the module runner.
 */
interface PlanRuntime {
    fun projectExists(name: String): Boolean
    fun createProject(name: String, overwrite: Boolean): ProjectRef
    fun openProject(name: String, createIfMissing: Boolean): ProjectRef
    fun importPrograms(programIds: List<String>, target: ImportTarget): Int
    fun runModule(spec: ModuleSpec, programIds: List<String>): Int
}
