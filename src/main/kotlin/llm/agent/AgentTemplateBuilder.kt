package org.iotsplab.akiba.llm.agent

import org.iotsplab.akiba.llm.client.AkibaLLMClient

// ============================================================
//  AgentTemplateBuilder — DSL for constructing [AgentTemplate]s
// ============================================================
//
// Most modules want to publish one or two templates.  Constructing an
// [AgentTemplate] via its primary constructor is verbose — the call
// site ends up looking like:
//
//   AgentTemplate(
//       id = "binary_crypto_worker",
//       version = "1.0",
//       name = "Binary crypto worker",
//       description = "...",
//       baseSystemPrompt = "...",
//       defaultStrategy = StrategySpec(StrategySpecId.REACT, mapOf(...)),
//       defaultHarness = HarnessSpec("default"),
//       defaultMaxIterations = 6,
//       toolPolicy = ToolPolicy(
//           allow = setOf("search_skill", "read_skill", "query_ghidra_api"),
//           deny  = setOf("run_shell", "run_script", "spawn_sub_agent"),
//       ),
//       budgetPolicy = BudgetPolicy(maxDepth = 2, maxIterations = 6, ...),
//       inputSchema = mapOf(
//           "task"  to TemplateInputSpec("string", required = true,  ...),
//           "focus" to TemplateInputSpec("string", required = false, ...),
//       ),
//       allowedOverrides = setOf("maxIterations", "focus"),
//       factory = { ctx -> akibaAgent { ... } },
//   )
//
// The DSL below produces the same value but reads top-to-bottom and
// groups related configuration:
//
//   agentTemplate("binary_crypto_worker") {
//       version = "1.0"
//       description = "Binary crypto worker"
//       baseSystemPrompt = "..."
//       maxIterations(6)
//
//       strategy(StrategySpecId.REACT)
//
//       allowTools("search_skill", "read_skill", "query_ghidra_api")
//       denyTools("run_shell", "run_script", "spawn_sub_agent")
//
//       input("task") { type = "string"; required = true; description = "..." }
//       input("focus") { type = "string"; required = false }
//       allowedOverrides("maxIterations", "focus")
//
//       budget {
//           maxDepth = 2
//           maxIterations = 6
//       }
//
//       interaction {
//           canAskParent = true
//           maxBlockingRequests = 3
//       }
//
//       factory { ctx -> akibaAgent { ... } }
//   }
//
// The DSL compiles to the same `AgentTemplate` data class — no extra
// indirection, no parsing, no reflection.  The factory closure is
// stored as-is.
// ============================================================

/**
 * Mutable builder backing the [agentTemplate] DSL.  Collect every
 * field, then produce an [AgentTemplate] via [build].  All setter
 * methods return the receiver so calls can be chained.
 */
class AgentTemplateBuilder(private val id: String) {

    // ---- Required-by-validation fields --------------------------------

    var version: String = "1.0"
    var name: String = id
    var description: String = ""
    var baseSystemPrompt: String = ""

    // ---- Strategy / harness -------------------------------------------

    private var strategyId: StrategySpecId = StrategySpecId.REACT
    private var strategyParams: Map<String, Any?> = emptyMap()
    private var harnessId: String? = null
    private var harnessDescription: String? = null
    private var harnessParams: Map<String, Any?> = emptyMap()
    private var maxIterations: Int = 8

    // ---- Tool policy --------------------------------------------------

    private val allow: MutableSet<String> = linkedSetOf()
    private val deny: MutableSet<String> = linkedSetOf()
    private var allowRecursion: Boolean = false

    // ---- Skills -------------------------------------------------------

    private val requiredSkillIds: MutableList<String> = mutableListOf()
    private val optionalSkillIds: MutableList<String> = mutableListOf()

    // ---- Inputs / overrides -------------------------------------------

    private val inputs: LinkedHashMap<String, TemplateInputSpec> = linkedMapOf()
    private val allowedOverrides: MutableSet<String> = linkedSetOf()

