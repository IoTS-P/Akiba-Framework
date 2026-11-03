package org.iotsplab.akiba.llm.agent.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.iotsplab.akiba.llm.tool.core.ToolCallParser

/**
 * Keeps the strategy prompt and the tool-call parser in sync.
 *
 * The prompt teaches the model a JSON layout by example; if an example drifts
 * out of the format [ToolCallParser] accepts, the model follows the prompt and
 * its calls silently fail.  These assertions make that drift a test failure.
 */
class AgentPromptsExamplesTest {

    @Test
    fun `every runnable tool_call example in the strategy prompt parses`() {
        val instruction = AgentPrompts.REACT_INSTRUCTION

        // The first ```json block is the shape template with <placeholders>; the
        // rest are concrete examples.  Only concretely-parseable blocks count.
        val calls = ToolCallParser.parseAll(instruction)

        assertEquals(
            4,
            calls.size,
            "expected the four runnable examples to parse; got ${calls.map { it.name }}",
        )
        assertEquals(
            listOf("script_library", "script_library", "script_library", "query_ghidra_api"),
            calls.map { it.name },
        )
        assertTrue(calls.all { it.arguments.isNotEmpty() }, "every example should carry arguments")
    }

    @Test
    fun `the strategy prompt no longer demands a response template`() {
        val instruction = AgentPrompts.REACT_INSTRUCTION

        assertFalse(instruction.contains("Thought:", ignoreCase = false))
        assertFalse(instruction.contains("Final Answer:", ignoreCase = false))
        assertFalse(instruction.contains("Tool decision:"))
        assertFalse(instruction.contains("STOPPING RULE"))
        assertTrue(instruction.contains("tool_call"), "the tool_call contract must stay")
    }

    @Test
    fun `the shared rules no longer demand a response template`() {
        val rules = AgentPrompts.DEFAULT_AGENT_RULES

        assertFalse(rules.contains("Thought:", ignoreCase = false))
        assertFalse(rules.contains("Final Answer:", ignoreCase = false))
        assertTrue(rules.contains("tool_call"), "the tool_call contract must stay")
    }

    @Test
    fun `the format reminder describes actions, not markers`() {
        val reminder = AgentPrompts.formatReminder("a, b")

        assertFalse(reminder.contains("Final Answer:"))
        assertFalse(reminder.contains("Thought:"))
        assertTrue(reminder.contains("tool_call"))
        assertTrue(reminder.contains("a, b"))
    }
}
