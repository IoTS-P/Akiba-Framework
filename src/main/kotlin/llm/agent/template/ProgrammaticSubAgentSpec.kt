package org.iotsplab.akiba.llm.agent.template

import org.iotsplab.akiba.llm.agent.core.AkibaAgent
import org.iotsplab.akiba.llm.agent.runtime.JobHandle

// ============================================================
//  ProgrammaticSubAgentSpec — declarative description of a child
//  agent built by the caller (no AgentTemplate involved)
// ============================================================
//
// Authored inside the [akibaAgent] DSL via `subAgent { ... }` and consumed by
// [AgentModule.spawnConfiguredSubAgents] to spawn children before the parent's
// first turn. Unlike [SubAgentSpec] (a parent-issued request carrying
// `templateId` / `inputs` / `overrides`), this carries the agent factory
// directly — for fixed-orchestration modules that know their children at
// construction time.

/**
 * Declarative description of a child agent the owning [AkibaAgent] wants
 * pre-created at startup, built from the [AgentBuilder.subAgent] DSL block.
 *
 * @param coldStart When true and [lifecycle] is STANDBY, the child skips its
 *   initial `agent.run()` and parks directly, so no LLM round-trip happens at
 *   spawn; the dispatcher wakes it later. Ignored for ONE_SHOT.
 * @param agentFactory Called once with the freshly-allocated [JobHandle] so
 *   the child can stash it via `agent.runtimeHandle = handle`.
 */
data class ProgrammaticSubAgentSpec(
    val name: String,
    val depth: Int = 1,
    val lifecycle: Lifecycle = Lifecycle.STANDBY,
    val coldStart: Boolean = true,
    val taskPrompt: String,
    val agentFactory: (JobHandle) -> AkibaAgent,
)

/**
 * DSL builder for a single [ProgrammaticSubAgentSpec], used inside
 * [AgentBuilder.subAgent]. The `buildAgent { ... }` block is required.
 * ```
 * subAgent("batch_linear_planner") {
 *     taskPrompt = "Read your inbox and react."
 *     buildAgent { handle -> buildBatchLinearPlannerAgent(handle.sessionId) }
 * }
 * ```
 */
class ProgrammaticSubAgentBuilder(private val name: String) {
    var depth: Int = 1
    var lifecycle: Lifecycle = Lifecycle.STANDBY
    /** When true (default) the child skips its initial LLM call and parks. */
    var coldStart: Boolean = true
    var taskPrompt: String = ""
    private var agentFactory: ((JobHandle) -> AkibaAgent)? = null

    /** Set the [Lifecycle] the child runs with. */
    fun lifecycle(value: Lifecycle) {
        lifecycle = value
    }

    /**
     * Provide the [AkibaAgent] factory.  Receives the freshly-allocated
     * [JobHandle] so the child can wire its `runtimeHandle` and
     * coroutine bookkeeping.
     */
    fun buildAgent(block: (JobHandle) -> AkibaAgent) {
        agentFactory = block
    }

    internal fun build(): ProgrammaticSubAgentSpec = ProgrammaticSubAgentSpec(
        name = name,
        depth = depth,
        lifecycle = lifecycle,
        coldStart = coldStart,
        taskPrompt = taskPrompt,
        agentFactory = requireNotNull(agentFactory) {
            "subAgent('$name'): buildAgent { ... } is required"
        },
    )
}
