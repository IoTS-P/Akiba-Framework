package org.iotsplab.akiba.llm.agent.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.iotsplab.akiba.llm.agent.module.tool

/**
 * The top-level `tool(...)` entry point and `akibaAgent { tools { tool(...) } }`
 * now share one builder, so both must behave the same — in particular, a tool
 * without `execute` fails at definition time instead of silently returning an
 * error string when the model calls it.
 */
class ToolDslTest {

    @Test
    fun `a tool without execute fails at definition time`() {
        val failure = assertFailsWith<IllegalStateException> {
            tool("no_exec") {
                description = "missing executor"
            }
        }
        assertTrue(failure.message!!.contains("no_exec"), "message should name the tool")
    }

    @Test
    fun `a well-formed definition keeps name description parameters and executor`() {
        val defined = tool("demo") {
            description = "demo tool"
            parameter("p", "string", "a parameter")
            execute { args -> "ok:" + args["p"] }
        }

        assertEquals("demo", defined.name)
        assertEquals("demo tool", defined.description)
        assertEquals(1, defined.parameters.size)
        assertEquals("p", defined.parameters.first().name)
        assertEquals("ok:x", defined.execute(mapOf("p" to "x")))
    }
}
