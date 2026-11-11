package org.iotsplab.akiba.llm.agent.stream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.iotsplab.akiba.llm.tool.core.ToolCallParser

/**
 * Reproduces the streaming regression as a scripted sequence: two consecutive
 * LLM generations (an iteration boundary in between) are published into
 * [StreamChunkBus], and a poller that mirrors `Agent.vue`'s `since`/generation
 * logic drains them one poll at a time.
 *
 * The checks are the ones the UI needs: every delta appears exactly once and in
 * order, the terminal chunk never erases the delta it shares its chunkCount
 * with, and `done` is observed once per generation.
 */
class StreamChunkDeliveryTest {

    /** Poller twin of `pollStreamChunksOnce()` in `views/Agent.vue`. */
    private class Poller(private val sessionId: String) {
        var cursor = 0L
        var generation = -1
        var finishedGeneration = -1
        val text = StringBuilder()
        var doneCount = 0
        var lastGenerationText = ""

        fun poll(): Boolean {
            val snap = StreamChunkBus.historySince(sessionId, cursor) ?: return false
            if (generation != -1 && snap.generation != generation) {
                // New generation: discard the text, KEEP the cursor (the bus
                // seq never restarts, so nothing of the new generation can be
                // filtered out).
                text.clear()
            }
            generation = snap.generation
            for (chunk in snap.chunks) {
                if (chunk.seq > cursor) cursor = chunk.seq
                if (chunk.delta.isNotEmpty()) text.append(chunk.delta)
            }
            if (snap.done && snap.generation != finishedGeneration) {
                finishedGeneration = snap.generation
                lastGenerationText = text.toString()
                doneCount++
                return true
            }
            return false
        }
    }

    /** Publish one generation the way `LlmTransport.invokeChatStreaming` does. */
    private fun publishGeneration(
        sessionId: String,
        deltas: List<String>,
        poller: Poller,
        pollEvery: Int,
        failWith: String? = null,
        explicitStart: Boolean = true,
    ) {
        if (explicitStart) {
            // The transport opens the generation before any text arrives.
            StreamChunkBus.publish(
                StreamChunkBus.Chunk(sessionId, delta = "", chunkCount = 0, byteCount = 0, newGeneration = true)
            )
        }
        var bytes = 0
        deltas.forEachIndexed { index, delta ->
            bytes += delta.length
            StreamChunkBus.publish(
                StreamChunkBus.Chunk(
                    sessionId = sessionId,
                    delta = delta,
                    chunkCount = index + 1,
                    byteCount = bytes,
                )
            )
            if ((index + 1) % pollEvery == 0) poller.poll()
        }
        // The terminal chunk reuses the LAST delta's chunkCount (that is what
        // the transport sends) and carries an empty delta.
        StreamChunkBus.publish(
            StreamChunkBus.Chunk(
                sessionId = sessionId,
                delta = "",
                chunkCount = deltas.size,
                byteCount = bytes,
                done = true,
                error = failWith,
            )
        )
    }

    @Test
    fun `two generations deliver every delta exactly once with done for each`() {
        val sessionId = "test-delivery-two-generations"
        val first = (1..42).map { "tok$it " }
        val second = listOf("Let me take a look at the currently loaded binary.")
        val poller = Poller(sessionId)

        publishGeneration(sessionId, first, poller, pollEvery = 5)
        assertTrue(poller.poll(), "the first generation's done must resolve the bubble")
        assertEquals(1, poller.doneCount)
        assertEquals(first.joinToString(""), poller.lastGenerationText)

        // Iteration boundary: the next LLM call restarts chunkCount at 1 while
        // the browser still holds the previous generation's cursor.
        val cursorBefore = poller.cursor
        assertTrue(cursorBefore >= 1, "poller must hold a cursor from generation 1")
        publishGeneration(sessionId, second, poller, pollEvery = 1)
        assertTrue(poller.poll(), "the second generation's done must resolve the bubble")

        assertEquals(2, poller.doneCount, "done must be observed exactly once per generation")
        assertEquals(second.joinToString(""), poller.lastGenerationText)
        println(
            "generations=2 deltas=${first.size + second.size} " +
                "cursorBeforeGen2=$cursorBefore cursorAfter=${poller.cursor} " +
                "gen2Text=${poller.lastGenerationText.length} chars doneCount=${poller.doneCount}"
        )
    }

