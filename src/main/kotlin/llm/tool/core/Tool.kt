package org.iotsplab.akiba.llm.tool.core

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.json.JsonArraySchema
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema
import dev.langchain4j.model.chat.request.json.JsonEnumSchema
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema
import dev.langchain4j.model.chat.request.json.JsonNumberSchema
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import dev.langchain4j.model.chat.request.json.JsonSchemaElement
import dev.langchain4j.model.chat.request.json.JsonStringSchema
import org.iotsplab.akiba.utils.AkibaJson

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
 * Deduplication strategy for a tool's results.
 *
 * Controls how [DefaultToolResultDuplicateDetector] decides whether a call
 * repeats evidence already seen in the session:
 * - [RESULT_HASH]: SHA-256 of the OUTPUT (default), for large distinctive
 *   output such as disassembly; short successful results are exempted.
 * - [ARGS_ONLY]: canonical ARGUMENTS only, for tools where a repeat call is
 *   always wasteful; no short-result exemption.
 */
enum class ToolDedupStrategy {
    RESULT_HASH,
    ARGS_ONLY,
}

/**
 * A tool that an agent can invoke during a ReAct loop.
 *
 * Tools are the primary mechanism by which an LLM agent interacts with the
 * outside world (file system, database, Ghidra API, etc.).  Each tool carries a
 * unique [name], a [description] the LLM uses to decide when to call it, a
 * [parameters] schema, and an [execute] body that performs the work.
 */
