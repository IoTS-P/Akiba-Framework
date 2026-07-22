package org.iotsplab.akiba.llm.agent

import org.apache.logging.log4j.LogManager
import java.util.concurrent.ConcurrentHashMap

// ============================================================
//  AgentBuilderComponents — single source of truth for the
//  "building blocks" used to construct child agents
// ============================================================
//
// This object collects every component a parent agent can use to build
// a child agent: strategies, harnesses, tools, default budgets.
//
// Design notes:
//   - All static catalogue data (built-in strategies, default harness,
//     tool risk tags, default budgets) lives here as `val` constants.
//   - Custom harnesses are dynamic: callers register them at runtime
//     via [registerHarness].  The catalogue returned by
//     [describeHarnesses] always reflects the current state.
//   - [resolveStrategy] / [resolveHarness] materialise specs into live
//     [AgentStrategy] / [AgentHarness] instances, so template factories
//     don't have to know how each strategy is constructed.
//   - The object is process-global; it survives module / workflow
//     boundaries.  Tests should call [clear] in teardown.
// ============================================================

/**
 * Process-global registry of agent construction components.  This is
 * the single source of truth for the `agent_builder_alternatives`
 * tool and for any consumer that needs to enumerate / instantiate
 * strategies, harnesses, tools, or budgets.
 */
object AgentBuilderComponents {

    private val logger = LogManager.getLogger(AgentBuilderComponents::class.java)

    // ============================================================
    //  Strategy descriptors
    // ============================================================

    /**
     * Descriptor for a single strategy parameter.  Mirrors a subset of
     * JSON Schema used to render a self-describing catalogue entry.
     */
    data class ParamSchema(
        val type: String,
        val default: Any?,
        val min: Number? = null,
        val max: Number? = null,
        val description: String? = null
    )

    /**
     * Self-describing entry for one strategy.  The descriptor is the
     * stable, LLM-facing shape; the [resolve] method is the
     * runtime-facing shape.
     */
    data class StrategyDescriptor(
        val id: StrategySpecId,
        val name: String,
        val description: String,
        val paramsSchema: Map<String, ParamSchema> = emptyMap()
    )

    /** Built-in strategy catalogue.  Read-only. */
    val strategies: List<StrategyDescriptor> = listOf(
        StrategyDescriptor(
            id = StrategySpecId.REACT,
            name = "ReActStrategy",
            description = "Thought → Action → Observation loop. Best for exploratory tool use.",
            paramsSchema = mapOf(
                "maxIterations" to ParamSchema(
                    type = "integer", default = 8, min = 1, max = 1000,
                    description = "Hard cap on Thought/Action rounds per run."
                )
            )
        ),
        StrategyDescriptor(
            id = StrategySpecId.PLAN_EXECUTE,
            name = "PlanExecuteStrategy",
            description = "Plan first, execute each step, then reflect. Best for multi-step tasks.",
            paramsSchema = mapOf(
                "maxReplanCycles" to ParamSchema(
                    type = "integer", default = 1, min = 0, max = 5,
                    description = "Number of re-planning cycles before forcing completion."
                ),
                "includeStepNumbers" to ParamSchema(
                    type = "boolean", default = true,
                    description = "Whether the plan includes step numbers in the execution prompt."
                )
            )
        )
    )

    /** Look up a built-in strategy descriptor by id. */
    fun strategy(id: StrategySpecId): StrategyDescriptor? =
        strategies.firstOrNull { it.id == id }

    /**
     * Resolve a [StrategySpec] to a concrete [AgentStrategy].  Unknown
     * strategy ids throw — the parent agent must reference a known id.
     */
    fun resolve(spec: StrategySpec): AgentStrategy = when (spec.id) {
        StrategySpecId.REACT -> ReActStrategy()
        StrategySpecId.PLAN_EXECUTE -> PlanExecuteStrategy(
            maxReplanCycles = (spec.params["maxReplanCycles"] as? Number)?.toInt() ?: 1,
            includeStepNumbers = (spec.params["includeStepNumbers"] as? Boolean) ?: true
        )
    }

    // ============================================================
    //  Harness descriptors + custom harness registry
    // ============================================================

    /**
     * Self-describing entry for one harness.  The id is what templates
     * reference in their [HarnessSpec.id]; the runtime instance is
     * resolved via [resolve] / [resolveHarness].
     */
    data class HarnessDescriptor(
        val id: String,
        val name: String,
        val description: String,
        val custom: Boolean = false
    )

