package org.iotsplab.akiba.llm.agent.template

import com.fasterxml.jackson.databind.JsonNode
import org.iotsplab.akiba.llm.agent.core.AgentTranscriptWriter
import org.iotsplab.akiba.llm.agent.core.AkibaAgent
import org.iotsplab.akiba.llm.client.AkibaLLMClient
import org.iotsplab.akiba.utils.AkibaJson

// ============================================================
//  AgentTemplate — pre-built, reusable agent construction block
// ============================================================
//
// A pre-configured recipe for building a child [AkibaAgent]: the host
// encapsulates harness / strategy / prompt wiring once, the parent agent picks
// a template by id and supplies a few typed inputs, and the orchestrator
// validates scope, tool policy, depth and budget before any child is created.
// Templates are hot-swappable at runtime (register, replace, unregister);
// storage and lookup live in `AgentTemplateRegistry`.
// ============================================================

/**
 * Strategy specifier.  Only allow-listed ids may be referenced by a parent
 * agent; unknown ids are rejected by the orchestrator.  This avoids the
 * parent agent passing arbitrary class names.
 */
enum class StrategySpecId {
    REACT;

    companion object {
        fun fromString(raw: String?): StrategySpecId? = when (raw?.lowercase()) {
            "react", "reactstrategy", "default" -> REACT
            else -> null
        }
    }
}

/**
 * Describes the execution strategy of a template.  Kept intentionally small
 * (id + typed parameters) so it can be safely serialised in tool responses
 * and validated against an allow-list.
 */
data class StrategySpec(
    val id: StrategySpecId = StrategySpecId.REACT,
    /** Strategy-specific tunables.  Keys are strategy-defined. */
    val params: Map<String, Any?> = emptyMap()
)

/**
 * Describes a harness that the child agent should run with.  Because
 * harnesses are arbitrary code, the id is only meaningful in concert with
 * the orchestrator's harness registry; the orchestrator is responsible for
 * resolving the id to a real [AgentHarness] instance.  Unknown ids are
 * rejected.
 */
data class HarnessSpec(
    val id: String,
    val description: String? = null,
    val params: Map<String, Any?> = emptyMap()
)

/**
 * Lifecycle attached to a sub-agent dispatch.
 *
 * - [ONE_SHOT] (default): runs once, then terminal (`status='completed'`);
 *   history stays readable but `send_agent_message` rejects further messages.
 * - [STANDBY]: parks as `status='standby'` after its primary task;
 *   `beforeIteration` drains inbound messages for follow-up work. The
 *   dispatcher that wakes a parked session is wired separately
 *   (AgentMailboxDispatcher).
 */
enum class Lifecycle { ONE_SHOT, STANDBY }

/**
 * Policy for "who can cancel this session" — enforced by [DefaultStateHook]
 * when registered with the runtime.
 *
 * The system-level caller path (`callerSessionId == null`: runtime, orphan
 * reaper, cascade, CLI) is ALWAYS allowed; policy restricts agents only.
 * [ANCESTOR_ONLY] (default) = closest parent only, never siblings or
 * descendants. [ANY] = any session. [NONE] = system only. Self-cancellation
 * is always allowed.
 */
enum class CancellationPolicy { ANCESTOR_ONLY, ANY, NONE }

/**
 * Policy controlling which tools a child agent may use and which are denied.
 *
 * Effective set: `(allow ∩ parent registry)`, or the full parent registry when
 * `allow` is empty, minus `deny` — `deny` always wins. Unknown `allow` names
 * reject the spec (typo catcher); unknown `deny` names are ignored so
 * role-level constraints survive parent-side filtering. Parents cannot escape
 * via `SubAgentSpec.overrides` — see [AgentTemplate.allowedOverrides].
 */
