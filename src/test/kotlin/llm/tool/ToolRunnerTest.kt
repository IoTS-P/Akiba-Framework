package llm.tool

import org.iotsplab.akiba.llm.tool.core.Tool
import org.iotsplab.akiba.llm.tool.core.ToolRunner
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How long a tool call is allowed to run.
 *
 * The interesting part is not the happy path but the edges: a tool that asks for nothing, a
 * tool that asks for far too much, and a tool whose own answer is nonsense.
 */
class ToolRunnerTest {

    private fun tool(asked: ((Map<String, Any?>) -> Int)? = null) = Tool(
        name = "probe",
        description = "does nothing",
        timeoutSeconds = asked,
        execute = { "ok" },
    )

    @Test
    fun `a tool that asks for nothing gets the default`() {
        assertEquals(300, ToolRunner.limitSeconds(tool(), emptyMap()))
    }

    @Test
    fun `a tool may ask for its own time`() {
        assertEquals(900, ToolRunner.limitSeconds(tool { 900 }, emptyMap()))
    }

    @Test
    fun `the tool sees its own arguments`() {
        val t = tool { args -> (args["timeout"] as? Number)?.toInt() ?: 60 }
        assertEquals(600, ToolRunner.limitSeconds(t, mapOf("timeout" to 600)))
    }

    @Test
    fun `nothing may run longer than the ceiling`() {
        assertEquals(86_400, ToolRunner.limitSeconds(tool { 999_999 }, emptyMap()))
    }

    @Test
    fun `a nonsense answer falls back to the default`() {
        assertEquals(300, ToolRunner.limitSeconds(tool { 0 }, emptyMap()))
        assertEquals(300, ToolRunner.limitSeconds(tool { -5 }, emptyMap()))
        assertEquals(300, ToolRunner.limitSeconds(tool { throw IllegalStateException("no") }, emptyMap()))
    }

    @Test
    fun `a call that ignores the interrupt does not hold the agent`() = runBlocking {
        // Thread.sleep answers an interrupt by throwing; this one swallows it and sleeps
        // again, standing in for a tool stuck somewhere interrupt cannot reach. The agent
        // must still get its answer back at the limit.
        val stubborn = Tool(
            name = "stubborn",
            description = "does not stop",
            timeoutSeconds = { 2 },
            execute = {
                runCatching { Thread.sleep(30_000) }
                runCatching { Thread.sleep(30_000) }
                "finally"
            },
        )

        val startedAt = System.currentTimeMillis()
        val outcome = ToolRunner.run(stubborn, emptyMap(), null)
        val elapsed = System.currentTimeMillis() - startedAt

        assertTrue(elapsed < 6_000, "returned after ${elapsed}ms, the limit was 2s")
        assertTrue(outcome.output.contains("ran past its 2s limit"), outcome.output)
    }
}
