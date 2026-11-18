package org.iotsplab.akiba.plan

/**
 * Everything a plan can fail with, so a script can tell "the project already exists"
 * apart from "the query was wrong" without matching on message text.
 */
open class PlanException(
    message: String,
    val step: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/** `createProject` found the name taken. Use [createOrOpenProject] to reuse it. */
class ProjectExistsException(val project: String, step: Int? = null) :
    PlanException("Project '$project' already exists", step)

/** `openProject` was asked for a project that is not there. A typo must not create one. */
class ProjectMissingException(val project: String, step: Int? = null) :
    PlanException("Project '$project' does not exist; use createOrOpenProject to create it", step)

/** `importBinary` found the file already registered, by checksum. */
class BinaryExistsException(val path: String, val checksum: String, step: Int? = null) :
    PlanException("'$path' is already in the database (checksum $checksum)", step)

/** `binaries(filter)` was given a condition the database rejected. */
class BinaryQueryException(val sql: String, cause: Throwable, step: Int? = null) :
    PlanException("Binary query failed: ${cause.message}", step, cause)

/** A verb referenced a program the plan cannot resolve. */
class ProgramMissingException(val programId: String, step: Int? = null) :
    PlanException("Program '$programId' is not in the index any more", step)

/** The read-only query behind `programs.filter(sql)` failed. */
class PlanQueryException(val sql: String, cause: Throwable, step: Int? = null) :
    PlanException("Program query failed: ${cause.message}", step, cause)

/** The plan script did not compile. */
class PlanCompileException(val script: String, val diagnostics: List<String>) :
    PlanException("Plan script '$script' failed to compile:\n" + diagnostics.joinToString("\n"))

/** A step failed while the executor was running it. */
class PlanStepException(
    val stepNumber: Int,
    val kind: String,
    message: String,
    cause: Throwable? = null,
) : PlanException("Step $stepNumber ($kind) failed: $message", stepNumber, cause)

/**
 * Either a value or the reason there is none.
 *
 * Plan-time verbs return this instead of throwing, so one bad call in a script does
 * not end the run: the script can inspect it, skip it, or call [expect] to turn it
 * back into an exception where that is the right answer.
 */
sealed interface Outcome<out T> {

    data class Ok<out T>(val value: T) : Outcome<T>

    data class Failed(val error: PlanException) : Outcome<Nothing>

    /** The value, or the failure as a thrown [PlanException]. */
    fun expect(): T = when (this) {
        is Ok -> value
        is Failed -> throw error
    }

    fun getOrNull(): T? = (this as? Ok)?.value

    fun <R> map(transform: (T) -> R): Outcome<R> = when (this) {
        is Ok -> Ok(transform(value))
        is Failed -> this
    }

    /** Runs [block] on failure and passes the outcome through. */
    fun onFailure(block: (PlanException) -> Unit): Outcome<T> {
        if (this is Failed) block(error)
        return this
    }

    fun recover(block: (PlanException) -> @UnsafeVariance T): Outcome<T> = when (this) {
        is Ok -> this
        is Failed -> Ok(block(error))
    }
}

inline fun <T> outcomeOf(block: () -> T): Outcome<T> =
    try {
        Outcome.Ok(block())
    } catch (e: PlanException) {
        Outcome.Failed(e)
    }
