package org.iotsplab.akiba.llm.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

// ============================================================
//  Tool — definition
// ============================================================

/**
 * Describes a single parameter accepted by a [Tool].
 */
data class ToolParameter(
    /** Parameter name. */
    val name: String,
    /** JSON Schema type (e.g. "string", "integer", "boolean", "object", "array"). */
    val type: String = "string",
    /** Human-readable description of what this parameter does. */
    val description: String = "",
    /** Whether this parameter is required. */
    val required: Boolean = true,
    /** Enum values, if this parameter is constrained to a fixed set. */
    val enum: List<String>? = null
)

/**
 * A tool that an agent can invoke during a ReAct loop.
 *
 * Tools are the primary mechanism by which an LLM agent interacts with
 * the outside world (file system, database, Ghidra API, etc.).
 *
 * Each tool has:
 * - A unique [name] that the LLM uses to reference it
 * - A [description] that helps the LLM decide when to use it
 * - A list of [parameters] describing the expected input schema
 * - An [execute] function that performs the actual work
 *
 * Example:
 * ```kotlin
 * val searchTool = Tool(
 *     name = "search_functions",
 *     description = "Search for functions matching a regex pattern",
 *     parameters = listOf(
 *         ToolParameter("pattern", "string", "Regex pattern to match"),
 *     )
 * ) { args ->
 *     val pattern = args["pattern"] as String
 *     // ... search logic ...
 *     "Found 3 functions: main, foo, bar"
 * }
 * ```
 */
class Tool(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter> = emptyList(),
    val execute: (Map<String, Any?>) -> String
) {
    private val mapper = jacksonObjectMapper()

    /**
     * Generate a JSON Schema description of this tool for LLM tool-calling.
     *
     * The output follows the OpenAI function-calling format, which is
     * widely supported by OpenAI-compatible providers.
     */
    fun toJsonSchema(): String {
        val properties = mutableMapOf<String, Any>()
        val required = mutableListOf<String>()

        for (param in parameters) {
            val prop = mutableMapOf<String, Any>(
                "type" to param.type,
                "description" to param.description
            )
            param.enum?.let { prop["enum"] = it }
            properties[param.name] = prop
            if (param.required) required.add(param.name)
        }

        val schema = mapOf(
            "type" to "object",
            "properties" to properties,
            "required" to required
        )

        val functionDef = mapOf(
            "name" to name,
            "description" to description,
            "parameters" to schema
        )

        return mapper.writeValueAsString(mapOf("type" to "function", "function" to functionDef))
    }

    /**
     * Safely execute this tool, catching any exceptions and returning
     * an error message instead of propagating the exception.
     */
    fun safeExecute(args: Map<String, Any?>): String {
        return try {
            execute(args)
        } catch (e: Exception) {
            "Tool '$name' execution error: ${e.message}"
        }
    }
}

// ============================================================
//  ToolRegistry
// ============================================================

/**
 * Registry of tools available to an agent.
 *
 * Tools can be registered by name and later looked up by the agent
 * when the LLM decides to call a tool.
 */
class ToolRegistry {
    private val tools: MutableMap<String, Tool> = linkedMapOf()

    /** Register a tool. Overwrites any existing tool with the same name. */
    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    /** Register multiple tools. */
    fun registerAll(vararg toolList: Tool) {
        toolList.forEach { register(it) }
    }

    /** Register multiple tools from a collection. */
    fun registerAll(tools: Collection<Tool>) {
        tools.forEach { register(it) }
    }

    /** Look up a tool by name. */
    fun get(name: String): Tool? = tools[name]

    /** All registered tools. */
    fun all(): Collection<Tool> = tools.values

    /** Tool names. */
    fun names(): Set<String> = tools.keys.toSet()

    /** Generate JSON Schema array for all registered tools. */
    fun toJsonSchemas(): List<String> = tools.values.map { it.toJsonSchema() }

    /** Number of registered tools. */
    fun size(): Int = tools.size

    /** Whether any tools are registered. */
    fun isEmpty(): Boolean = tools.isEmpty()

    companion object {
        /** Create a [ToolRegistry] from a list of tools. */
        fun of(vararg toolList: Tool): ToolRegistry = ToolRegistry().apply {
            registerAll(*toolList)
        }
    }
}