    // ---- Budget / interaction -----------------------------------------

    /**
     * Mutable scratch space for budget overrides.  [build] reads
     * [pendingBudget] first; if null, the default [BudgetPolicy] is
     * used.  Holds the assembled [BudgetPolicy] after a `budget { ... }`
     * block has run.
     */
    private val budget = BudgetPolicy()
    private var pendingBudget: BudgetPolicy? = null

    /**
     * Mutable scratch space for interaction overrides.  [build] reads
     * [pendingInteraction] first; if null, the default [InteractionPolicy]
     * is used.  Holds the assembled [InteractionPolicy] after an
     * `interaction { ... }` block has run.
     */
    private val interaction = InteractionPolicy()
    private var pendingInteraction: InteractionPolicy? = null

    // ---- Factory ------------------------------------------------------

    private var factory: ((SubAgentFactoryContext) -> AkibaAgent)? = null

    // ---- Task-prompt renderer -----------------------------------------

    /**
     * Optional custom renderer for the per-dispatch `## Concrete task`
     * section of the child session's first user message.  Receives the
     * fully-resolved inputs (post-default, post-validation; secrets are
     * redacted by the orchestrator before the renderer is called) and
     * returns prose to embed verbatim into the prompt.
     *
     * When unset, the orchestrator falls back to a generic renderer that
     * lists inputs as a structured `## Inputs` block and adds a generic
     * sentence linking inputs to the role described in the system
     * prompt.  Templates whose inputs read better as inline prose
     * (e.g. address ranges for a linear sweep) should set a custom
     * renderer so the child LLM cannot mistake the structured inputs
     * for boilerplate metadata and skip the task.
     */
    private var taskPromptRenderer: ((Map<String, Any?>) -> String)? = null

    // ---- Setters (return this for chaining) ---------------------------

    fun version(v: String): AgentTemplateBuilder = apply { version = v }
    fun name(n: String): AgentTemplateBuilder = apply { name = n }
    fun description(d: String): AgentTemplateBuilder = apply { description = d }
    fun baseSystemPrompt(prompt: String): AgentTemplateBuilder = apply { baseSystemPrompt = prompt }

    fun strategy(id: StrategySpecId, params: Map<String, Any?> = emptyMap()): AgentTemplateBuilder = apply {
        strategyId = id
        strategyParams = params
    }

    fun defaultHarness(
        id: String,
        description: String? = null,
        params: Map<String, Any?> = emptyMap()
    ): AgentTemplateBuilder = apply {
        harnessId = id
        harnessDescription = description
        harnessParams = params
    }

    fun maxIterations(n: Int): AgentTemplateBuilder = apply { maxIterations = n }

    fun allowTools(vararg names: String): AgentTemplateBuilder = apply { allow += names }
    fun allowTools(names: Iterable<String>): AgentTemplateBuilder = apply { allow += names }
    fun denyTools(vararg names: String): AgentTemplateBuilder = apply { deny += names }
    fun denyTools(names: Iterable<String>): AgentTemplateBuilder = apply { deny += names }
    fun allowRecursion(value: Boolean): AgentTemplateBuilder = apply { allowRecursion = value }

    fun requireSkills(vararg ids: String): AgentTemplateBuilder = apply { requiredSkillIds += ids }
    fun optionalSkills(vararg ids: String): AgentTemplateBuilder = apply { optionalSkillIds += ids }

    fun input(name: String, configure: InputBuilder.() -> Unit): AgentTemplateBuilder = apply {
        val b = InputBuilder(name).apply(configure)
        inputs[name] = b.build()
    }

    fun allowedOverrides(vararg keys: String): AgentTemplateBuilder = apply { allowedOverrides += keys }

    fun budget(configure: BudgetPolicyBuilder.() -> Unit): AgentTemplateBuilder = apply {
        val newBudget = BudgetPolicyBuilder(budget).apply(configure).build()
        // Re-assign: budget is `val` and immutable, so we shadow via
        // a mutable holder.  We do it through a setter below.
        pendingBudget = newBudget
    }

