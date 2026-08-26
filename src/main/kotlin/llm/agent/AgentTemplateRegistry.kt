package org.iotsplab.akiba.llm.agent

import org.apache.logging.log4j.LogManager
import org.iotsplab.akiba.llm.client.AkibaLLMClient
import java.util.concurrent.ConcurrentHashMap

// ============================================================
//  AgentTemplateRegistry — process-level in-memory template store
// ============================================================
//
// Templates are not pure data: they carry factory closures, harness
// references, and validated policy.  Because of that they are kept in an
// in-memory registry keyed by template id, with **scope** metadata to
// control visibility.
//
// Scopes:
//   - A scope is a free-form string the orchestrator passes when
//     registering a template.  Convention: an AgentModule uses its
//     `agentSessionId` as the scope id, so a child agent spawned by
//     `spawn_sub_agent` (template path) can only see templates its parent
//     registered for that scope.
//   - `unregisterScope(scopeId)` removes every template registered
//     against that scope.  AgentModule's `startProcess` calls this in its
//     `finally` block to avoid leaks across runs.
//
// Concurrency:
//   - Registration / unregistration are concurrent-safe (ConcurrentHashMap).
//   - Listing returns an immutable snapshot.
//
// The registry is intentionally **not** persisted to disk.  Templates are
// in-process recipe objects.  Persisting a template id is enough for
// external systems to know "this module can produce child agents of type
// X"; the actual factory lives in the host JVM.
// ============================================================

/**
 * Process-level registry for [AgentTemplate]s.
 *
 * Typical usage:
 * ```
 * AgentTemplateRegistry.register(template, scopeId = sessionId)
 * try {
 *     // ... run parent agent that may spawn children ...
 * } finally {
 *     AgentTemplateRegistry.unregisterScope(scopeId)
 * }
 * ```
 */
object AgentTemplateRegistry {
    private val logger = LogManager.getLogger(AgentTemplateRegistry::class.java)

    /** Internal record combining a template with the scope it was registered into. */
    private data class Entry(
        val template: AgentTemplate,
        /** All scopes this template is visible in.  Same template can be registered to many scopes. */
        val scopes: MutableSet<String> = mutableSetOf()
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * Register [template] and make it visible to [scopeId].  If the same
     * template id was already registered, the previous entry is **replaced**
     * (the new factory wins); the previous scope memberships are dropped.
     *
     * @return the previous template with the same id, or null.
     */
    fun register(template: AgentTemplate, scopeId: String): AgentTemplate? {
        require(scopeId.isNotBlank()) { "scopeId must not be blank" }
        val previous = entries.remove(template.id)?.template
        val entry = Entry(template)
        entry.scopes.add(scopeId)
        entries[template.id] = entry
        logger.info(
            "Registered agent template id='{}' v='{}' name='{}' scope='{}' (replaced={})",
            template.id, template.version, template.name, scopeId, previous != null
        )
        return previous
    }

    /**
     * Unregister a single template id.  Returns the removed template or null.
     */
    fun unregister(templateId: String): AgentTemplate? {
        val removed = entries.remove(templateId)?.template
        if (removed != null) {
            logger.info("Unregistered agent template id='{}'", templateId)
        }
        return removed
    }

    /**
     * Remove every template registered into [scopeId].  Returns the number
     * of templates removed.  Intended to be called from AgentModule's
     * `finally` block to avoid leaks across runs.
     */
    fun unregisterScope(scopeId: String): Int {
        if (scopeId.isBlank()) return 0
        var removed = 0
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val e = it.next().value
            synchronized(e.scopes) {
                if (e.scopes.remove(scopeId) && e.scopes.isEmpty()) {
                    it.remove()
                    removed++
                }
            }
        }
        if (removed > 0) {
            logger.info("Unregistered {} agent template(s) for scope='{}'", removed, scopeId)
        }
        return removed
    }

    /**
     * Look up a template by id without scope filtering.  Use
     * [resolveForScope] for scope-aware lookup.
     */
    fun get(templateId: String): AgentTemplate? = entries[templateId]?.template

    /**
     * Look up a template by id only if it is visible to [scopeId].
     * @return the template, or null if not registered / not visible.
     */
    fun resolveForScope(scopeId: String, templateId: String): AgentTemplate? {
        val e = entries[templateId] ?: return null
        return if (e.scopes.contains(scopeId)) e.template else null
    }

    /**
     * List every template currently registered, regardless of scope.
     * Returned list is a snapshot and is safe to iterate.
     */
    fun listAll(): List<AgentTemplate> = entries.values.map { it.template }

    /**
     * List templates visible to [scopeId].  Returned list is a snapshot.
     */
    fun listForScope(scopeId: String): List<AgentTemplate> {
        if (scopeId.isBlank()) return emptyList()
        return entries.values
            .filter { it.scopes.contains(scopeId) }
            .map { it.template }
    }

