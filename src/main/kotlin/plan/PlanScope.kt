package org.iotsplab.akiba.plan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** A project a plan writes into. Cheap to build; nothing is created until a verb runs. */
data class ProjectRef(val name: String) {
    /** `batch / "raw"` — the folder part of an import target. */
    operator fun div(folder: String): ImportTarget = ImportTarget(this, folder)
}

/** Where imports land: a project and its folder. */
data class ImportTarget(val project: ProjectRef, val folder: String?)

/** A program as the plan sees it: identity plus the facts a script may branch on. */
data class ProgramHandle(
    val id: String,
    val name: String,
    val arch: String? = null,
    val sizeBytes: Long? = null,
    val path: String? = null,
)

/** `module("…") { timeout = 600 }` */
data class ModuleSpec(val className: String, val timeoutSeconds: Int? = null) {
    fun withTimeout(seconds: Int) = copy(timeoutSeconds = seconds)
}

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

/**
 * What a plan script talks to.
 *
 * Verbs run immediately — the script is the program, not a description of one — and
 * each one returns an [Outcome] so a single failure does not end the run. Every call
 * is appended to [journal] as it happens; that log replaces the old preview.
 */
class PlanScope(
    private val runtime: PlanRuntime,
    private val journal: PlanJournal = PlanJournal.disabled(),
) {

    // ---- projects -------------------------------------------------------

    /** Fails with [ProjectExistsException] when the name is taken. */
    fun createProject(name: String, overwrite: Boolean = false): Outcome<ProjectRef> =
        step("createProject", mapOf("project" to name, "overwrite" to overwrite)) {
            if (!overwrite && runtime.projectExists(name)) throw ProjectExistsException(name)
            runtime.createProject(name, overwrite)
        }

    /** Reuses an existing project, creates it otherwise. */
    fun createOrOpenProject(name: String): Outcome<ProjectRef> =
        step("createOrOpenProject", mapOf("project" to name)) {
            runtime.openProject(name, createIfMissing = true)
        }

    /** Requires the project to exist; a typo is an error rather than a new batch. */
    fun openProject(name: String): Outcome<ProjectRef> =
        step("openProject", mapOf("project" to name)) {
            if (!runtime.projectExists(name)) throw ProjectExistsException("$name (missing)")
            runtime.openProject(name, createIfMissing = false)
        }

    // ---- work -----------------------------------------------------------

    /** Imports every program in [programs] into [target]. */
    fun importAll(programs: List<ProgramHandle>, target: ImportTarget): Outcome<Int> =
        step(
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

    /** Runs [spec] over [programs]; an empty list means "whatever the task opened". */
    fun runAll(programs: List<ProgramHandle>, spec: ModuleSpec): Outcome<Int> =
        step(
            "runTask",
            mapOf(
                "module" to spec.className,
                "count" to programs.size,
                "timeout" to (spec.timeoutSeconds ?: 0),
            ),
        ) { runtime.runModule(spec, programs.map { it.id }) }

    // ---- plumbing -------------------------------------------------------

    /** Runs [block], records the step with its outcome, and never throws a [PlanException]. */
    private inline fun <T> step(
        kind: String,
        args: Map<String, Any?>,
        block: () -> T,
    ): Outcome<T> {
        val started = System.currentTimeMillis()
        val result = try {
            Outcome.Ok(block())
        } catch (e: PlanException) {
            Outcome.Failed(e)
        } catch (e: Exception) {
            // Anything else is a runtime fault of the implementation; surface it as a
            // plan failure so the script decides, but keep the cause.
            Outcome.Failed(PlanException("$kind failed: ${e.message}", cause = e))
        }
        journal.record(kind, args, result, System.currentTimeMillis() - started)
        return result
    }
}

/**
 * Append-only record of what a plan actually did.
 *
 * The old preview tried to predict a run before it happened; a script that branches on
 * query results makes that guess worthless. This log is written as the run unfolds, so
 * it always matches reality and still answers "what did this batch do".
 */
class PlanJournal(private val file: File?) {

    private val json = Json { encodeDefaults = false }

    @Synchronized
    fun record(kind: String, args: Map<String, Any?>, result: Outcome<*>, elapsedMs: Long) {
        val target = file ?: return
        val line = buildJsonObject {
            put("kind", kind)
            put("ms", elapsedMs)
            when (result) {
                is Outcome.Ok -> {
                    put("status", "ok")
                    put("value", result.value?.toString() ?: "")
                }
                is Outcome.Failed -> {
                    put("status", "failed")
                    put("error", result.error.message)
                }
            }
            put("args", buildJsonObject { args.forEach { (k, v) -> put(k, v?.toString() ?: "") } })
        }
        target.parentFile?.mkdirs()
        target.appendText(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), line) + "\n")
    }

    companion object {
        fun disabled() = PlanJournal(null)
        fun at(file: File) = PlanJournal(file)
    }
}