    fun interaction(configure: InteractionPolicyBuilder.() -> Unit): AgentTemplateBuilder = apply {
        val newInteraction = InteractionPolicyBuilder(interaction).apply(configure).build()
        pendingInteraction = newInteraction
    }

    fun factory(block: (SubAgentFactoryContext) -> AkibaAgent): AgentTemplateBuilder = apply {
        factory = block
    }

    /**
     * Register a custom task-prompt renderer.  See [AgentTemplate.taskPromptRenderer].
     */
    fun taskPromptRenderer(block: (Map<String, Any?>) -> String): AgentTemplateBuilder = apply {
        taskPromptRenderer = block
    }

    /**
     * Materialise the [AgentTemplate].  Throws [IllegalStateException]
     * if any required field is missing.
     */
    fun build(): AgentTemplate {
        require(baseSystemPrompt.isNotBlank()) {
            "agentTemplate('$id'): baseSystemPrompt must be set"
        }
        require(description.isNotBlank()) {
            "agentTemplate('$id'): description must be set"
        }
        require(maxIterations in 1..1000) {
            "agentTemplate('$id'): maxIterations must be in 1..1000, got $maxIterations"
        }
        val resolvedFactory = factory
            ?: error("agentTemplate('$id'): factory { ... } block is required")
        val defaultHarness = harnessId?.let {
            HarnessSpec(id = it, description = harnessDescription, params = harnessParams)
        }
        return AgentTemplate(
            id = id,
            version = version,
            name = name,
            description = description,
            baseSystemPrompt = baseSystemPrompt,
            requiredSkillIds = requiredSkillIds.toList(),
            optionalSkillIds = optionalSkillIds.toList(),
            defaultStrategy = StrategySpec(strategyId, strategyParams),
            defaultHarness = defaultHarness,
            defaultMaxIterations = maxIterations,
            toolPolicy = ToolPolicy(
                allow = allow.toSet(),
                deny = deny.toSet(),
                allowRecursion = allowRecursion
            ),
            interactionPolicy = pendingInteraction ?: interaction,
            budgetPolicy = pendingBudget ?: budget,
            inputSchema = inputs.toMap(),
            allowedOverrides = allowedOverrides.toSet(),
            factory = resolvedFactory,
            taskPromptRenderer = taskPromptRenderer
        )
    }
}

// ============================================================
//  Sub-DSLs
// ============================================================

/** DSL for a single [TemplateInputSpec]. */
class InputBuilder(private val name: String) {
    var type: String = "string"
    var required: Boolean = true
    var default: Any? = null
    var description: String = ""
    var enum: List<String>? = null
    var secret: Boolean = false

    fun enum(vararg values: String) { enum = values.toList() }
    fun enum(values: Iterable<String>) { enum = values.toList() }
    fun required(v: Boolean) { required = v }
    fun type(t: String) { type = t }
    fun default(v: Any?) { default = v }
    fun description(d: String) { description = d }
    fun secret(s: Boolean) { secret = s }

    internal fun build(): TemplateInputSpec = TemplateInputSpec(
        type = type,
        required = required,
        default = default,
        description = description,
        enum = enum,
        secret = secret
    )
}

/**
 * DSL for [BudgetPolicy].  The builder is mutable: assigning to a
 * property records the new value, and [build] produces the final
 * [BudgetPolicy] by composing the originally-supplied target with the
 * overrides.
 */
class BudgetPolicyBuilder(initial: BudgetPolicy) {
    var maxIterations: Int = initial.maxIterations
    var maxWallClockMs: Long = initial.maxWallClockMs
    var maxToolCalls: Int = initial.maxToolCalls
    var maxDepth: Int = initial.maxDepth
    var maxChildrenPerCall: Int = initial.maxChildrenPerCall
    var maxChildrenPerRoot: Int = initial.maxChildrenPerRoot

