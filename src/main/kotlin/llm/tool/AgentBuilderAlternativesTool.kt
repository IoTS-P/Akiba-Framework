package org.iotsplab.akiba.llm.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.iotsplab.akiba.llm.agent.AgentBuilderComponents
import org.iotsplab.akiba.llm.agent.AgentModule
import org.iotsplab.akiba.llm.agent.AgentTemplateRegistry

// ============================================================
//  AgentBuilderAlternativesTool — catalogue of controllable
//  "building blocks" for constructing child agents
// ============================================================
//
// Read-only tool that exposes the components / templates currently
// visible to the caller.  The actual catalogue data lives in
// [AgentBuilderComponents] (single source of truth) and
// [AgentTemplateRegistry]; this tool only formats and serialises it.
//
// The tool is read-only: it never mutates the registry or the agent.
// The parent agent must use a separate spawn tool
// (e.g. `spawn_sub_agent` with a templateId) to actually create a child.
// ============================================================

/**
 * Create the `agent_builder_alternatives` tool.
 *
 * @param parent the parent [AgentModule] (used to read its tool registry
 *               and to resolve the scope id).
 */
fun AgentBuilderAlternativesTool(parent: AgentModule): Tool = Tool(
    name = "agent_builder_alternatives",
    description = buildString {
        appendLine("List the agent construction components and templates that are currently available.")
        appendLine("Returns the controllable 'building blocks' (strategies, harnesses, tools) and")
        appendLine("pre-built agent templates that you can reference when creating child agents.")
        appendLine("Templates are restricted to the current workflow scope and must be referenced by id.")
        appendLine("This tool is read-only: it does NOT create any agent. Use a dedicated spawn tool")
        appendLine("(e.g. spawn_sub_agent with a templateId) to actually create a child agent.")
    },
    parameters = listOf(
        ToolParameter(
            "intent", "string",
            "Optional keyword to filter templates and tool descriptions by intent / role. " +
                "Matched against template id, name, description, and tool name.",
            required = false
        ),
        ToolParameter(
            "include", "string",
            "Comma-separated filter of sections to include: 'strategies' | 'harnesses' | " +
                "'tools' | 'templates' | 'budgets'. Default: all sections.",
            required = false
        ),
        ToolParameter(
            "detail", "string",
            "'summary' (default) for compact descriptors, 'full' to include enum / default " +
                "fields in inputSchema.",
            required = false
        )
    )
) { args ->
    val mapper = jacksonObjectMapper()
    val intent = (args["intent"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
    val includeRaw = (args["include"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
    val detail = (args["detail"] as? String)?.trim()?.lowercase() ?: "summary"
    val includeFull = detail == "full"

    val includeSections = parseInclude(includeRaw)
    val scopeId = parent.agentSessionId ?: ""

    val out = LinkedHashMap<String, Any?>()

    if ("strategies" in includeSections) {
        out["strategies"] = describeStrategies()
    }
    if ("harnesses" in includeSections) {
        out["harnesses"] = describeHarnesses()
    }
    if ("tools" in includeSections) {
        out["tools"] = describeTools(parent, intent)
    }
    if ("templates" in includeSections) {
        out["templates"] = AgentTemplateRegistry.describeScope(
            scopeId = scopeId,
            includeFullInputs = includeFull,
            intent = intent
        )
    }
    if ("budgets" in includeSections) {
        out["budgets"] = describeBudgets()
    }

    out["scopeId"] = scopeId

    try {
        mapper.writeValueAsString(out)
    } catch (e: Exception) {
        "Error serialising agent_builder_alternatives: ${e.message}"
    }
}

private fun parseInclude(raw: String?): Set<String> {
    if (raw.isNullOrBlank()) {
        return setOf("strategies", "harnesses", "tools", "templates", "budgets")
    }
    val tokens = raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    val valid = setOf("strategies", "harnesses", "tools", "templates", "budgets")
    val unknown = tokens - valid
    require(unknown.isEmpty()) {
        "Unknown 'include' value(s): $unknown (allowed: $valid)"
    }
    return tokens
}

private fun describeStrategies(): List<Map<String, Any?>> =
    AgentBuilderComponents.strategies.map { s ->
        mapOf(
            "id" to s.id.name.lowercase(),
            "name" to s.name,
            "description" to s.description,
            "paramsSchema" to s.paramsSchema.mapValues { (_, p) ->
                LinkedHashMap<String, Any?>().apply {
                    put("type", p.type)
                    put("default", p.default)
                    p.min?.let { put("min", it) }
                    p.max?.let { put("max", it) }
                    p.description?.let { put("description", it) }
                }
            }
        )
    }

private fun describeHarnesses(): List<Map<String, Any?>> =
    AgentBuilderComponents.harnesses().map { h ->
        mapOf(
            "id" to h.id,
            "name" to h.name,
            "description" to h.description,
            "custom" to h.custom
        )
    }

private fun describeTools(parent: AgentModule, intent: String?): List<Map<String, Any?>> {
    val parentTools = parent.agent?.toolRegistry?.names() ?: emptySet()
    return AgentBuilderComponents.describeTools(parentTools, intent)
}

private fun describeBudgets(): Map<String, Any?> = AgentBuilderComponents.defaultBudgets
