package org.iotsplab.akiba.module

import java.time.Instant
import java.util.Collections

/**
 * In-memory, write-only-from-the-child / read-only-from-the-parent record of what an
 * [AkibaModule] did during a single `startProcess` invocation.
 *
 * Until now, a module's runtime side-effects (database writes via `updateData` /
 * `updateErr`, plus the `startTask` / `finishTask` timing pair recorded via
 * [org.iotsplab.akiba.data.database.DatabaseClient]) were only observable from outside the
 * Akiba process — by querying the database. That is fine for offline post-processing but
 * very awkward when a parent module just spawned a child via [AkibaModule.callModule] and
 * wants to react to what the child produced *in this same run*.
 *
 * `RuntimeReport` plugs that gap by mirroring a fixed set of side-effects into a plain
 * in-memory map as they happen:
 *
 *  | Key                 | Value type            | Written by                               |
 *  | ------------------- | --------------------- | ---------------------------------------- |
 *  | `"data"`            | `Map<String, Any?>`   | every [AkibaModule.updateData] call (cumulative; later writes overwrite earlier values for the same column) |
 *  | `"err_msg"`         | `String?`             | [AkibaModule.updateErr] / [AkibaModule.clearErr] |
 *  | `"start_time"`      | [Instant]             | the moment `startTask` is reported to the daemon |
 *  | `"end_time"`        | [Instant]             | the moment `finishTask` is reported to the daemon |
 *  | `"execution_time_ms"` | [Long]              | total wall time spent inside `startProcess()` |
 *
 * The map is constructed by the parent (typically [AkibaModule.callModule]) and passed
 * into the child via the new `runtimeReport` constructor parameter; the child's base-class
 * machinery does the populating, so module authors don't have to do anything special — the
 * report is filled in automatically. The parent reads the result via
 * [AkibaModule.runtimeReportView] *after* `startProcess` has returned.
 *
 * ### Concurrency
 *
 * A child module runs to completion before the parent observes the report (that is the
 * contract of [AkibaModule.callModule]: the call is `suspend` and only returns after the
 * child finished). No locking is therefore required between the child's writes and the
 * parent's reads. Within the child itself, all writes happen on the module's own coroutine
 * and are sequential, so a plain mutable map is enough.
 */
class RuntimeReport {
    private val backing: MutableMap<String, Any?> = mutableMapOf()
    private val accumulatedData: MutableMap<String, Any?> = mutableMapOf()

    /**
     * Read-only view onto the report. The returned map reflects later mutations made by
     * the child (it's a live view, not a copy), but its keys/values cannot be modified
     * through this reference.
     */
    val view: Map<String, Any?> = Collections.unmodifiableMap(backing)

    init {
        // Make the "data" entry visible from the start as an unmodifiable view onto our
        // internal accumulator; this way the parent never sees a `null` for that key, only
        // an empty map until the child's first updateData() call.
        backing[KEY_DATA] = Collections.unmodifiableMap(accumulatedData)
    }

    /**
     * Record an [AkibaModule.updateData] call. Multiple calls accumulate: later writes for
     * the same column overwrite earlier ones, identical to how the database row evolves
     * across successive UPDATE statements.
     */
    internal fun recordUpdateData(data: Map<String, Any?>) {
        accumulatedData.putAll(data)
    }

    /** Record an [AkibaModule.updateErr] call (or [AkibaModule.clearErr], with `null`). */
    internal fun recordErr(msg: String?) {
        backing[KEY_ERR_MSG] = msg
    }

    /** Record the moment `DatabaseClient.startTask` is invoked. */
    internal fun recordStart(time: Instant) {
        backing[KEY_START_TIME] = time
    }

    /** Record the moment `DatabaseClient.finishTask` is invoked. */
    internal fun recordEnd(time: Instant) {
        backing[KEY_END_TIME] = time
    }

    /** Record the wall time spent inside the module's `startProcess()` body. */
    internal fun recordExecutionTime(ms: Long) {
        backing[KEY_EXECUTION_TIME_MS] = ms
    }

    override fun toString(): String = "RuntimeReport(${backing})"

    companion object {
        /** Map key for the cumulative [AkibaModule.updateData] payload. */
        const val KEY_DATA: String = "data"

        /** Map key for the latest [AkibaModule.updateErr] message (or `null`). */
        const val KEY_ERR_MSG: String = "err_msg"

        /** Map key for the [Instant] at which `DatabaseClient.startTask` was reported. */
        const val KEY_START_TIME: String = "start_time"

        /** Map key for the [Instant] at which `DatabaseClient.finishTask` was reported. */
        const val KEY_END_TIME: String = "end_time"

        /** Map key for the total wall time spent inside `startProcess()` (milliseconds). */
        const val KEY_EXECUTION_TIME_MS: String = "execution_time_ms"
    }
}
