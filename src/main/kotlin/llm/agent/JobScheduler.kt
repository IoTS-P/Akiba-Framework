package org.iotsplab.akiba.llm.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.apache.logging.log4j.LogManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// ============================================================
//  JobScheduler — bounded, priority-aware coroutine queue
// ============================================================

/**
 * Configuration for [JobScheduler] caps. Defaults are 32 children
 * per parent, 256 per root, 64 per binary — tuned conservatively
 * for typical LLM rate limits; raise for self-hosted model servers.
 */
data class SchedulerConfig(
    /** Max in-flight child Jobs per parent. Excess spawns are queued. */
    val maxConcurrentChildrenPerParent: Int = 32,
    /** Max in-flight child Jobs per root session. */
    val maxChildrenPerRoot: Int = 256,
    /** Max in-flight child Jobs across the whole binary. */
    val maxConcurrentMsgsHandledPerBinary: Int = 64,
    /**
     * Hard upper bound on queued (not yet running) Jobs. When the
     * queue is full, the scheduler rejects new spawns. Prevents
     * unbounded memory growth under a runaway LLM.
     */
    val maxQueuedJobs: Int = 1024,
    /** How long a queued Job waits before being admitted (ms). */
    val admitPollIntervalMs: Long = 200L,
)

/**
 * Per-binary Job scheduler. Owns the coroutine scope that all
 * child agent Jobs run in; enforces caps on the number of
 * concurrently running children; queues excess spawns.
 *
 * The scheduler is intentionally single-instance per binary —
 * multiple schedulers would re-introduce the cap problem.
 */
class JobScheduler(
    val binaryId: Int,
    val config: SchedulerConfig = SchedulerConfig(),
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val logger = LogManager.getLogger(JobScheduler::class.java)

    private val handles = ConcurrentHashMap<String, JobHandle>()
    private val queue = Channel<QueuedJob>(capacity = config.maxQueuedJobs)

    private val activePerParent = ConcurrentHashMap<String, AtomicInteger>()
    private val activePerRoot = ConcurrentHashMap<String, AtomicInteger>()
    private val activePerBinary = AtomicInteger(0)

    private val runner: Job = scope.launch {
        while (isActive) {
            try {
                admitLoop()
            } catch (e: Exception) {
                logger.warn("JobScheduler.admitLoop threw: ${e.message}", e)
            }
            delay(config.admitPollIntervalMs)
        }
    }

    /**
     * Register a Job handle. If a cap has room, the Job is admitted
     * immediately and [onAdmitted] is invoked; otherwise the handle
     * is queued and the call returns.
     */
    fun register(handle: JobHandle, onAdmitted: (JobHandle) -> Unit) {
        handles[handle.sessionId] = handle
        if (!tryAdmit(handle, onAdmitted)) {
            val queued = QueuedJob(handle, onAdmitted)
            val ok = queue.trySend(queued).isSuccess
            if (!ok) {
                logger.error(
                    "JobScheduler: queue is full (>= ${config.maxQueuedJobs}), " +
                        "dropping session ${handle.sessionId}"
                )
                handles.remove(handle.sessionId)
            }
        }
    }

    private fun tryAdmit(handle: JobHandle, onAdmitted: (JobHandle) -> Unit): Boolean {
        if (activePerBinary.get() >= config.maxConcurrentMsgsHandledPerBinary) return false
        val parentCounter = activePerParent.computeIfAbsent(handle.parentSessionId) { AtomicInteger(0) }
        if (parentCounter.get() >= config.maxConcurrentChildrenPerParent) return false
        // Per-root cap: key on `rootSessionId` (set at spawn time and
        // held constant for the lifetime of the handle), NOT on
        // `parentSessionId`. Using `parentSessionId` here meant every
        // direct child of a root was counted under the root's own
        // counter, so the per-root budget collapsed to "per-parent"
        // and gave no per-tree isolation.
        val rootCounter = activePerRoot.computeIfAbsent(handle.rootSessionId) { AtomicInteger(0) }
        if (rootCounter.get() >= config.maxChildrenPerRoot) return false
        return admit(handle, onAdmitted)
    }

    private fun admit(handle: JobHandle, onAdmitted: (JobHandle) -> Unit): Boolean {
        activePerBinary.incrementAndGet()
        activePerParent.computeIfAbsent(handle.parentSessionId) { AtomicInteger(0) }.incrementAndGet()
        activePerRoot.computeIfAbsent(handle.rootSessionId) { AtomicInteger(0) }.incrementAndGet()
        try {
            onAdmitted(handle)
        } catch (e: Exception) {
            logger.error("onAdmitted for ${handle.sessionId} threw: ${e.message}", e)
        }
        return true
    }

    /** Slot released by a child Job. Decrements the cap counters. */
    internal fun release(sessionId: String) {
        val handle = handles.remove(sessionId) ?: return
        activePerBinary.decrementAndGet()
        activePerParent[handle.parentSessionId]?.let {
            it.decrementAndGet()
            if (it.get() <= 0) activePerParent.remove(handle.parentSessionId)
        }
        activePerRoot[handle.rootSessionId]?.let {
            it.decrementAndGet()
            if (it.get() <= 0) activePerRoot.remove(handle.rootSessionId)
        }
    }

    private suspend fun admitLoop() {
        while (true) {
            val next = queue.tryReceive().getOrNull() ?: return
            if (!tryAdmit(next.handle, next.onAdmitted)) {
                // Put it back at the head and bail until next tick.
                queue.trySend(next)
                return
            }
        }
    }

    fun get(sessionId: String): JobHandle? = handles[sessionId]
    fun size(): Int = handles.size

    fun shutdown() {
        runner.cancel()
    }

    private data class QueuedJob(
        val handle: JobHandle,
        val onAdmitted: (JobHandle) -> Unit,
    )
}
