package org.iotsplab.akiba.module

import java.time.Instant
import java.util.Collections

/**
 * In-memory record of what an [AkibaModule] did during a single `startProcess`
 * call, so a parent can react to the child's side-effects instead of querying
 * the database for them.
 *
 * Keys: `"data"` (cumulative `updateData` writes), `"err_msg"`, `"start_time"`,
 * `"end_time"` and `"execution_time_ms"`. The parent creates the map and passes
 * it to the child via the `runtimeReport` constructor parameter. No locking is
 * needed: `callModule` is suspend and finishes the child before the parent reads.
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

    /** Record a failure stack trace for diagnostics. */
    internal fun recordTraceback(traceback: String?) {
        backing[KEY_TRACEBACK] = traceback
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

        /** Map key for the stack trace captured when the module failed. */
        const val KEY_TRACEBACK: String = "traceback"

        /** Map key for the [Instant] at which `DatabaseClient.startTask` was reported. */
        const val KEY_START_TIME: String = "start_time"

        /** Map key for the [Instant] at which `DatabaseClient.finishTask` was reported. */
        const val KEY_END_TIME: String = "end_time"

        /** Map key for the total wall time spent inside `startProcess()` (milliseconds). */
        const val KEY_EXECUTION_TIME_MS: String = "execution_time_ms"
    }
}
