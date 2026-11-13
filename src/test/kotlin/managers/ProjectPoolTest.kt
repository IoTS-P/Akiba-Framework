package managers

import org.iotsplab.akiba.managers.ProjectPool
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeProject(val name: String) : AutoCloseable {
    var closed = false
    override fun close() {
        closed = true
    }
}

/**
 * Two sessions must be able to hold the same project at once — that is the whole point
 * of the pool — while a single program stays single-writer.
 */
class ProjectPoolTest {

    private fun pool(vararg opened: AtomicInteger) = ProjectPool<FakeProject>(
        idleTimeoutMs = 1000,
        open = { key ->
            if (opened.isNotEmpty()) opened[0].incrementAndGet()
            FakeProject(key)
        },
    )

    @Test
    fun `two leases share one open project`() {
        val opens = AtomicInteger()
        val pool = pool(opens)

        val first = pool.acquire("fx20")
        val second = pool.acquire("fx20")

        assertTrue(first.handle === second.handle, "the same open project is shared")
        assertEquals(1, opens.get(), "opened once, not per session")
        assertEquals(2, pool.refCount("fx20"))

        first.close()
        assertEquals(1, pool.refCount("fx20"))
        second.close()
        assertEquals(0, pool.refCount("fx20"))
    }

    @Test
    fun `different programs of one project run in parallel`() {
        val pool = pool()
        val lease = pool.acquire("fx20")
        val both = CountDownLatch(2)

        repeat(2) { i ->
            thread {
                pool.withProgram(lease.key, "program-$i") {
                    both.countDown()
                    // If programs shared one lock this would time out.
                    assertTrue(both.await(2, TimeUnit.SECONDS), "program $i ran alongside the other")
                }
            }
        }
        both.await(3, TimeUnit.SECONDS)
        lease.close()
    }

    @Test
    fun `the same program is single writer`() {
        val pool = pool()
        val lease = pool.acquire("fx20")
        val inside = AtomicInteger()
        val maxInside = AtomicInteger()

        repeat(4) {
            thread {
                pool.withProgram(lease.key, "same") {
                    val now = inside.incrementAndGet()
                    maxInside.updateAndGet { max -> if (now > max) now else max }
                    Thread.sleep(30)
                    inside.decrementAndGet()
                }
            }
        }
        Thread.sleep(400)
        assertEquals(1, maxInside.get(), "only one holder of a program at a time")
        lease.close()
    }

    @Test
    fun `an idle project is closed, a held one is not`() {
        var now = 0L
        val pool = ProjectPool<FakeProject>(idleTimeoutMs = 1000, clock = { now }, open = { FakeProject(it) })

        val held = pool.acquire("busy")
        val dropped = pool.acquire("idle")
        dropped.close()

        now = 500
        assertTrue(pool.sweepIdle().isEmpty(), "nothing expired yet")

        now = 1500
        val closed = pool.sweepIdle()
        assertEquals(listOf("idle"), closed)
        assertEquals(1, pool.refCount("busy"), "a live lease is never swept")
        assertEquals(0, pool.refCount("idle"))
        held.close()
    }
}