    internal fun build(): BudgetPolicy = BudgetPolicy(
        maxIterations = maxIterations,
        maxWallClockMs = maxWallClockMs,
        maxToolCalls = maxToolCalls,
        maxDepth = maxDepth,
        maxChildrenPerCall = maxChildrenPerCall,
        maxChildrenPerRoot = maxChildrenPerRoot,
    )
}

/**
 * DSL for [InteractionPolicy].  Same pattern as [BudgetPolicyBuilder]:
 * mutable properties are recorded and [build] produces the final
 * immutable policy.
 */
class InteractionPolicyBuilder(initial: InteractionPolicy) {
    private var lifecycle: Lifecycle = initial.lifecycle
    private var canSendToParent: Boolean = initial.canSendToParent
    private var canSendToChildren: Boolean = initial.canSendToChildren
    private var canPublishArtifacts: Boolean = initial.canPublishArtifacts
    private var canAskParent: Boolean = initial.canAskParent
    private var canRequestSiblingArtifacts: Boolean = initial.canRequestSiblingArtifacts
    private var canReadParentTranscript: Boolean = initial.canReadParentTranscript
    private var defaultAskTimeoutMs: Long = initial.defaultAskTimeoutMs
    private var maxBlockingRequests: Int = initial.maxBlockingRequests
    private var canBeCancelledBy: CancellationPolicy = initial.canBeCancelledBy

    fun lifecycle(v: Lifecycle) { lifecycle = v }
    fun canSendToParent(v: Boolean) { canSendToParent = v }
    fun canSendToChildren(v: Boolean) { canSendToChildren = v }
    fun canPublishArtifacts(v: Boolean) { canPublishArtifacts = v }
    fun canAskParent(v: Boolean) { canAskParent = v }
    fun canRequestSiblingArtifacts(v: Boolean) { canRequestSiblingArtifacts = v }
    fun canReadParentTranscript(v: Boolean) { canReadParentTranscript = v }
    fun defaultAskTimeoutMs(v: Long) { defaultAskTimeoutMs = v }
    fun maxBlockingRequests(v: Int) { maxBlockingRequests = v }
    fun canBeCancelledBy(v: CancellationPolicy) { canBeCancelledBy = v }

    internal fun build(): InteractionPolicy = InteractionPolicy(
        lifecycle = lifecycle,
        canSendToParent = canSendToParent,
        canSendToChildren = canSendToChildren,
        canPublishArtifacts = canPublishArtifacts,
        canAskParent = canAskParent,
        canRequestSiblingArtifacts = canRequestSiblingArtifacts,
        canReadParentTranscript = canReadParentTranscript,
        defaultAskTimeoutMs = defaultAskTimeoutMs,
        maxBlockingRequests = maxBlockingRequests,
        canBeCancelledBy = canBeCancelledBy,
    )
}

// ============================================================
//  Top-level DSL entry point
// ============================================================

/**
 * Build an [AgentTemplate] using a Kotlin DSL.
 *
 * ```kotlin
 * val tpl = agentTemplate("binary_crypto_worker") {
 *     version = "1.0"
 *     description = "..."
 *     baseSystemPrompt = "..."
 *     maxIterations(6)
 *     strategy(StrategySpecId.REACT)
 *     allowTools("search_skill", "read_skill")
 *     denyTools("run_shell")
 *     input("task") { type = "string"; required = true }
 *     factory { ctx -> akibaAgent { ... } }
 * }
 * ```
 */
fun agentTemplate(id: String, configure: AgentTemplateBuilder.() -> Unit): AgentTemplate =
    AgentTemplateBuilder(id).apply(configure).build()

/**
 * Mutable variant of [agentTemplate] returning the builder directly.
 * Useful when a module wants to assemble the builder in multiple steps.
 */
fun agentTemplateBuilder(id: String): AgentTemplateBuilder = AgentTemplateBuilder(id)
