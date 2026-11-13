package managers

import org.iotsplab.akiba.llm.agent.dummy.DummyLLMClient
import org.iotsplab.akiba.llm.client.ChatCompletion
import org.iotsplab.akiba.llm.memory.AgentChatMessage
import org.iotsplab.akiba.managers.ProgramScheduler
import org.iotsplab.akiba.managers.ProjectTurnDispatcher
import org.iotsplab.akiba.managers.ProjectWorkerPool
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class DispatcherWorker : ProjectWorkerPool.WorkerProcess {
    @Volatile override var alive = true
    @Volatile override var exitCode: Int? = null
    override fun stop() {
        alive = false
    }
}

/**
 * The pooling contract end to end: two sessions, one project, one program, and a model
 * that has to be told when the other session changed the program under it.
 *
 * The model is [DummyLLMClient], so what is asserted is what the agent would really
 * receive — not a stub of our own invention.
 */
class ProjectTurnDispatcherTest {

    private val spawns = AtomicInteger()

    private fun dispatcher() = ProjectTurnDispatcher(
        workers = ProjectWorkerPool(spawn = { DispatcherWorker().also { spawns.incrementAndGet() } }),
        programs = ProgramScheduler(defaultTimeoutMs = 5000),
    )

    /** One agent turn: the note becomes a system message before the user's request. */
    private fun runTurn(
        dispatcher: ProjectTurnDispatcher,
        sessionId: String,
        observedNotes: MutableList<String?>,
        requests: MutableList<String>,
        gate: CountDownLatch? = null,
    ) {
        dispatcher.run("fx20", "program-1", sessionId) { turn ->
            observedNotes += turn.interleavingNote

            val dummy = DummyLLMClient(listOf(ChatCompletion(content = "analyzed $sessionId")))
            val messages = buildList {
                turn.interleavingNote?.let { add(AgentChatMessage(role = "system", content = it)) }
                add(AgentChatMessage(role = "user", content = "what changed?"))
            }
            val reply = dummy.chat(systemPrompt = null, messages = messages, tools = null)

            gate?.countDown()
            gate?.await(2, TimeUnit.SECONDS)
            synchronized(requests) {
                requests += dummy.requests.first().joinToString(" | ") { "${it.role}:${it.content}" }
            }
            assertEquals("analyzed $sessionId", reply.content)
        }
    }

    @Test
    fun `a second session is told what the first one did`() {
        val dispatcher = dispatcher()
        val notes = mutableListOf<String?>()
        val requests = mutableListOf<String>()

        runTurn(dispatcher, "session-a", notes, requests)
        runTurn(dispatcher, "session-b", notes, requests)

        assertNull(notes[0], "the first turn has nothing to report")
        val note = assertNotNull(notes[1], "the second turn is told about session-a")
        assertTrue(note.contains("session-a") || note.contains("session a"), note)

        val secondRequest = requests[1]
        assertTrue(secondRequest.contains(note), "the note reached the model: $secondRequest")
        assertEquals(1, spawns.get(), "one worker served both sessions")
    }

    @Test
    fun `a session that comes back is told about the session that went in between`() {
        val dispatcher = dispatcher()
        val notes = mutableListOf<String?>()
        val requests = mutableListOf<String>()

        runTurn(dispatcher, "session-a", notes, requests)
        runTurn(dispatcher, "session-b", notes, requests)
        runTurn(dispatcher, "session-a", notes, requests)

        assertNotNull(notes[2], "session-a learns about session-b's work")
        assertTrue(requests[2].contains("session-b"), requests[2])
    }

    @Test
    fun `two sessions never work the same program at once`() {
        val dispatcher = dispatcher()
        val notes = mutableListOf<String?>()
        val requests = mutableListOf<String>()
        val gate = CountDownLatch(1)
        val peak = AtomicInteger()
        val inside = AtomicInteger()

        val threads = listOf("session-a", "session-b").map { session ->
            thread {
                dispatcher.run("fx20", "program-1", session) { turn ->
                    val now = inside.incrementAndGet()
                    peak.updateAndGet { max -> if (now > max) now else max }
                    gate.countDown()
                    gate.await(2, TimeUnit.SECONDS)
                    inside.decrementAndGet()
                }
            }
        }
        threads.forEach { it.join(4000) }

        assertEquals(1, peak.get(), "one session inside the program at a time")
        assertEquals(1, spawns.get(), "and they still shared one worker")
        notes.clear(); requests.clear()
    }
}