data class ToolPolicy(
    /** Tool names the child may use.  Empty means "inherit parent's eligible set". */
    val allow: Set<String> = emptySet(),
    /** Tool names the child is explicitly forbidden from using.
     *  Entries not present in the parent's registry are silently ignored. */
    val deny: Set<String> = emptySet(),
    /** True if the child may recursively spawn further sub-agents.  Default false. */
    val allowRecursion: Boolean = false
) {
    /**
     * Resolve the effective tool set given a parent's tool names.
     *
     * `allow` is validated against [parentTools]: any tool name the
     * parent does not expose is rejected (typo catcher).
     *
     * `deny` is *not* validated: entries the parent does not expose are
     * simply skipped (no-op). This lets templates express role-level
     * security constraints (e.g. "no shell") that survive parent-side
     * tool filtering.
     *
     * @throws IllegalArgumentException for unknown tools in `allow`.
     */
    fun resolve(parentTools: Set<String>): Set<String> {
        val unknownAllow = allow - parentTools
        require(unknownAllow.isEmpty()) {
            "ToolPolicy allow contains tool(s) not present in parent registry: $unknownAllow"
        }
        val base = if (allow.isEmpty()) parentTools else allow
        return base - deny
    }
}

/**
 * Budget policy attached to a sub-agent invocation.  All fields are
 * upper bounds — the parent agent may lower them through [SubAgentSpec.overrides]
 * if and only if the field is listed in [AgentTemplate.allowedOverrides].
 */
data class BudgetPolicy(
    /** Hard cap on iterations.  0 means use the template's default. */
    val maxIterations: Int = 0,
    /** Soft wall-clock cap in milliseconds.  0 means unbounded. */
    val maxWallClockMs: Long = 0L,
    /** Maximum tool calls in a single run.  0 means unbounded. */
    val maxToolCalls: Int = 0,
    /** Maximum depth in the agent tree.  1 = leaf. */
    val maxDepth: Int = 1,
    /** Maximum number of sub-agents a single parent may spawn in one call. */
    val maxChildrenPerCall: Int = 1,
    /** Maximum number of sub-agents a single root may spawn cumulatively. */
    val maxChildrenPerRoot: Int = 8,
)

/**
 * Interaction policy for child agents.
 *
 * Covers parent/child mailbox + artifact sharing. The sibling-to-sibling
 * and blocking-ask surfaces ([canAskParent] /
 * [canRequestSiblingArtifacts] / [canReadParentTranscript]) are not
 * yet supported: they default to `false` and the `init` block rejects
 * non-false values so callers notice instead of silently getting
 * a no-op.
 */
data class InteractionPolicy(
    /** Lifecycle after the primary task finishes. See [Lifecycle]. */
    val lifecycle: Lifecycle = Lifecycle.ONE_SHOT,
    /**
     * STANDBY children only: when true (default), the child parks
     * immediately at spawn WITHOUT running its initial task prompt and
     * waits for the first mailbox message (e.g. a request-driven worker
     * such as AndroidAnalyzer's native analyzer).  When false, the
     * child runs its initial task prompt first — required when the
     * spawn-time inputs already assign real work (e.g. the Java
     * analyzer's file list) — and only parks when it later calls
     * `await_condition`.  Ignored for ONE_SHOT lifecycle.
     */
    val coldStart: Boolean = true,
    /** Child may send mailbox messages to its direct parent. */
    val canSendToParent: Boolean = true,
    /** Parent may send mailbox messages to its direct children. */
    val canSendToChildren: Boolean = true,
    /** Agent may publish named artifacts readable by other sessions. */
    val canPublishArtifacts: Boolean = true,

    // ---- Fields reserved for future blocking-ask surface. Reject non-default.

    /** Child may send blocking ask-parent requests.  Default false. */
    val canAskParent: Boolean = false,
    /** Child may ask parent to read sibling artifacts.  Default false. */
    val canRequestSiblingArtifacts: Boolean = false,
    /** Child may read parent's full transcript.  Default false. */
    val canReadParentTranscript: Boolean = false,
    /** Default timeout for a single child→parent request, in milliseconds. */
    val defaultAskTimeoutMs: Long = 300_000L,
    /** Maximum number of blocking requests a child may issue per run. */
    val maxBlockingRequests: Int = 0,
    /**
     * Who may cancel this session, honoured by [DefaultStateHook] when
     * registered with the runtime. Defaults to
     * [CancellationPolicy.ANCESTOR_ONLY]: only the closest direct/indirect
     * parent (and system-level callers) can cancel, never siblings, uncles or
     * descendants. See [CancellationPolicy].
     */
    val canBeCancelledBy: CancellationPolicy = CancellationPolicy.ANCESTOR_ONLY,
) {
    init {
        require(!canAskParent) { "InteractionPolicy.canAskParent is not yet supported" }
        require(!canRequestSiblingArtifacts) {
            "InteractionPolicy.canRequestSiblingArtifacts is not yet supported"
        }
        require(!canReadParentTranscript) {
            "InteractionPolicy.canReadParentTranscript is not yet supported"
        }
    }

    /** Map the enum to the database column's wire value. */
    fun lifecycleWireValue(): String = when (lifecycle) {
        Lifecycle.ONE_SHOT -> "one_shot"
        Lifecycle.STANDBY -> "standby"
    }
}

