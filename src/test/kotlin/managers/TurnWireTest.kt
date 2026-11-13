package managers

import kotlinx.serialization.json.Json
import org.iotsplab.akiba.server.routes.TurnDto
import org.iotsplab.akiba.server.routes.TurnLlmDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The wire shape the worker parses.
 *
 * The worker builds its model client from these fields, so a field that silently stops
 * being encoded would leave it talking to the wrong provider with no error.
 */
class TurnWireTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the model survives the wire`() {
        val dto = TurnDto(
            id = "t-1",
            sessionId = "session-a",
            programId = "program-1",
            prompt = "hello",
            systemPrompt = "rules",
            username = "admin",
            programName = "libfoo.so",
            llm = TurnLlmDto("OPEN_AI", "gpt-5.5", "sk-secret", "https://example.test/v1"),
        )

        val decoded = json.decodeFromString<TurnDto>(json.encodeToString(dto))

        assertEquals("gpt-5.5", decoded.llm?.modelName)
        assertEquals("OPEN_AI", decoded.llm?.provider)
        assertEquals("sk-secret", decoded.llm?.apiKey)
        assertEquals("https://example.test/v1", decoded.llm?.baseUrl)
        assertEquals("libfoo.so", decoded.programName)
    }

    @Test
    fun `a turn without a model still parses`() {
        val decoded = json.decodeFromString<TurnDto>(
            json.encodeToString(TurnDto("t-2", "session-b", prompt = "hi")),
        )

        assertNull(decoded.llm)
        assertEquals("hi", decoded.prompt)
    }
}