    private val customHarnesses = ConcurrentHashMap<String, AgentHarness>()

    /** Static built-in: the no-op default harness. */
    val defaultHarness: HarnessDescriptor = HarnessDescriptor(
        id = "default",
        name = "DefaultAgentHarness",
        description = "No-op harness. Use when no domain-specific workflow is required.",
        custom = false
    )

    /**
     * Register a custom harness by id.  The id is what templates
     * declare in [HarnessSpec.id].  Re-registering with the same id
     * replaces the previous instance.
     */
    fun registerHarness(id: String, harness: AgentHarness) {
        require(id.isNotBlank()) { "Harness id must not be blank" }
        require(id != defaultHarness.id) {
            "Harness id '$id' is reserved for the default harness"
        }
        customHarnesses[id] = harness
        logger.info("Registered custom harness id='{}' name='{}'", id, harness.name)
    }

    /** Remove a previously-registered custom harness.  No-op if unknown. */
    fun unregisterHarness(id: String): AgentHarness? {
        val removed = customHarnesses.remove(id)
        if (removed != null) {
            logger.info("Unregistered custom harness id='{}'", id)
        }
        return removed
    }

    /** Whether a harness with the given id is currently registered (incl. the default). */
    fun hasHarness(id: String): Boolean =
        id == defaultHarness.id || customHarnesses.containsKey(id)

    /**
     * Snapshot of every harness currently visible (default + custom).
     * Returned list is immutable.
     */
    fun harnesses(): List<HarnessDescriptor> {
        val custom = customHarnesses.entries
            .map { (id, h) -> HarnessDescriptor(id, h.name, defaultHarnessDescription(h), custom = true) }
        return listOf(defaultHarness) + custom
    }

    /**
     * Resolve a [HarnessSpec] to a concrete [AgentHarness].  Returns the
     * default no-op harness when [spec] is null or references an
     * unknown id (with a warning).
     */
    fun resolveHarness(spec: HarnessSpec?): AgentHarness {
        if (spec == null) return DefaultAgentHarness
        val custom = customHarnesses[spec.id]
        if (custom != null) return custom
        if (spec.id == defaultHarness.id) return DefaultAgentHarness
        logger.warn(
            "Harness id='{}' is not registered; falling back to DefaultAgentHarness",
            spec.id
        )
        return DefaultAgentHarness
    }

    private fun defaultHarnessDescription(h: AgentHarness): String {
        val cls = h::class
        // Try to find a kdoc-ish first sentence via the class name.  We
        // don't have a clean way to extract kdoc at runtime, so the
        // descriptor just says "custom harness" with the class name.
        return "Custom harness: ${cls.simpleName ?: "anonymous"}"
    }

    // ============================================================
    //  Tool descriptors
    // ============================================================

    /**
     * Internal classification of every known tool: risk level,
     * capability tags, and whether a child agent is allowed to use it.
     */
    data class ToolDescriptor(
        val name: String,
        val risk: String,
        val capabilities: Set<String>,
        val childEligible: Boolean,
        val description: String
    )