    /**
     * Describe all templates visible to [scopeId] for tool consumption.
     * @param includeFullInputs include enum/default fields in inputSchema.
     */
    fun describeScope(
        scopeId: String,
        includeFullInputs: Boolean = false,
        intent: String? = null
    ): List<Map<String, Any?>> {
        val templates = listForScope(scopeId)
        val filtered = if (intent.isNullOrBlank()) templates
        else templates.filter { t ->
            t.id.contains(intent, ignoreCase = true) ||
                t.name.contains(intent, ignoreCase = true) ||
                t.description.contains(intent, ignoreCase = true)
        }
        return filtered
            .sortedBy { it.id }
            .map { it.describe(includeFullInputs) }
    }

    /**
     * Wipe the entire registry.  Intended for tests and shutdown paths.
     */
    fun clear() {
        entries.clear()
        logger.warn("AgentTemplateRegistry cleared")
    }

    /**
     * Number of registered templates (across all scopes).
     */
    fun size(): Int = entries.size

    /**
     * Resolve the [CancellationPolicy] of the template that owns
     * [sessionId] — looked up by checking which template's
     * `scope` membership includes [sessionId] (the convention
     * is "parent agent's sessionId = scope id").
     *
     * Used by [DefaultStateHook] when it needs to honour a
     * target's per-template `canBeCancelledBy` setting.  Returns
     * [CancellationPolicy.ANCESTOR_ONLY] when no template can be
     * resolved (freeform / top-level / orphan) — the safe default
     * that preserves the "child cannot cancel its parent" rule.
     */
    fun findPolicyForSession(
        @Suppress("UNUSED_PARAMETER") agentDbClient: org.iotsplab.akiba.data.database.AgentDatabaseClient,
        sessionId: String,
    ): CancellationPolicy {
        if (sessionId.isBlank()) return CancellationPolicy.ANCESTOR_ONLY
        for (entry in entries.values) {
            if (entry.scopes.contains(sessionId)) {
                return entry.template.interactionPolicy.canBeCancelledBy
            }
        }
        return CancellationPolicy.ANCESTOR_ONLY
    }
}

// ============================================================
//  SubAgentSpec — parent's request to spawn a child agent
// ============================================================

/**
 * Parent agent's request to spawn a child agent from a template.
 *
 * Field semantics:
 *   - [templateId] is required and must refer to a template visible to the
 *     caller's scope.
 *   - [inputs] are validated against the template's `inputSchema` (types,
 *     required, enum).
 *   - [overrides] are filtered against the template's `allowedOverrides`
 *     whitelist.  Unknown keys are rejected.
 *   - [name] is purely cosmetic (used in logs/transcript).
 *   - [skillOverrides] may only *add* to the template's
 *     `requiredSkillIds`; it cannot remove them.
 *
 * Validation is performed by [validate] before any factory runs.
 */
