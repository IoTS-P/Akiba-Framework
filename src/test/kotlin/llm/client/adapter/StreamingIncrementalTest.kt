package org.iotsplab.akiba.llm.client.adapter

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.iotsplab.akiba.llm.client.ChatChunk
import org.iotsplab.akiba.llm.client.LLMConfig
import org.iotsplab.akiba.llm.client.LLMProvider
import org.iotsplab.akiba.llm.memory.AgentChatMessage

/**
 * Proves the streaming path is genuinely incremental.
 *
 * A fake OpenAI-compatible endpoint emits one SSE chunk every [CHUNK_GAP_MS] ms.
 * If the adapter (or langchain4j) buffered the response, every chunk would
 * arrive at the same instant at the end; instead the arrival spread must
 * roughly match the server's own pacing.  This is also what makes the 30 s
 * "no chunk" stall watchdog meaningful.
 */
class StreamingIncrementalTest {

    private companion object {
        const val CHUNK_COUNT = 5
        const val CHUNK_GAP_MS = 300L
    }

    private lateinit var server: HttpServer
    private var port: Int = 0
    private val requestCount = AtomicInteger(0)

    @BeforeTest
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            requestCount.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.responseHeaders.add("Cache-Control", "no-cache")
            exchange.sendResponseHeaders(200, 0) // chunked

            exchange.responseBody.use { body ->
                for (i in 0 until CHUNK_COUNT) {
                    val json = """{"id":"chatcmpl-test","object":"chat.completion.chunk",""" +
                        """"created":1,"model":"test-model","choices":[{"index":0,""" +
                        """"delta":{"content":"tok$i"},"finish_reason":null}]}"""
                    body.write("data: $json\n\n".toByteArray())
                    body.flush()
                    Thread.sleep(CHUNK_GAP_MS)
                }
                val final = """{"id":"chatcmpl-test","object":"chat.completion.chunk",""" +
                    """"created":1,"model":"test-model","choices":[{"index":0,""" +
                    """"delta":{},"finish_reason":"stop"}],""" +
                    """"usage":{"prompt_tokens":3,"completion_tokens":5,"total_tokens":8}}"""
                body.write("data: $final\n\n".toByteArray())
                body.write("data: [DONE]\n\n".toByteArray())
                body.flush()
            }
        }
        server.start()
        port = server.address.port
    }

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    private fun adapter() = OpenAiFamilyAdapter(
        LLMConfig(
            provider = LLMProvider.DEEP_SEEK,
            modelName = "test-model",
            apiKey = "test-key",
            baseUrl = "http://127.0.0.1:$port",
            timeoutSeconds = 30,
        )
    )

    @Test
    fun `chunks arrive as the server emits them, not buffered at the end`() = runBlocking {
        val arrivals = mutableListOf<Long>()
        val deltas = StringBuilder()
        val adapter = adapter()

        try {
            val started = System.nanoTime()
            var terminal: ChatChunk? = null
            adapter.chatStream(
                systemPrompt = "system",
                messages = listOf(AgentChatMessage(role = "user", content = "hi")),
            ).collect { chunk ->
                arrivals += System.nanoTime() - started
                if (chunk.isComplete) terminal = chunk else deltas.append(chunk.delta)
            }

            assertEquals(CHUNK_COUNT + 1, arrivals.size, "expected $CHUNK_COUNT deltas + terminal chunk")
            assertEquals("tok0tok1tok2tok3tok4", deltas.toString())

            val spreadMs = (arrivals.last() - arrivals.first()) / 1_000_000
            val expectedSpread = CHUNK_GAP_MS * (CHUNK_COUNT - 1)
            println(
                "chunk arrivals (ms since start): " + arrivals.map { it / 1_000_000 } +
                    " -> spread ${spreadMs}ms, server pacing ${expectedSpread}ms"
            )
            assertTrue(
                spreadMs >= expectedSpread * 0.8,
                "chunks were buffered: first-to-last spread was ${spreadMs}ms, " +
                    "expected >= ${(expectedSpread * 0.8).toLong()}ms",
            )

            val done = terminal ?: error("no terminal chunk")
            assertEquals("stop", done.finishReason)
            assertEquals(8, done.tokenUsage?.totalTokenCount)
        } finally {
            adapter.close()
        }
    }

    @Test
    fun `one-shot chat uses the same endpoint without streaming`() {
        // The fake server only speaks SSE, so a non-streaming call must fail —
        // this asserts chat() and chatStream() really are different code paths.
        val adapter = adapter()
        try {
            val failure = runCatching { adapter.chat(null, listOf(AgentChatMessage("user", "hi"))) }
            assertTrue(failure.isFailure, "non-streaming chat should not parse an SSE body")
        } finally {
            adapter.close()
        }
    }
}
