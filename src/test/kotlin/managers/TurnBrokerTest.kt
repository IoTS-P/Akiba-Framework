package managers

import org.iotsplab.akiba.managers.TurnBroker
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Who the worker gets next, and what the waiting session is told when it is done. */
class TurnBrokerTest {

    private var counter = 0
    private fun turn(session: String, prompt: String = "p", project: String = "fx20") =
        TurnBroker.Turn("t-${counter++}", project, session, "program-1", prompt)

    @Test
    fun `a turn carries the model it must use`() {
        val broker = TurnBroker()
        val llm = TurnBroker.TurnLlm("OPEN_AI", "gpt-5.5", "sk-this-turn", "https://example.test/v1")
        broker.submit(TurnBroker.Turn("t-1", "alpha", "session-a", "program-1", "hi", llm = llm))

        val handed = broker.next("alpha", 1_000)!!

        assertEquals("gpt-5.5", handed.llm?.modelName)
        assertEquals("sk-this-turn", handed.llm?.apiKey)
        assertEquals("https://example.test/v1", handed.llm?.baseUrl)
    }

    @Test
    fun `sessions take turns instead of queueing behind a backlog`() {
        val broker = TurnBroker()
        broker.submit(turn("session-a", "a1"))
        broker.submit(turn("session-a", "a2"))
        broker.submit(turn("session-a", "a3"))
        broker.submit(turn("session-b", "b1"))

        assertEquals("a1", broker.next("fx20", 100)!!.prompt)
        assertEquals("b1", broker.next("fx20", 100)!!.prompt, "b is not stuck behind a's backlog")
        assertEquals("a2", broker.next("fx20", 100)!!.prompt)
        assertEquals("a3", broker.next("fx20", 100)!!.prompt)
    }

    @Test
    fun `a session's own turns stay in order`() {
        val broker = TurnBroker()
        listOf("first", "second", "third").forEach { broker.submit(turn("session-a", it)) }

        assertEquals("first", broker.next("fx20", 100)!!.prompt)
        assertEquals("second", broker.next("fx20", 100)!!.prompt)
        assertEquals("third", broker.next("fx20", 100)!!.prompt)
    }

    @Test
    fun `another project's work is not handed out`() {
        val broker = TurnBroker()
        broker.submit(turn("session-a", "other-project", project = "other"))

        assertNull(broker.next("fx20", 50), "the fx20 worker sees nothing from other")
        assertEquals("other-project", broker.next("other", 50)!!.prompt)
    }

    @Test
    fun `the waiting session learns when its turn is done`() {
        val broker = TurnBroker()
        val submitted = turn("session-a")
        broker.submit(submitted)
        val future = broker.resultOf(submitted.id)!!

        val handed = broker.next("fx20", 100)!!
        broker.finish(handed.id, TurnBroker.Result("ok"))

        assertTrue(future.get(1, TimeUnit.SECONDS).ok)
    }

    @Test
    fun `a failed turn tells the session why`() {
        val broker = TurnBroker()
        val submitted = turn("session-a")
        broker.submit(submitted)

        broker.finish(submitted.id, TurnBroker.Result("failed", "worker restarted"))

        val result = broker.resultOf(submitted.id)
        assertTrue(result == null || result.isDone, "the future was completed")
    }

    @Test
    fun `a flooding session is capped`() {
        val broker = TurnBroker(capacityPerSession = 2)
        assertTrue(broker.submit(turn("session-a")))
        assertTrue(broker.submit(turn("session-a")))
        assertFalse(broker.submit(turn("session-a")))
        assertTrue(broker.submit(turn("session-b")), "other sessions are unaffected")
    }

    @Test
    fun `stopping a session drops its backlog and wakes its waiters`() {
        val broker = TurnBroker()
        val first = turn("session-a")
        val second = turn("session-a")
        broker.submit(first)
        broker.submit(second)
        broker.submit(turn("session-b", "b1"))

        assertEquals(2, broker.cancelSession("session-a", "fx20"))
        assertEquals(1, broker.pending("fx20"))
        assertEquals("b1", broker.next("fx20", 100)!!.prompt)
    }
}
