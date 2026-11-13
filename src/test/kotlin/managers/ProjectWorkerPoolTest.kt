package managers

import org.iotsplab.akiba.managers.ProjectWorkerPool
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeWorker : ProjectWorkerPool.WorkerProcess {
    @Volatile override var alive = true
    @Volatile override var exitCode: Int? = null
    var stopped = false

    override fun stop() {
        stopped = true
        alive = false
        exitCode = 0
    }

    fun crash() {
        alive = false
        exitCode = 137
    }
}

/**
 * The pool's job is to keep one worker per project alive and to never hand out a dead
 * one — a dead worker would mean a turn that fails with no explanation.
 */
class ProjectWorkerPoolTest {

    private val spawned = mutableListOf<FakeWorker>()
    private var now = 0L

    private fun pool(idleMs: Long = 1000) = ProjectWorkerPool(
        idleTimeoutMs = idleMs,
        clock = { now },
        spawn = { FakeWorker().also { spawned += it } },
    )

    @Test
    fun `two turns share one worker per project`() {
        val pool = pool()

        val a = pool.acquire("fx20")
        val b = pool.acquire("fx20")

        assertTrue(a.process === b.process, "same worker serves both sessions")
        assertEquals(1, spawned.size)
        assertEquals(2, pool.refCount("fx20"))

        a.close()
        assertEquals(1, pool.refCount("fx20"))
        b.close()
        assertEquals(0, pool.refCount("fx20"))
        assertTrue(pool.isRunning("fx20"), "idle is not dead")
    }

    @Test
    fun `different projects get different workers`() {
        val pool = pool()
        val a = pool.acquire("fx20")
        val b = pool.acquire("other")

        assertFalse(a.process === b.process)
        assertEquals(2, spawned.size)
        a.close(); b.close()
    }

    @Test
    fun `a crashed worker is replaced instead of handed out`() {
        val pool = pool()
        val first = pool.acquire("fx20")
        val original = first.process as FakeWorker
        first.close()

        original.crash()

        val next = pool.acquire("fx20")
        assertFalse(next.process === original, "the dead worker is not reused")
        assertEquals(2, spawned.size)
        assertTrue(original.stopped, "the corpse was cleaned up")
        next.close()
    }

    @Test
    fun `an idle worker is stopped, a borrowed one is not`() {
        val pool = pool(idleMs = 1000)
        val busy = pool.acquire("busy")
        val idle = pool.acquire("idle")
        idle.close()

        now = 500
        assertTrue(pool.sweepIdle().isEmpty())

        now = 1500
        assertEquals(listOf("idle"), pool.sweepIdle())
        assertTrue(pool.isRunning("busy"), "a borrowed worker survives the sweep")
        busy.close()
    }

    @Test
    fun `discard drops a project so the next turn starts clean`() {
        val pool = pool()
        val lease = pool.acquire("fx20")
        lease.close()

        pool.discard("fx20")
        assertFalse(pool.isRunning("fx20"))

        val fresh = pool.acquire("fx20")
        assertEquals(2, spawned.size)
        fresh.close()
    }
}
