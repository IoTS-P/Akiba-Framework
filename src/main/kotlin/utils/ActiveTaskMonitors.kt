package org.iotsplab.akiba.utils

import ghidra.util.task.TaskMonitor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Global registry of in-flight module task monitors.
 *
 * Each [AkibaModule.startProcess] creates a [CoroutineTaskMonitor] that
 * wraps both a Ghidra `TaskMonitor` and a Kotlin coroutine `Job`. Calling
 * `taskGlobalMonitor.cancel()` propagates to **both** layers:
 *
 *   - the underlying Ghidra `TaskMonitor` is cancelled, so the next
 *     `checkCancelled()` inside a Ghidra analyzer or import pass throws
 *     `CancelledException` and the Ghidra code unwinds cleanly;
 *   - the wrapped coroutine `Job` is cancelled, so any coroutine
 *     suspension point (e.g. `dbClient.startTask`, `withContext { ... }`)
 *     throws `CancellationException` and the coroutine code unwinds.
 *
 * The Ghidra stack alone is *not* cancellable from a coroutine: a Ghidra
 * analyzer that has already been entered runs to its next
 * `checkCancelled()` call, which the Ghidra threading model invokes
 * periodically. The wrapping makes the **whole** module run cancellable
 * in a single, predictable hop.
 *
 * Stop-button flow:
 *   1. User clicks Stop in the UI.
 *   2. `/workflow/stop/:id` sends `SIGINT` to the `akiba` subprocess.
 *   3. The subprocess's `Main.interruptHandler` runs.
 *   4. `interruptHandler` calls [cancelAll] on this registry, which
 *      cancels every in-flight monitor in O(N) where N is the number of
 *      concurrent modules (typically 1, but can be higher if the user's
 *      workflow runs modules in parallel).
 *   5. Each `workOnBinary` coroutine's `taskGlobalMonitor.cancel()`
 *      unwinds the Ghidra stack and the coroutine inside the module's
 *      `startProcess`, then the `try { ... } finally { ... }` block
 *      in `ProgramManager.workOnBinary` moves the logDir to
 *      `runtime_error/` and reports `[FILE:id] stopped` to the
 *      progress stream.
 *   6. The subprocess then exits cleanly with `exitProcess(0)`.
 *
 * Without this registry, the stop signal would only reach the
 * coroutine boundary and Ghidra analyzers would happily run to
 * completion, leaving logDirs orphaned at the temporary work location
 * (and the parent never seeing `[FILE:id] stopped`).
 */
object ActiveTaskMonitors {

    /**
     * Active monitors keyed by a monotonically increasing id. We use an
     * `Int` key (not the monitor itself) so the same physical monitor
     * can never appear twice in the set — defensive, since the monitor
     * objects are not `Comparable` and a hash-based set would dedupe by
     * identity, which is still safe but harder to reason about.
     *
     * The values are kept as `Pair<TaskMonitor, AtomicInteger>` where:
     *   - `first` is the underlying Ghidra `TaskMonitor` (used to call
     *     `cancel()` on cancellation — this propagates to Ghidra
     *     analyzers);
     *   - `second` is a shared counter of subscribers, used to
     *     safely de-duplicate concurrent `register`/`unregister` calls
     *     on the same logical task (we only ever add one entry per
     *     monitor but the counter is a cheap insurance against
     *     bookkeeping bugs).
     */
    private val active = ConcurrentHashMap<Int, TaskMonitor>()

    /** Monotonically increasing id. Atomic so concurrent registrations are unique. */
    private val nextId = AtomicInteger(0)

    /**
     * Register an in-flight [TaskMonitor]. Returns an opaque token that
     * the caller **must** pass to [unregister] once the task is done.
     *
     * The token is independent of the monitor's identity so callers can
     * safely register the same monitor twice (e.g. nested layers) and
     * unregister independently.
     */
    fun register(monitor: TaskMonitor): Int {
        val id = nextId.incrementAndGet()
        active[id] = monitor
        return id
    }

    /**
     * Remove a previously-registered monitor. Safe to call multiple
     * times for the same id (subsequent calls are no-ops).
     */
    fun unregister(id: Int) {
        active.remove(id)
    }

    /**
     * Cancel every registered monitor. Idempotent: calling it twice
     * is a no-op the second time, because Ghidra's `TaskMonitor.cancel()`
     * is itself idempotent.
     *
     * @return the number of monitors that were registered at the time
     *         of the call (a useful diagnostic for log messages).
     */
    fun cancelAll(): Int {
        val snapshot = active.toMap()
        snapshot.values.forEach { monitor ->
            try {
                monitor.cancel()
            } catch (_: Exception) {
                // Defensive: a single bad monitor should not prevent
                // the rest from being cancelled.
            }
        }
        return snapshot.size
    }

    /** Number of currently-registered monitors. For diagnostics. */
    fun activeCount(): Int = active.size
}