    @Test
    fun `terminal chunk does not erase the delta it shares its chunkCount with`() {
        val sessionId = "test-terminal-collision"
        val poller = Poller(sessionId)
        val deltas = listOf("Let me take a look at the ", "currently loaded ", "binary.")
        publishGeneration(sessionId, deltas, poller, pollEvery = deltas.size, explicitStart = false)
        assertTrue(poller.poll())
        // The old bus replaced the entry holding chunkCount 3 with the terminal
        // chunk (delta ""), which dropped "binary." from the visible message.
        assertEquals("Let me take a look at the currently loaded binary.", poller.lastGenerationText)
        println("terminalCollision text=\"${poller.lastGenerationText}\"")
    }

    @Test
    fun `a producer that only restarts its counter still opens a generation`() {
        val sessionId = "test-legacy-restart"
        val poller = Poller(sessionId)
        publishGeneration(sessionId, listOf("first answer"), poller, pollEvery = 1, explicitStart = false)
        assertTrue(poller.poll())
        // One-delta generation: its `done` carries chunkCount 1 as well and
        // must not wipe the only delta (nor bump the generation again).
        publishGeneration(sessionId, listOf("second answer"), poller, pollEvery = 1, explicitStart = false)
        assertTrue(poller.poll())
        assertEquals("second answer", poller.lastGenerationText)
        assertEquals(2, poller.doneCount)
        println("legacyRestart text=\"${poller.lastGenerationText}\" done=${poller.doneCount}")
    }

    @Test
    fun `a retried push never duplicates a delta`() {
        val sessionId = "test-duplicate-retry"
        val poller = Poller(sessionId)
        StreamChunkBus.publish(StreamChunkBus.Chunk(sessionId, "alpha", 1, 5))
        StreamChunkBus.publish(StreamChunkBus.Chunk(sessionId, "beta", 2, 9))
        // Same payload delivered twice by the sender's retry.
        StreamChunkBus.publish(StreamChunkBus.Chunk(sessionId, "beta", 2, 9))
        StreamChunkBus.publish(StreamChunkBus.Chunk(sessionId, "", 2, 9, done = true))
        assertTrue(poller.poll())
        assertEquals("alphabeta", poller.lastGenerationText)
        val snap = assertNotNull(StreamChunkBus.historySince(sessionId, 0))
        assertEquals(3, snap.chunks.size, "2 deltas + 1 terminal chunk, duplicate dropped")
        println("dedupe chunks=${snap.chunks.size} text=\"${poller.lastGenerationText}\"")
    }

    @Test
    fun `an interrupted generation ends the bubble with done and an error`() {
        val sessionId = "test-interrupted"
        val poller = Poller(sessionId)
        publishGeneration(
            sessionId,
            listOf("partial ", "answer"),
            poller,
            pollEvery = 1,
            failWith = "streaming chat stalled",
        )
        assertTrue(poller.poll(), "an interrupted generation must still deliver done")
        val snap = assertNotNull(StreamChunkBus.historySince(sessionId, 0))
        assertEquals("streaming chat stalled", snap.error)
        assertTrue(snap.done)
        println("interrupted text=\"${poller.lastGenerationText}\" error=${snap.error}")
    }

    @Test
    fun `a cancelled generation resolves through finish`() {
        val sessionId = "test-cancelled"
        val poller = Poller(sessionId)
        StreamChunkBus.publish(StreamChunkBus.Chunk(sessionId, "half an answer", 1, 14))
        poller.poll()
        StreamChunkBus.finish(sessionId, "cancelled by user")
        assertTrue(poller.poll(), "finish() must resolve a generation that will never post done")
        assertEquals(1, poller.doneCount)
        println("cancelled text=\"${poller.lastGenerationText}\" done=${poller.doneCount}")
    }
}

