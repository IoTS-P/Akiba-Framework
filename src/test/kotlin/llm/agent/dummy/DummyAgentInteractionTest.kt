package org.iotsplab.akiba.llm.agent.dummy

import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.iotsplab.akiba.llm.agent.builder.AgentBuilder
import org.iotsplab.akiba.llm.client.ChatCompletion
import org.iotsplab.akiba.llm.client.NativeToolCall
import org.iotsplab.akiba.llm.tool.core.Tool
import org.iotsplab.akiba.llm.tool.core.ToolParameter
import org.iotsplab.akiba.managers.ProjectBusyException
import org.iotsplab.akiba.managers.ProjectLockOwner
import org.iotsplab.akiba.server.routes.errorPayload

/**
 * Drives the real agent loop against [DummyLLMClient].
 *
 * The point is to pin the interaction itself — that a scripted tool call is
 * executed, that its result is sent back to the model, and that the second
 * scripted reply becomes the answer — without a provider in the loop.
 */
class DummyAgentInteractionTest {

    private fun echoTool(counter: MutableList<Map<String, Any?>>) = Tool(
        name = "echo",
        description = "Echo the given value back",
        parameters = listOf(ToolParameter("value", "string", "value to echo", required = true)),
        execute = { args ->
            counter += args
            "echoed:${args["value"]}"
        },
    )

    @Test
    fun `a scripted tool call runs and the second reply becomes the answer`() = runBlocking {
        val seenArgs = mutableListOf<Map<String, Any?>>()
        val client = DummyLLMClient(
            listOf(
                ChatCompletion(
                    content = "",
                    finishReason = "tool_calls",
                    toolCalls = listOf(NativeToolCall("call-1", "echo", """{"value":"ping"}""")),
                ),
                ChatCompletion(content = "The tool said: echoed:ping", finishReason = "stop"),
            ),
        )
        val agent = AgentBuilder().apply {
            client(client)
            tools(echoTool(seenArgs))
            maxIterations(5)
        }.build()

        val result = agent.run("echo ping please")

        assertEquals(1, seenArgs.size, "the scripted tool call must run exactly once")
        assertEquals("ping", seenArgs.single()["value"])
        assertEquals("The tool said: echoed:ping", result.output)
        assertEquals(2, client.callCount, "one call for the tool decision, one for the answer")
        val secondRequest = client.requests[1]
        assertTrue(
            secondRequest.any { it.content.contains("echoed:ping") },
            "the tool result must be handed back to the model: $secondRequest",
        )
        agent.close()
    }

    @Test
    fun `a busy project answers 409 and never claims a configuration fault`() {
        val owner = ProjectLockOwner(
            pid = 4242,
            sessionId = "session-holder",
            instance = "default_instance",
            acquiredAt = "2026-11-11T03:28:31Z",
        )
        val busy = ProjectBusyException("test_project", owner)

        // Handlers that translate IllegalStateException into "not configured"
        // swallowed this and reported a lock as a broken configuration.
        assertFalse(busy is IllegalStateException, "the busy signal must not look like a state error")

        val (status, body) = errorPayload(busy)
        val text = body["error"].orEmpty()
        assertEquals(HttpStatusCode.Conflict, status)
        assertTrue(text.contains("test_project"), "the project must be named: $text")
        assertTrue(text.contains("session-holder"), "the holder must be named: $text")
        assertFalse(
            text.contains("not configured", ignoreCase = true),
            "a lock is not a configuration problem: $text",
        )
    }
}
