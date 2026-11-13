package org.iotsplab.akiba.plan

/** A project a plan writes into. Cheap to build; nothing is created until a verb runs. */
data class ProjectRef(val name: String) {
    /** `batch / "raw"` — the folder part of an import target. */
    operator fun div(folder: String): ImportTarget = ImportTarget(this, folder)
}

/** Where imports land: a project and its folder. */
data class ImportTarget(val project: ProjectRef, val folder: String?)

/**
 * A program as the plan sees it: identity, where it lives, and the facts a script may
 * branch on. The project is part of it because copying a program needs a source.
 */
data class ProgramHandle(
    val id: String,
    val name: String,
    val arch: String? = null,
    val sizeBytes: Long? = null,
    val path: String? = null,
    val project: String? = null,
)

/** `module("…") { timeout = 600 }` */
data class ModuleSpec(val className: String, val timeoutSeconds: Int? = null) {
    fun withTimeout(seconds: Int) = copy(timeoutSeconds = seconds)
}