/**
 * The tool call from the reproduced turn, streamed as text.  It must parse into
 * exactly the call the model meant, and an ordinary JSON answer must not.
 */
class StreamedTextToolCallTest {

    private val streamedReply = """
        Let me take a look at the currently loaded binary.

        ```json
        {"tool_call":{"name":"script_library","arguments":{"action":"run","scriptName":"binary_info","parameters":{}}}}
        ```
    """.trimIndent()

    private val registryNames = setOf("script_library", "run_script", "query_ghidra_api")

    @Test
    fun `a streamed tool_call block parses into exactly one call`() {
        val calls = ToolCallParser.parseExplicitTextCalls(streamedReply, registryNames)
        assertEquals(1, calls.size)
        assertEquals("script_library", calls.single().name)
        val args = calls.single().arguments
        assertEquals("run", args["action"])
        assertEquals("binary_info", args["scriptName"])
        assertTrue(ToolCallParser.hasExplicitTextCall(streamedReply))
        println("parsed call=${calls.single().name} args=${calls.single().argumentsJson}")
    }

    @Test
    fun `a JSON answer is not executed even when it names a known tool`() {
        val answer = """
            Found 2 suspicious functions:
            {"suspiciousFunctions":[{"name":"script_library","reason":"calls system"}]}
        """.trimIndent()
        val calls = ToolCallParser.parseExplicitTextCalls(answer, registryNames)
        assertTrue(calls.isEmpty(), "an answer must never be executed as a call: $calls")
    }

    @Test
    fun `prose carrying a flat name and arguments object stays an answer`() {
        // The shape pinned by AgentCompletionRuleTest: no fence, no wrapper.
        val answer = """Here is the summary: {"name": "script_library", "arguments": {"action": "run"}}"""
        val calls = ToolCallParser.parseExplicitTextCalls(answer, registryNames)
        assertTrue(calls.isEmpty(), "prose JSON must not execute: $calls")
        assertTrue(!ToolCallParser.hasExplicitTextCall(answer))
    }

    @Test
    fun `a tool_call block naming an unregistered tool is not executed`() {
        val reply = """{"tool_call":{"name":"rm_rf","arguments":{"path":"/"}}}"""
        val calls = ToolCallParser.parseExplicitTextCalls(reply, registryNames)
        assertTrue(calls.isEmpty(), "unregistered tool must not run: $calls")
        assertTrue(ToolCallParser.hasExplicitTextCall(reply))
    }

    @Test
    fun `a tool event rides the stream without starting a generation`() {
        val sessionId = "session-tool-event"
        val payload = """{"id":"script_library:1","name":"script_library","args":"{}"}"""
        StreamChunkBus.publish(
            StreamChunkBus.Chunk(sessionId, delta = "", chunkCount = 0, byteCount = 0, newGeneration = true)
        )
        StreamChunkBus.publish(
            StreamChunkBus.Chunk(sessionId, delta = "hello", chunkCount = 1, byteCount = 5)
        )
        // A tool starts in the middle of the answer: the event must not wipe
        // the text already streamed, and must not be read as answer text.
        StreamChunkBus.publish(
            StreamChunkBus.Chunk(
                sessionId = sessionId,
                delta = payload,
                chunkCount = 0,
                byteCount = 0,
                kind = StreamChunkBus.KIND_TOOL_START,
            )
        )
        StreamChunkBus.publish(
            StreamChunkBus.Chunk(sessionId, delta = " world", chunkCount = 2, byteCount = 11)
        )
        val snapshot = StreamChunkBus.historySince(sessionId, since = 0)!!
        val textChunks = snapshot.chunks.filter { it.kind == StreamChunkBus.KIND_TEXT }
        assertEquals("hello world", textChunks.joinToString("") { it.delta })
        assertEquals(1, snapshot.chunks.count { it.kind == StreamChunkBus.KIND_TOOL_START })
        assertEquals(1, snapshot.generation)
    }
}