/**
 * Schema for a single input field of a template.  The orchestrator
 * validates [SubAgentSpec.inputs] against the template's input schema
 * before the factory is invoked.
 */
data class TemplateInputSpec(
    /** JSON-Schema-like type: "string", "integer", "number", "boolean", "object", "array". */
    val type: String = "string",
    val required: Boolean = true,
    val default: Any? = null,
    val description: String = "",
    val enum: List<String>? = null,
    /** Mark as secret: never written to transcript / logs. */
    val secret: Boolean = false
)

/**
 * Bundle passed to a template's [AgentTemplate.factory] when the orchestrator
 * instantiates a child agent. Carries everything the factory needs to build a
 * fully-wired [AkibaAgent] without depending on the agent's own mutable state.
 *
 * The factory must treat this bundle as the *only* source of configuration
 * and must not read additional state from the parent agent, which may have
 * changed by the time the factory runs.
 */
class SubAgentFactoryContext(
    /** Template id this factory is being invoked for. */
    val templateId: String,
    /** Template version, copied from [AgentTemplate.version]. */
    val templateVersion: String,
    /** The parent session id (root of the tree for root-level agents). */
    val rootSessionId: String?,
    /** The direct parent session id.  Null when spawning the root. */
    val parentSessionId: String?,
    /** Current depth (1 for root, 2 for child of root, ...). */
    val depth: Int,
    /** Validated [BudgetPolicy] for this invocation. */
    val budget: BudgetPolicy,
    /** Validated [ToolPolicy] for this invocation. */
    val toolPolicy: ToolPolicy,
    /** Validated inputs (type-checked against inputSchema, defaults applied). */
    val inputs: Map<String, Any?>,
    /** Overrides actually applied (post-allowlist). */
    val appliedOverrides: Map<String, Any?>,
    /** Resolved tool names (the exact set the child may use). */
    val resolvedToolNames: Set<String>,
    /** The shared LLM client (resolved by the orchestrator from parent). */
    val llmClient: AkibaLLMClient,
    /** Session id reserved for the new child (may be null if DB is unavailable). */
    val childSessionId: String?,
    /** Human-readable name of the child, for logs/transcript. */
    val childName: String,
    /**
     * The full template that was resolved.  Factories can read
     * `baseSystemPrompt`, the original `defaultStrategy` / `defaultHarness`,
     * etc. from here without keeping a reference to the parent module.
     */
    val template: AgentTemplate = throw IllegalStateException("template not set"),
    /**
     * The resolved spec view produced by [SubAgentSpec.validate].
     * Factories can read `effectiveMaxIterations`, `effectiveStrategy`,
     * `effectiveHarness`, `effectiveMaxToolCalls`, etc. from here.
     */
    val resolvedSpec: ResolvedSubAgentSpec = throw IllegalStateException("resolvedSpec not set"),
    /**
     * The shared [org.iotsplab.akiba.data.database.AgentDatabaseClient] used
     * by the orchestrator. Factories that need to query the database
     * (e.g. to look up siblings / parent memories) should use this rather
     * than reaching for the parent's connection. Null when DB persistence
     * is disabled for the child session.
     */
    val agentDbClient: org.iotsplab.akiba.data.database.AgentDatabaseClient? = null,
    /**
     * The chat memory instance the orchestrator has reserved for the child.
     * Factories MUST use this (or build a memory backed by [childSessionId])
     * instead of constructing an `inMemory` memory of their own — otherwise
     * the child's messages are lost when the child returns, which breaks
     * front-end "Open child session transcript" and the export feature.
     */
    val memory: org.iotsplab.akiba.llm.memory.ChatMemory =
        org.iotsplab.akiba.llm.memory.InMemoryChatMemory(),
    /**
     * The transcript writer the orchestrator has reserved for the child.
     * When provided, factories should pass it to the child agent via the
     * builder's `transcript(...)` setter so that the child's LLM
     * interactions, tool calls, and results are written to the child
     * session's `transcript` column (visible to the export endpoint and
     * the tree view). Null only when DB persistence is disabled.
     */
    val transcript: AgentTranscriptWriter? = null,
) {
    /** Helper to read a required string input. */
    fun requireInput(key: String): String =
        (inputs[key] as? String) ?: error("Required input '$key' missing or not a string")

    /** Helper to read an optional string input. */
    fun optionalInput(key: String): String? = inputs[key] as? String

    /** Helper to read an optional int input. */
    fun optionalInt(key: String): Int? = (inputs[key] as? Number)?.toInt()
}