data class SubAgentSpec(
    val templateId: String,
    val inputs: Map<String, Any?> = emptyMap(),
    val overrides: Map<String, Any?> = emptyMap(),
    val name: String? = null,
    val skillOverrides: List<String> = emptyList()
) {
    init {
        require(templateId.isNotBlank()) { "SubAgentSpec.templateId must not be blank" }
    }

    /**
     * Validate this spec against [template] and the parent's runtime
     * context.  Throws [IllegalArgumentException] on any violation; the
     * caller is expected to convert the exception into a tool-visible
     * error string.
     *
     * @param template the resolved template (must be non-null).
     * @param parentTools the parent agent's full tool name set.
     * @param parentDepth the parent agent's depth (1-based).
     * @return a fully-resolved [SubAgentSpec] view to feed into the factory.
     */
    fun validate(
        template: AgentTemplate,
        parentTools: Set<String>,
        parentDepth: Int
    ): ResolvedSubAgentSpec {
        // 1. Template id sanity (the lookup should have already enforced this, but be defensive).
        require(template.id == templateId) {
            "Template id mismatch: spec asks for '$templateId', got '${template.id}'"
        }

        // 2. Input validation.
        for ((key, spec) in template.inputSchema) {
            val provided = inputs[key]
            when {
                spec.required && (provided == null || provided == "") -> {
                    if (spec.default == null) {
                        error("Missing required input '$key' for template '${template.id}'")
                    }
                }
                provided != null -> {
                    validateType(key, provided, spec.type)
                    spec.enum?.let { enumVals ->
                        val asString = provided.toString()
                        require(asString in enumVals) {
                            "Input '$key' value '$asString' not in enum $enumVals"
                        }
                    }
                }
            }
        }

        // 3. Override whitelist.
        for (key in overrides.keys) {
            require(key in template.allowedOverrides) {
                "Override '$key' is not in template '${template.id}' allowedOverrides=" +
                    template.allowedOverrides.sorted()
            }
        }

        // 4. Tool policy resolution (validates against parent registry).
        val resolvedTools = template.toolPolicy.resolve(parentTools)
        if (!template.toolPolicy.allowRecursion) {
            require("spawn_sub_agent" !in resolvedTools) {
                "Template '${template.id}' denies recursion but resolved tool set includes 'spawn_sub_agent'"
            }
        }

        // 5. Depth budget.
        val childDepth = parentDepth + 1
        val depthCeiling = template.budgetPolicy.maxDepth.coerceAtLeast(1)
        require(childDepth <= depthCeiling) {
            "Spawning template '${template.id}' would exceed maxDepth=" +
                "$depthCeiling (parent depth=$parentDepth)"
        }

        // 6. Skill override can only add, not remove.
        val combinedSkills = (template.requiredSkillIds + skillOverrides).distinct()

        // 7. Build the resolved view.
        val resolvedInputs = template.inputSchema.mapValues { (key, spec) ->
            inputs[key] ?: spec.default
        }
        val appliedOverrides = applyOverrides(template, overrides)

        return ResolvedSubAgentSpec(
            originalSpec = this,
            template = template,
            resolvedInputs = resolvedInputs,
            appliedOverrides = appliedOverrides,
            resolvedToolNames = resolvedTools,
            resolvedRequiredSkills = combinedSkills,
            childDepth = childDepth
        )
    }

    private fun validateType(key: String, value: Any?, expectedType: String) {
        val ok = when (expectedType) {
            "string" -> value is String
            "integer" -> value is Int || value is Long || value is Short || value is Byte
            "number" -> value is Number
            "boolean" -> value is Boolean
            "object", "array" -> true   // LLM-facing objects are loosely typed at this layer
            else -> true
        }
        require(ok) { "Input '$key' must be of type '$expectedType' (got ${value?.javaClass?.simpleName})" }
    }

    private fun applyOverrides(
        template: AgentTemplate,
        raw: Map<String, Any?>
    ): Map<String, Any?> {
        if (raw.isEmpty()) return emptyMap()
        val applied = LinkedHashMap<String, Any?>()
        for ((key, value) in raw) {
            // Each known key has a known domain; for unknown keys the
            // whitelist check in validate() has already rejected them.
            when (key) {
                "maxIterations" -> {
                    val n = (value as? Number)?.toInt()
                        ?: error("Override maxIterations must be an integer, got $value")
                    require(n in 1..1000) { "Override maxIterations must be in 1..1000, got $n" }
                    applied[key] = n
                }
                "maxToolCalls" -> {
                    val n = (value as? Number)?.toInt()
                        ?: error("Override maxToolCalls must be an integer, got $value")
                    require(n in 0..100_000) { "Override maxToolCalls must be in 0..100000, got $n" }
                    applied[key] = n
                }
                "maxWallClockMs" -> {
                    val n = (value as? Number)?.toLong()
                        ?: error("Override maxWallClockMs must be an integer, got $value")
                    require(n in 0L..TimeCaps.LONG_MS) { "Override maxWallClockMs out of range" }
                    applied[key] = n
                }
                "strategy.id" -> {
                    val id = StrategySpecId.fromString(value as? String)
                        ?: error("Override strategy.id must be one of react|plan_execute, got '$value'")
                    applied[key] = id
                }
                "allowRecursion" -> {
                    val b = value as? Boolean
                        ?: error("Override allowRecursion must be a boolean, got $value")
                    applied[key] = b
                }
                "lifecycle" -> {
                    val raw = (value as? String)?.lowercase()
                        ?: error("Override lifecycle must be a string, got $value")
                    val parsed = when (raw) {
                        "one_shot", "one-shot", "oneshot" -> Lifecycle.ONE_SHOT
                        "standby", "stand_by" -> Lifecycle.STANDBY
                        else -> error("Override lifecycle must be one_shot|standby, got '$value'")
                    }
                    applied[key] = parsed
                }
                "interaction.canSendToParent" -> {
                    val b = value as? Boolean
                        ?: error("Override interaction.canSendToParent must be a boolean")
                    applied[key] = b
                }
                "interaction.canSendToChildren" -> {
                    val b = value as? Boolean
                        ?: error("Override interaction.canSendToChildren must be a boolean")
                    applied[key] = b
                }
                "interaction.canPublishArtifacts" -> {
                    val b = value as? Boolean
                        ?: error("Override interaction.canPublishArtifacts must be a boolean")
                    applied[key] = b
                }
                else -> {
                    // Custom override keys are forwarded verbatim; the
                    // template author is responsible for any further
                    // validation in their factory.
                    applied[key] = value
                }
            }
        }
        return applied
    }
}

/**
 * Snapshot of a [SubAgentSpec] after it has been validated against a
 * template and the parent's runtime.  Carries the resolved view the
 * factory will see.  This type is intentionally immutable.
 */
