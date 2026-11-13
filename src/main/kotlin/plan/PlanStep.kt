package org.iotsplab.akiba.plan

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The data a plan script records while it runs.
 *
 * A script never executes anything itself: query verbs hit the read-only index and
 * action verbs append a [Step]. [PlanExecutor] walks the result later, which is what
 * makes a dry run and an audit trail free.
 */
@Serializable
data class Plan(
    val planVersion: Int = 1,
    val script: String,
    val scriptHash: String,
    val instance: String,
    val steps: List<Step>,
)

/** One unit of work. [deps] are step numbers that must finish first. */
@Serializable
data class Step(
    val n: Int,
    val kind: StepKind,
    val deps: List<Int> = emptyList(),
    val retry: Int = 0,
    val onFailure: Failure = Failure.ABORT,
)

enum class Failure { ABORT, SKIP, CONTINUE }

enum class Mode { SEQUENTIAL, PARALLEL }

/**
 * The programs a step works on.
 *
 * A filter over a large project easily yields thousands of ids; inlining them would
 * turn the plan file into a megabyte of UUIDs. Past [PlanWriter.spillThreshold] the
 * ids go to a side file and the plan keeps the count plus the file name.
 */
@Serializable
sealed interface ItemSet {
    @Serializable
    @SerialName("ids")
    data class Ids(val ids: List<String>) : ItemSet

    @Serializable
    @SerialName("spilled")
    data class Spilled(val count: Int, val ref: String) : ItemSet
}

@Serializable
sealed interface StepKind {

    /** Fails with [ProjectExistsException] when the name is taken. */
    @Serializable
    @SerialName("createProject")
    data class CreateProject(
        val project: String,
        val overwrite: Boolean = false,
    ) : StepKind

    /**
     * Opens a project, creating it when [createIfMissing] is set — the recorded form
     * of `createOrOpenProject`. Without the flag a missing project is an error, which
     * keeps a typo from silently producing a new empty batch.
     */
    @Serializable
    @SerialName("openProject")
    data class OpenProject(
        val project: String,
        val createIfMissing: Boolean = false,
    ) : StepKind

    @Serializable
    @SerialName("import")
    data class Import(
        val items: ItemSet,
        val project: String,
        val folder: String? = null,
        val concurrency: Int = 1,
    ) : StepKind

    /** Runs a module over [items]; null means "the program the task opened". */
    @Serializable
    @SerialName("runTask")
    data class RunTask(
        val module: String,
        val items: ItemSet? = null,
        val timeoutSeconds: Int? = null,
        val concurrency: Int = 1,
    ) : StepKind

    @Serializable
    @SerialName("config")
    data class Config(
        val section: String,
        val values: Map<String, String>,
    ) : StepKind

    @Serializable
    @SerialName("group")
    data class Group(
        val mode: Mode,
        val children: List<Step>,
    ) : StepKind
}

/**
 * Writes a [Plan] to disk.
 *
 * [spillThreshold] is the point where an id list stops being inlined. The default
 * keeps a plan readable by eye: a few hundred ids are still fine to grep, a few
 * thousand are not.
 */
class PlanWriter(private val spillThreshold: Int = 500) {

    private val json = Json {
        prettyPrint = true
        classDiscriminator = "kind"
        encodeDefaults = false
        explicitNulls = false
    }

    fun encode(plan: Plan): String = json.encodeToString(Plan.serializer(), plan)

    fun decode(text: String): Plan = json.decodeFromString(Plan.serializer(), text)

    /**
     * Returns the [ItemSet] to record for [ids], spilling to `<dir>/<step>.ids`
     * when the list is too long to inline. [dir] must be writable; pass null to
     * always inline (used by tests and small plans).
     */
    fun items(step: Int, ids: List<String>, dir: java.io.File?): ItemSet =
        if (dir != null && ids.size > spillThreshold) {
            val ref = "step-$step.ids"
            dir.mkdirs()
            java.io.File(dir, ref).writeText(ids.joinToString("\n"))
            ItemSet.Spilled(ids.size, ref)
        } else {
            ItemSet.Ids(ids)
        }
}
