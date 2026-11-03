package org.iotsplab.akiba.llm.agent.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * The reporter is the single writer of `__llm_progress__` rows (it replaced a
 * 15 s heartbeat plus a 3 s chunk writer that both described the same
 * generation).  These assertions cover the counter bookkeeping the streaming
 * loop and the SSE publisher read from.
 */
class LlmProgressReporterTest {

    @Test
    fun `accumulates chunk and byte counters`() = runBlocking {
        val reporter = LlmProgressReporter(agentDbClient = null, sessionId = null).start()

        assertEquals(0, reporter.chunkCount)
        assertEquals(0, reporter.byteCount)

        reporter.onDelta("abc", "abc")
        reporter.onDelta("de", "abcde")

        assertEquals(2, reporter.chunkCount)
        assertEquals(5, reporter.byteCount)

        reporter.finish(succeeded = true)
    }

    @Test
    fun `starting without a session is a no-op but stays usable`() = runBlocking {
        val reporter = LlmProgressReporter(agentDbClient = null, sessionId = "s").start()
        reporter.onDelta("x", "x")
        assertEquals(1, reporter.chunkCount)
        reporter.finish(succeeded = false)
    }
}