    /**
     * Classify every known built-in tool by risk and capability tags.
     *
     * Keep this table aligned with `BuiltInTools.all` and the per-tool
     * implementation files.  New tools should add their entry here.
     */
    val toolDescriptors: Map<String, ToolDescriptor> = listOf(
        ToolDescriptor(
            name = "run_module",
            risk = "high",
            capabilities = setOf("EXEC_MODULE", "WRITE_DB", "WRITE_FS"),
            childEligible = false,
            description = "Run another Akiba module on the same binary. Side-effecting."
        ),
        ToolDescriptor(
            name = "spawn_sub_agent",
            risk = "high",
            capabilities = setOf("SPAWN_AGENT", "RECURSIVE"),
            childEligible = false,
            description = "Spawn a child LLM agent (template path or freeform path). " +
                "Returns a handle immediately; use await_multiple_children to wait. Reserved for parent use."
        ),
        ToolDescriptor(
            name = "query_module_data",
            risk = "low",
            capabilities = setOf("READ_DB"),
            childEligible = true,
            description = "Read analysis results from the database for the current binary."
        ),
        ToolDescriptor(
            name = "query_session_history",
            risk = "medium",
            capabilities = setOf("READ_SESSION"),
            childEligible = false,
            description = "Read a session's transcript. Parent-only."
        ),
        ToolDescriptor(
            name = "query_memories",
            risk = "low",
            capabilities = setOf("READ_MEMORY"),
            childEligible = true,
            description = "Search the long-term memory store."
        ),
        ToolDescriptor(
            name = "read_history_tool_call",
            risk = "low",
            capabilities = setOf("READ_TOOL_RESULT"),
            childEligible = true,
            description = "Read a stored historical tool-call result by uuid."
        ),
        ToolDescriptor(
            name = "list_modules",
            risk = "low",
            capabilities = setOf("READ_REGISTRY"),
            childEligible = true,
            description = "List available Akiba modules."
        ),
        ToolDescriptor(
            name = "script_library",
            risk = "medium",
            capabilities = setOf("EXEC_SCRIPT", "READ_SCRIPT_LIB"),
            childEligible = false,
            description = "Search and run pre-built scripts from the library."
        ),
        ToolDescriptor(
            name = "search_skill",
            risk = "low",
            capabilities = setOf("READ_SKILL_METADATA"),
            childEligible = true,
            description = "List or search installed skills without reading their content."
        ),
        ToolDescriptor(
            name = "read_skill",
            risk = "low",
            capabilities = setOf("READ_SKILL"),
            childEligible = true,
            description = "Read a file from an installed skill package."
        ),
        ToolDescriptor(
            name = "run_script",
            risk = "high",
            capabilities = setOf("EXEC_SCRIPT"),
            childEligible = false,
            description = "Compile and run a Kotlin script. Code execution."
        ),
        ToolDescriptor(
            name = "query_ghidra_api",
            risk = "low",
            capabilities = setOf("READ_API_DOCS"),
            childEligible = true,
            description = "Search and read Ghidra API documentation."
        ),
        ToolDescriptor(
            name = "run_shell",
            risk = "high",
            capabilities = setOf("SHELL_EXEC"),
            childEligible = false,
            description = "Execute a shell command in the module workspace."
        ),
        ToolDescriptor(
            name = "agent_builder_alternatives",
            risk = "low",
            capabilities = setOf("READ_CATALOG"),
            childEligible = true,
            description = "List agent construction components and templates."
        )
    ).associateBy { it.name }

    /**
     * Filter / project the static tool descriptors down to the
     * intersection of [parentTools] (the parent agent's actual
     * registry) and the catalogue, with optional text filtering.
     *
     * Tools present in the parent registry but not in the catalogue
     * are surfaced with `risk = "unknown"` and `childEligible = false`
     * so the parent agent can see the gap.
     */
    fun describeTools(
        parentTools: Set<String>,
        intent: String? = null
    ): List<Map<String, Any?>> {
        val resolved = parentTools.map { name ->
            val d = toolDescriptors[name]
            if (d == null) {
                mapOf(
                    "name" to name,
                    "risk" to "unknown",
                    "capabilities" to emptyList<String>(),
                    "childEligible" to false,
                    "description" to "(no catalogue entry — treat as high risk)"
                )
            } else {
                mapOf(
                    "name" to d.name,
                    "risk" to d.risk,
                    "capabilities" to d.capabilities.toList().sorted(),
                    "childEligible" to d.childEligible,
                    "description" to d.description
                )
            }
        }
        val filtered = if (intent.isNullOrBlank()) resolved
        else resolved.filter { entry ->
            val name = entry["name"] as String
            val desc = entry["description"] as String
            name.contains(intent, ignoreCase = true) ||
                desc.contains(intent, ignoreCase = true)
        }
        return filtered.sortedBy { it["name"] as String }
    }

    // ============================================================
    //  Budget defaults
    // ============================================================

    /**
     * Static budget ceiling applied to child agents when no override
     * is supplied.  Templates may lower these per template, but cannot
     * raise them.
     */
    val defaultBudgets: Map<String, Any?> = mapOf(
        "maxDepth" to 2,
        "maxChildrenPerCall" to 4,
        "maxChildrenPerRoot" to 8,
        "maxIterationsPerChild" to 16,
        "maxWallClockMsPerChild" to 600_000L,
        "maxTotalToolCallsPerTree" to 200,
        "interactionFlagsSupported" to emptyList<String>()
    )

    // ============================================================
    //  Test / shutdown helpers
    // ============================================================

    /**
     * Drop every dynamic registration.  Intended for tests and
     * shutdown paths.  The static catalogue (strategies, default
     * harness, tool descriptors, budget defaults) is unaffected.
     */
    fun clear() {
        customHarnesses.clear()
        logger.warn("AgentBuilderComponents: cleared custom harness registry")
    }
}
