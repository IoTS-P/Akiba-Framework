package managers

import org.iotsplab.akiba.managers.TurnQueue
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A session's turns keep their order; two sessions do not wait for each other. */
class TurnQueueTest {

    private var counter = 0
    private fun request(session: String, prompt: String = "p") =
        TurnQueue.Request("r-${counter++}", session, "program-1", prompt)

    @Test
    fun `one session's turns come out in the order they went in`() {
        val queue = TurnQueue()
        val first = request("session-a", "one")
        val second = request("session-a", "two")
        queue.submit(first); queue.submit(second)

        assertEquals("one", queue.poll("session-a")!!.prompt)
        assertEquals("two", queue.poll("session-a")!!.prompt)
        assertNull(queue.poll("session-a"))
    }

    @Test
    fun `two sessions are queued independently`() {
        val queue = TurnQueue()
        queue.submit(request("session-a", "a1"))
        queue.submit(request("session-b", "b1"))

        assertEquals("b1", queue.poll("session-b")!!.prompt, "b does not wait behind a")
        assertEquals("a1", queue.poll("session-a")!!.prompt)
        assertEquals(setOf<String>(), queue.waitingSessions())
    }

    @Test
    fun `a flooding session is capped instead of starving the worker`() {
        val queue = TurnQueue(capacityPerSession = 2)
        assertTrue(queue.submit(request("session-a")))
        assertTrue(queue.submit(request("session-a")))
        assertFalse(queue.submit(request("session-a")), "third request is refused")

        assertTrue(queue.submit(request("session-b")), "another session is unaffected")
        assertEquals(2, queue.pending("session-a"))
    }

    @Test
    fun `cancelling a session drops only its backlog`() {
        val queue = TurnQueue()
        repeat(3) { queue.submit(request("session-a")) }
        queue.submit(request("session-b"))

        assertEquals(3, queue.cancelSession("session-a"))
        assertEquals(0, queue.pending("session-a"))
        assertEquals(1, queue.pending("session-b"), "the other session keeps its turn")
    }

    @Test
    fun `take waits for work instead of spinning`() {
        val queue = TurnQueue()
        var received: TurnQueue.Request? = null

        val waiter = thread { received = queue.take("session-a", timeoutMs = 2000) }
        Thread.sleep(100)
        queue.submit(request("session-a", "late"))

        waiter.join(2000)
        assertEquals("late", received!!.prompt)
    }

    @Test
    fun `an idle session gives up waiting`() {
        val queue = TurnQueue()
        val started = System.currentTimeMillis()
        assertNull(queue.take("nobody", timeoutMs = 50))
        assertTrue(System.currentTimeMillis() - started >= 40)
    }
}