class Tool(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter> = emptyList(),
    /**
     * Strategy used by [DefaultToolResultDuplicateDetector] to detect
     * repeated tool calls.  Defaults to [ToolDedupStrategy.RESULT_HASH]
     * (compare output SHA-256).  Set to [ToolDedupStrategy.ARGS_ONLY]
     * for tools where calling with the same arguments is always
     * wasteful regardless of output (e.g. state-recording tools like
     * `vuln_memory`).
     */
    val dedupStrategy: ToolDedupStrategy = ToolDedupStrategy.RESULT_HASH,
    /**
     * Optional dynamic resolver that overrides [dedupStrategy] on a
     * per-call basis.  When non-null, the strategy is invoked with the
     * tool's arguments at call time and the returned value takes
     * precedence over the static [dedupStrategy].
     *
     * Use this for **dispatch-style tools** (e.g. `script_library`)
     * where different sub-commands need different dedup behavior:
     * read-only scripts work well with [ToolDedupStrategy.RESULT_HASH]
     * while state-changing scripts should use
     * [ToolDedupStrategy.ARGS_ONLY] to catch redundant writes.
     */
    val dedupStrategyResolver: ((Map<String, Any?>) -> ToolDedupStrategy)? = null,
    val execute: (Map<String, Any?>) -> String
) {
    private val mapper = AkibaJson.mapper

    /**
     * Generate a JSON Schema description of this tool for LLM tool-calling.
     *
     * The output follows the OpenAI function-calling format, which is
     * widely supported by OpenAI-compatible providers.
     *
     * NOTE: this is the *wire* form, not what the adapters send.  The
     * providers receive [toToolSpecification]; this string form is kept
     * for diagnostics and for callers that need the raw JSON.
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
     * Convert this tool into the langchain4j [ToolSpecification] the model
     * adapters actually send to the provider.
     *
     * Building the spec directly (instead of serialising [toJsonSchema] to a
     * string and re-parsing it in the adapter) removes a full JSON
     * round-trip per tool per LLM call, and keeps the parameter-type mapping
     * faithful: `integer`, `number`, `boolean`, `array`, `object` and enums
     * each map to their own schema element.  The previous string-based
     * conversion advertised `number` as an integer and silently degraded
     * array/object parameters to strings.
     */
    fun toToolSpecification(): ToolSpecification {
        val properties = JsonObjectSchema.builder()
        val required = mutableListOf<String>()

        for (param in parameters) {
            properties.addProperty(param.name, toSchemaElement(param))
            if (param.required) required.add(param.name)
        }
        if (required.isNotEmpty()) properties.required(required)

        return ToolSpecification.builder()
            .name(name)
            .description(description)
            .parameters(properties.build())
            .build()
    }

    private fun toSchemaElement(param: ToolParameter): JsonSchemaElement {
        val values = param.enum
        if (!values.isNullOrEmpty()) {
            return JsonEnumSchema.builder()
                .description(param.description)
                .enumValues(values)
                .build()
        }
        return when (param.type.lowercase()) {
            "integer" -> JsonIntegerSchema.builder().description(param.description).build()
            "number" -> JsonNumberSchema.builder().description(param.description).build()
            "boolean" -> JsonBooleanSchema.builder().description(param.description).build()
            "array" -> JsonArraySchema.builder()
                .description(param.description)
                .items(JsonStringSchema.builder().build())
                .build()
            "object" -> JsonObjectSchema.builder().description(param.description).build()
            else -> JsonStringSchema.builder().description(param.description).build()
        }
    }

    /**
     * Safely execute this tool, catching any exceptions and returning
     * an error message instead of propagating the exception.
     */
    fun safeExecute(args: Map<String, Any?>): String {
        validateArguments(args)?.let { return it }
        return try {
            execute(args)
        } catch (e: Exception) {
            "Tool '$name' execution error: ${e.message}"
        }
    }

    private fun validateArguments(args: Map<String, Any?>): String? {
        val schemaNames = parameters.map { it.name }.toSet()
        val provided = args.keys.sorted()

        val missing = parameters
            .filter { it.required && (!args.containsKey(it.name) || args[it.name] == null) }
            .map { it.name }
        if (missing.isNotEmpty()) {
            return buildString {
                append("Tool argument error for '$name': missing required parameter(s): ")
                append(missing.joinToString(", ") { "'$it'" })
                append(". Provided argument keys: ")
                append(if (provided.isEmpty()) "<none>" else provided.joinToString(", ") { "'$it'" })
                append(". Use the exact parameter names from this tool's schema.")
            }
        }

        for (param in parameters) {
            val value = args[param.name]
            val enum = param.enum
            if (enum != null && value is String && enum.none { it.equals(value, ignoreCase = false) }) {
                return "Tool argument error for '$name': parameter '${param.name}' has invalid value '$value'. " +
                    "Allowed values: ${enum.joinToString(", ")}."
            }
        }

        val unknown = provided.filter { it !in schemaNames }
        if (unknown.isNotEmpty()) {
            val hints = unknown.mapNotNull { key ->
                when {
                    key == "scriptArgs" && "parameters" in schemaNames ->
                        "'scriptArgs' is the internal variable name inside library scripts; use tool parameter 'parameters' instead."
                    key == "params" && "parameters" in schemaNames ->
                        "Use 'parameters' instead of 'params'."
                    key == "args" && "arguments" in schemaNames ->
                        "Use 'arguments' instead of 'args'."
                    else -> null
                }
            }
            if (hints.isNotEmpty()) {
                return "Tool argument error for '$name': unexpected parameter(s): " +
                    unknown.joinToString(", ") { "'$it'" } + ". " + hints.joinToString(" ")
            }
        }
        return null
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

    /**
     * Cached provider-facing tool specs.
     *
     * The strategy asks for the full tool list on **every** LLM call, so
     * rebuilding one [ToolSpecification] per registered tool per iteration was
     * pure waste.  The cache is invalidated on [register]; a benign race can
     * at worst build the list twice.
     */
    @Volatile
    private var cachedSpecs: List<ToolSpecification>? = null

    /** Register a tool. Overwrites any existing tool with the same name. */
    fun register(tool: Tool) {
        tools[tool.name] = tool
        cachedSpecs = null
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

    /**
     * langchain4j tool specifications for every registered tool, in
     * registration order.  Cached until the next [register].
     */
    fun toToolSpecifications(): List<ToolSpecification> =
        cachedSpecs ?: tools.values.map { it.toToolSpecification() }.also { cachedSpecs = it }

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
