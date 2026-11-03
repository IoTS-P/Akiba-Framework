package org.iotsplab.akiba.llm.tool.core

import dev.langchain4j.model.chat.request.json.JsonArraySchema
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema
import dev.langchain4j.model.chat.request.json.JsonEnumSchema
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema
import dev.langchain4j.model.chat.request.json.JsonNumberSchema
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import dev.langchain4j.model.chat.request.json.JsonStringSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies the single-step Tool → langchain4j ToolSpecification conversion
 * (replacing the JSON serialise/parse round-trip) and its type fidelity.
 */
class ToolSpecificationTest {

    private fun tool() = Tool(
        name = "demo",
        description = "demo tool",
        parameters = listOf(
            ToolParameter("text", "string", "a string"),
            ToolParameter("count", "integer", "an integer"),
            ToolParameter("ratio", "number", "a float"),
            ToolParameter("flag", "boolean", "a bool"),
            ToolParameter("items", "array", "a list"),
            ToolParameter("blob", "object", "an object"),
            ToolParameter("mode", "string", "an enum", enum = listOf("a", "b")),
            ToolParameter("optional", "string", "not required", required = false),
        )
    ) { "ok" }

    @Test
    fun `parameter types map to their own schema element`() {
        val spec = tool().toToolSpecification()
        val params = assertNotNull(spec.parameters())

        assertEquals("demo", spec.name())
        assertEquals("demo tool", spec.description())

        assertTrue(params.properties()["text"] is JsonStringSchema)
        assertTrue(params.properties()["count"] is JsonIntegerSchema)
        assertTrue(params.properties()["ratio"] is JsonNumberSchema, "number must not degrade to integer")
        assertTrue(params.properties()["flag"] is JsonBooleanSchema)
        assertTrue(params.properties()["items"] is JsonArraySchema, "array must not degrade to string")
        assertTrue(params.properties()["blob"] is JsonObjectSchema)
        assertTrue(params.properties()["mode"] is JsonEnumSchema)
    }

    @Test
    fun `only required parameters are listed as required`() {
        val params = assertNotNull(tool().toToolSpecification().parameters())
        val required = params.required()
        assertTrue(required.contains("text"))
        assertTrue(!required.contains("optional"))
    }

    @Test
    fun `registry caches specs until a tool is registered`() {
        val registry = ToolRegistry()
        registry.register(tool())

        val first = registry.toToolSpecifications()
        val second = registry.toToolSpecifications()
        assertSame(first, second, "spec list should be cached")

        registry.register(Tool("other", "another") { "ok" })
        val after = registry.toToolSpecifications()
        assertEquals(2, after.size, "cache must be invalidated on register")
    }
}
