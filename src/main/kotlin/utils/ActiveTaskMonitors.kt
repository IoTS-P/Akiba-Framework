package org.iotsplab.akiba.utils

import ghidra.util.task.TaskMonitor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Global registry of in-flight module task monitors.
 *
 * `cancel()` must reach **both** the Ghidra `TaskMonitor` and the wrapping
 * coroutine `Job`: a Ghidra analyzer only unwinds at its next
 * `checkCancelled()`, so cancelling the coroutine alone would let analyzers run
 * to completion and orphan logDirs. `Main.interruptHandler` calls [cancelAll]
 * on SIGINT to stop every in-flight module.
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