data class ResolvedSubAgentSpec(
    val originalSpec: SubAgentSpec,
    val template: AgentTemplate,
    val resolvedInputs: Map<String, Any?>,
    val appliedOverrides: Map<String, Any?>,
    val resolvedToolNames: Set<String>,
    val resolvedRequiredSkills: List<String>,
    val childDepth: Int
) {
    val effectiveMaxIterations: Int
        get() = (appliedOverrides["maxIterations"] as? Int) ?: template.defaultMaxIterations

    val effectiveStrategy: StrategySpec
        get() {
            val idOverride = appliedOverrides["strategy.id"] as? StrategySpecId
            return if (idOverride != null) template.defaultStrategy.copy(id = idOverride)
            else template.defaultStrategy
        }

    val effectiveHarness: HarnessSpec?
        get() = template.defaultHarness

    val effectiveMaxToolCalls: Int
        get() = (appliedOverrides["maxToolCalls"] as? Int) ?: template.budgetPolicy.maxToolCalls

    val effectiveMaxWallClockMs: Long
        get() = (appliedOverrides["maxWallClockMs"] as? Long) ?: template.budgetPolicy.maxWallClockMs

    /**
     * Effective lifecycle after applying the parent's `lifecycle`
     * override (if any). Defaults to the template's
     * [AgentTemplate.interactionPolicy] [Lifecycle].
     */
    val effectiveLifecycle: Lifecycle
        get() = (appliedOverrides["lifecycle"] as? Lifecycle) ?: template.interactionPolicy.lifecycle

    /**
     * Effective cold-start flag after applying the parent's
     * `coldStart` override (if any).  Defaults to the template's
     * [InteractionPolicy.coldStart]; only meaningful when
     * [effectiveLifecycle] is STANDBY.
     */
    val effectiveColdStart: Boolean
        get() = (appliedOverrides["coldStart"] as? Boolean) ?: template.interactionPolicy.coldStart

    /**
     * Effective interaction policy after applying the parent's
     * `interaction.canSendTo*` and `interaction.canPublishArtifacts`
     * overrides. Legacy blocking fields remain whatever the template
     * declared (and the [InteractionPolicy] init rejects widening them).
     */
    val effectiveInteractionPolicy: InteractionPolicy
        get() {
            val base = template.interactionPolicy
            return base.copy(
                canSendToParent = (appliedOverrides["interaction.canSendToParent"] as? Boolean)
                    ?: base.canSendToParent,
                canSendToChildren = (appliedOverrides["interaction.canSendToChildren"] as? Boolean)
                    ?: base.canSendToChildren,
                canPublishArtifacts = (appliedOverrides["interaction.canPublishArtifacts"] as? Boolean)
                    ?: base.canPublishArtifacts,
                canBeCancelledBy = (appliedOverrides["interaction.canBeCancelledBy"] as? CancellationPolicy)
                    ?: base.canBeCancelledBy,
            )
        }
}

/** Internal helper: hard upper bounds for parameter validation. */
private object TimeCaps {
    const val LONG_MS: Long = 24L * 60 * 60 * 1000   // 1 day
}

// ============================================================
//  TemplateFactoryResolver — thin wrapper over AgentBuilderComponents
// ============================================================

/**
 * Backward-compatible thin wrapper around [AgentBuilderComponents] for
 * callers that prefer an instance-style API.  All state lives in the
 * global [AgentBuilderComponents] object, so multiple instances of this
 * class share the same registry.
 *
 * Only the built-in `ReAct` and `PlanExecute` strategies are
 * supported out of the box, and `HarnessSpec` resolution returns
 * [DefaultAgentHarness] unless a custom id was registered.
 */
class TemplateFactoryResolver {

    /** Register a custom harness by id.  The id is what the template
     *  declares in its [HarnessSpec.id].
     */
    fun registerHarness(id: String, harness: AgentHarness) {
        AgentBuilderComponents.registerHarness(id, harness)
    }

    fun unregisterHarness(id: String): AgentHarness? =
        AgentBuilderComponents.unregisterHarness(id)

    fun hasHarness(id: String): Boolean = AgentBuilderComponents.hasHarness(id)

    /**
     * Resolve a [StrategySpec] to a concrete [AgentStrategy].  Unknown
     * strategy ids throw — the parent agent must reference a known id.
     */
    fun resolveStrategy(spec: StrategySpec): AgentStrategy =
        AgentBuilderComponents.resolve(spec)

    /**
     * Resolve a [HarnessSpec] to a concrete [AgentHarness].  Returns the
     * default no-op harness when [spec] is null or references an
     * unknown id.
     */
    fun resolveHarness(spec: HarnessSpec?): AgentHarness =
        AgentBuilderComponents.resolveHarness(spec)
}
