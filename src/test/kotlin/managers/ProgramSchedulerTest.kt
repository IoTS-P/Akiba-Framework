package managers

import org.iotsplab.akiba.managers.ProgramScheduler
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Two sessions may share a project; they may not share a program at the same instant.
 * These tests pin the second half of that sentence.
 */
class ProgramSchedulerTest {

    private val scheduler = ProgramScheduler(defaultTimeoutMs = 2000)

    @Test
    fun `the same program runs one session at a time`() {
        val inside = AtomicInteger()
        val peak = AtomicInteger()
        val done = CountDownLatch(4)

        repeat(4) { i ->
            thread {
                scheduler.withProgram("fx20", "session-$i") {
                    val now = inside.incrementAndGet()
                    peak.updateAndGet { max -> if (now > max) now else max }
                    Thread.sleep(25)
                    inside.decrementAndGet()
                }
                done.countDown()
            }
        }
        done.await(5, TimeUnit.SECONDS)
        assertEquals(1, peak.get(), "never two sessions inside one program")
    }

    @Test
    fun `different programs run in parallel`() {
        val both = CountDownLatch(2)
        val ok = AtomicInteger()

        repeat(2) { i ->
            thread {
                scheduler.withProgram("program-$i", "session-$i") {
                    both.countDown()
                    if (both.await(2, TimeUnit.SECONDS)) ok.incrementAndGet()
                }
            }
        }
        Thread.sleep(300)
        assertEquals(2, ok.get(), "different programs are independent")
    }

    @Test
    fun `a tool that calls another tool does not deadlock`() {
        val depth = scheduler.withProgram("fx20", "session-a") {
            scheduler.withProgram("fx20", "session-a") { "inner" }
            "outer"
        }
        assertEquals("outer", depth)
        assertEquals(null, scheduler.holder("fx20"), "released after the outer block")
    }

    @Test
    fun `a waiting session gives up and learns who holds the program`() {
        val release = CountDownLatch(1)
        val held = CountDownLatch(1)

        thread {
            scheduler.withProgram("fx20", "session-owner", timeoutMs = 5000) {
                held.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        held.await(2, TimeUnit.SECONDS)

        val error = assertFailsWith<ProgramScheduler.ProgramBusyException> {
            scheduler.withProgram("fx20", "session-waiter", timeoutMs = 100) { }
        }
        assertEquals("fx20", error.programId)
        assertEquals("session-owner", error.holder)
        assertTrue(error.message!!.contains("session-owner"))

        release.countDown()
        Thread.sleep(150)
        assertEquals(null, scheduler.holder("fx20"))
    }

    @Test
    fun `an exception inside the block still releases the program`() {
        assertFailsWith<IllegalStateException> {
            scheduler.withProgram<Unit>("fx20", "session-a") { error("tool blew up") }
        }
        assertEquals(null, scheduler.holder("fx20"))

        var ran = false
        scheduler.withProgram<Unit>("fx20", "session-b") { ran = true }
        assertTrue(ran, "the next session was able to enter")
    }

    @Test
    fun `a session that comes back is told what happened meanwhile`() {
        scheduler.withProgram("fx20", "session-a", operation = "rename-program") { it }

        scheduler.withProgram("fx20", "6df217e5", operation = "decompile") { it }

        val lease = scheduler.withProgram("fx20", "session-a", operation = "list-symbols") { it }
        assertEquals(1, lease.foreign.size, "only the other session's call is reported")
        assertEquals("6df217e5", lease.foreign.single().sessionId)
        assertTrue(lease.stateChanged, "decompile is recorded as a mutation by default")

        val note = lease.note()!!
        assertTrue("session 6df217e5" in note, note)
        assertTrue("decompile" in note, note)
        assertTrue(note.contains("Re-read"), "the note tells the model what to do about it")
    }

    @Test
    fun `a read by someone else does not claim the state changed`() {
        scheduler.withProgram("fx20", "session-b", operation = "read-bytes", mutated = false) { it }

        val lease = scheduler.withProgram("fx20", "session-a") { it }
        assertTrue(lease.foreign.isNotEmpty())
        assertTrue(!lease.stateChanged, "a pure read leaves the program as it was")
        assertTrue(lease.note()!!.contains("read this program"), lease.note()!!)
    }

    @Test
    fun `a session is never told about its own calls`() {
        scheduler.withProgram("fx20", "session-a", operation = "one") { it }
        scheduler.withProgram("fx20", "session-a", operation = "two") { it }

        val lease = scheduler.withProgram("fx20", "session-a", operation = "three") { it }
        assertTrue(lease.foreign.isEmpty(), "own history is not interleaving")
        assertEquals(null, lease.note())
    }

    @Test
    fun `waiters queue in order`() {
        val order = Collections.synchronizedList(mutableListOf<Int>())
        val gate = CountDownLatch(1)

        thread { scheduler.withProgram<Unit>("fx20", "owner", timeoutMs = 5000) { gate.await(5, TimeUnit.SECONDS) } }
        Thread.sleep(100)

        (1..3).forEach { i ->
            thread {
                scheduler.withProgram<Unit>("fx20", "session-$i", timeoutMs = 5000) { order.add(i) }
            }
            Thread.sleep(50)
        }
        gate.countDown()
        Thread.sleep(400)
        assertEquals(listOf(1, 2, 3), order, "fair queue, no barging")
    }
}
