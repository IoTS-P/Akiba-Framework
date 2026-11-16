package org.iotsplab.akiba.module

/**
 * The codes a module ends with, in the shape of a process exit status.
 *
 * `0` means it worked. Codes below [FIRST_CUSTOM] belong to the framework: a task chain
 * treats them as fatal and skips whatever came next. A module may invent anything from
 * [FIRST_CUSTOM] up, and a plan decides for itself what such a code means.
 */
object ExitCode {

    const val SUCCESS: Int = 0

    /** The module decided its result was unusable. */
    const val FAILED: Int = 1

    /** Something threw where the module did not expect it. */
    const val RUNTIME_ERROR: Int = 2

    /** The module was stopped because it ran too long. */
    const val TIMEOUT: Int = 3

    /** Someone asked the run to stop. */
    const val CANCELLED: Int = 4

    /** Nothing to do: the input did not need this module. */
    const val SKIPPED: Int = 5

    /** Modules may use this and everything above it. */
    const val FIRST_CUSTOM: Int = 100

    private val names: Map<Int, String> = mapOf(
        SUCCESS to "SUCCESS",
        FAILED to "FAILED",
        RUNTIME_ERROR to "RUNTIME_ERROR",
        TIMEOUT to "TIMEOUT",
        CANCELLED to "CANCELLED",
        SKIPPED to "SKIPPED",
    )

    /** `SUCCESS`, `FAILED`, or `EXIT_<n>` for a code the framework does not know. */
    fun nameOf(code: Int): String = names[code] ?: "EXIT_$code"

    /** Anything but [SUCCESS]. */
    fun isFailure(code: Int): Boolean = code != SUCCESS

    /**
     * Whether this code should stop a task chain.
     *
     * A module's own codes do not: it knows something the chain does not, and the caller
     * is the one that decides what to do about it.
     */
    fun isFatal(code: Int): Boolean = code in FAILED until FIRST_CUSTOM
}
