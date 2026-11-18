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
    /** The binaries row this program was imported from; null when it never was. */
    val binaryId: Long? = null,
)

/**
 * A file in the database: what `importBinary` just registered, or what `binaries()` picked.
 *
 * [id] is the row in `binaries`, which is also the name of the stored copy and the key
 * module results are written against.
 */
data class BinaryHandle(
    val id: Long,
    val path: String,
    val sizeBytes: Long? = null,
    val checksum: String? = null,
    val arch: String? = null,
    val format: String? = null,
)

/**
 * `module("…") { timeout = 600 }`
 *
 * [config] is a plain map on purpose: the module's config class lives in a jar the script
 * cannot name, so the runtime turns this into the declared class for it.
 */
data class ModuleSpec(
    val className: String,
    val timeoutSeconds: Int? = null,
    val config: Map<String, Any?>? = null,
    /** Open the program for writing and save it after the run. */
    val writable: Boolean = false,
) {
    fun withTimeout(seconds: Int) = copy(timeoutSeconds = seconds)
    fun withConfig(values: Map<String, Any?>) = copy(config = values)
    fun writable() = copy(writable = true)
}