/**
 * A pre-built, reusable agent construction recipe, built at the host level.
 * The LLM sees only the descriptor from [describe] and references templates
 * by id.
 *
 * @param id Must match `[a-z0-9][a-z0-9_-]{0,63}`.
 * @param allowedOverrides `SubAgentSpec.overrides` keys the parent may change.
 * @param taskPromptRenderer Replaces the generic `## Concrete task` section;
 *   set it when inputs read better as prose than as a key-value table.
 */
data class AgentTemplate(
    val id: String,
    val version: String = "1.0",
    val name: String,
    val description: String,
    val baseSystemPrompt: String,
    val requiredSkillIds: List<String> = emptyList(),
    val optionalSkillIds: List<String> = emptyList(),
    val defaultStrategy: StrategySpec = StrategySpec(),
    val defaultHarness: HarnessSpec? = null,
    val defaultMaxIterations: Int = 8,
    val toolPolicy: ToolPolicy = ToolPolicy(),
    val interactionPolicy: InteractionPolicy = InteractionPolicy(),
    val budgetPolicy: BudgetPolicy = BudgetPolicy(),
    val inputSchema: Map<String, TemplateInputSpec> = emptyMap(),
    val allowedOverrides: Set<String> = emptySet(),
    val factory: (SubAgentFactoryContext) -> AkibaAgent,
    val taskPromptRenderer: ((Map<String, Any?>) -> String)? = null
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9_-]{0,63}"))) {
            "Template id '$id' must match [a-z0-9][a-z0-9_-]{0,63}"
        }
        require(baseSystemPrompt.isNotBlank()) {
            "Template '$id' must declare a non-blank baseSystemPrompt"
        }
        require(defaultMaxIterations in 1..1000) {
            "Template '$id' defaultMaxIterations must be in 1..1000, got $defaultMaxIterations"
        }
        // interactionPolicy already self-validates in its own init.
    }

    /**
     * Render a self-describing snapshot of this template for the LLM.
     * The factory closure is intentionally **not** serialised — the parent
     * agent must reference the template by id.
     */
    fun describe(includeFullInputs: Boolean = false): Map<String, Any?> {
        val inputs = if (includeFullInputs) {
            inputSchema.mapValues { (_, spec) ->
                mapOf(
                    "type" to spec.type,
                    "required" to spec.required,
                    "default" to spec.default,
                    "description" to spec.description,
                    "enum" to spec.enum
                )
            }
        } else {
            inputSchema.mapValues { (_, spec) ->
                mapOf(
                    "type" to spec.type,
                    "required" to spec.required,
                    "description" to spec.description
                )
            }
        }
        return mapOf(
            "id" to id,
            "version" to version,
            "name" to name,
            "description" to description,
            "strategy" to mapOf(
                "id" to defaultStrategy.id.name.lowercase(),
                "params" to defaultStrategy.params
            ),
            "harness" to defaultHarness?.let { mapOf("id" to it.id, "description" to it.description) },
            "defaultMaxIterations" to defaultMaxIterations,
            "defaultTools" to (if (toolPolicy.allow.isEmpty()) "(inherit parent)" else toolPolicy.allow.sorted()),
            "deniedTools" to toolPolicy.deny.sorted(),
            "allowRecursion" to toolPolicy.allowRecursion,
            "requiredSkillIds" to requiredSkillIds,
            "optionalSkillIds" to optionalSkillIds,
            "allowedOverrides" to allowedOverrides.sorted(),
            "inputSchema" to inputs,
            "budget" to mapOf(
                "maxIterations" to budgetPolicy.maxIterations,
                "maxWallClockMs" to budgetPolicy.maxWallClockMs,
                "maxToolCalls" to budgetPolicy.maxToolCalls,
                "maxDepth" to budgetPolicy.maxDepth,
                "maxChildrenPerCall" to budgetPolicy.maxChildrenPerCall,
                "maxChildrenPerRoot" to budgetPolicy.maxChildrenPerRoot
            ),
            "interaction" to mapOf(
                "lifecycle" to interactionPolicy.lifecycle.name.lowercase(),
                "canSendToParent" to interactionPolicy.canSendToParent,
                "canSendToChildren" to interactionPolicy.canSendToChildren,
                "canPublishArtifacts" to interactionPolicy.canPublishArtifacts,
                "canAskParent" to interactionPolicy.canAskParent,
                "canRequestSiblingArtifacts" to interactionPolicy.canRequestSiblingArtifacts,
                "canReadParentTranscript" to interactionPolicy.canReadParentTranscript,
                "defaultAskTimeoutMs" to interactionPolicy.defaultAskTimeoutMs,
                "maxBlockingRequests" to interactionPolicy.maxBlockingRequests,
                "canBeCancelledBy" to interactionPolicy.canBeCancelledBy.name.lowercase()
            )
        )
    }

    companion object {
        private val mapper = AkibaJson.mapper

        /**
         * Convenience: render a descriptor as pretty JSON.  Useful for tool
         * responses and for `agent_builder_alternatives`.
         */
        fun describeAsJson(template: AgentTemplate, includeFullInputs: Boolean = false): String =
            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(template.describe(includeFullInputs))

        /**
         * Parse a JSON object previously produced by [describe] back into
         * the parts the LLM might have edited (strategy, allowedOverrides,
         * etc.).  The factory itself is *not* round-trippable — it must
         * always come from the host side.
         */
        fun parseEditableDescriptor(node: JsonNode): TemplateEditableDescriptor {
            val id = node.get("id")?.asText() ?: error("Template descriptor missing 'id'")
            val version = node.get("version")?.asText() ?: "1.0"
            val maxIter = node.get("defaultMaxIterations")?.asInt() ?: 8
            val allowed = node.get("allowedOverrides")?.let { n ->
                if (n.isArray) n.mapNotNull { it.asText(null) }.toSet() else emptySet()
            } ?: emptySet()
            return TemplateEditableDescriptor(id, version, maxIter, allowed)
        }
    }
}

/**
 * Editable subset of a template descriptor — the parts a parent agent
 * may inspect and reference.  Returned by [AgentTemplate.parseEditableDescriptor].
 */
data class TemplateEditableDescriptor(
    val id: String,
    val version: String,
    val defaultMaxIterations: Int,
    val allowedOverrides: Set<String>
)
