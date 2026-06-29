package org.iotsplab.akiba.llm.agent

// ============================================================
//  ProgrammaticSubAgentSpec — declarative description of a child
//  agent built by the caller (no AgentTemplate involved)
// ============================================================
//
// Authored inside the [akibaAgent] DSL via `subAgent { ... }` and
// consumed by [AgentModule.spawnConfiguredSubAgents] to
// programmatically spawn the children BEFORE the parent runs its
// first turn.  The alternative to this is the template-based
// `spawn_sub_agent` tool (`AgentTemplate` +
// `AgentTemplateRegistry`); `ProgrammaticSubAgentSpec` is for
// fixed-orchestration setups where the module knows its children at
// construction time and wants to embed their session ids in its own
// first-turn prompt.
//
// Note the naming: there is also a [org.iotsplab.akiba.llm.agent.SubAgentSpec]
// in `AgentTemplateRegistry.kt` which represents a parent-issued
// request to spawn a child from a registered template.  That is a
// different concept — it carries `templateId` / `inputs` /
// `overrides`; this one carries the agent factory directly.

/**
 * Declarative description of a child agent that the owning
 * [AkibaAgent] wants pre-created at startup.  Built by
 * [ProgrammaticSubAgentBuilder] from the [AgentBuilder.subAgent] DSL
 * block.
 *
 * @param name         cosmetic child name.  Used in logs, transcript,
 *                     and as the suggested `moduleName` suffix.
 * @param depth        depth in the tree (1 for a direct child of
 *                     the root, 2 for a grandchild, ...).
 * @param lifecycle    [Lifecycle] the child runs with. STANDBY
 *                     children park after the first run and wait
 *                     for mailbox dispatch; ONE_SHOT children
 *                     terminate after a single `run()`.
 * @param taskPrompt   initial user message — same value is used for
 *                     both the transcript and the first `agent.run()`.
 * @param agentFactory builds the [AkibaAgent].  Called once with
 *                     the freshly-allocated [JobHandle] so the child
 *                     can stash it via `agent.runtimeHandle = handle`.
 */
data class ProgrammaticSubAgentSpec(
    val name: String,
    val depth: Int = 1,
    val lifecycle: Lifecycle = Lifecycle.STANDBY,
    val taskPrompt: String,
    val agentFactory: (JobHandle) -> AkibaAgent,
)

/**
 * DSL builder for a single [ProgrammaticSubAgentSpec].  Used inside
 * [AgentBuilder.subAgent].  The `buildAgent { ... }` block is
 * required — every sub-agent must be constructible from a
 * [JobHandle] and an arbitrary caller-supplied closure.
 *
 * ```kotlin
 * subAgent("batch_linear_planner") {
 *     depth = 1
 *     lifecycle(Lifecycle.STANDBY)
 *     taskPrompt = "Read your inbox and react."
 *     buildAgent { handle -> buildBatchLinearPlannerAgent(handle.sessionId) }
 * }
 * ```
 */
class ProgrammaticSubAgentBuilder(private val name: String) {
    var depth: Int = 1
    var lifecycle: Lifecycle = Lifecycle.STANDBY
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
        taskPrompt = taskPrompt,
        agentFactory = requireNotNull(agentFactory) {
            "subAgent('$name'): buildAgent { ... } is required"
        },
    )
}
