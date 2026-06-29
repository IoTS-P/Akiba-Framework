package org.iotsplab.akiba.llm.agent

import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.client.LLMClientFactory
import org.iotsplab.akiba.llm.memory.persistentChatMemory

// ============================================================
//  Programmatic spawn — bring-your-own AkibaAgent (no template)
// ============================================================
//
// Template-free sibling of
// `org.iotsplab.akiba.llm.tool.spawnChildFromTemplateProgrammatically`.
// Lives next to the template definitions in `llm/agent/` so any
// caller that already imports the template DSL can pick the
// programmatic variant up without reaching into `llm/tool/`.

/**
 * Outcome of [spawnChildFromAgentProgrammatically].  The caller gets
 * the [JobHandle] for downstream wiring (mailbox, await, cancel) and
 * the resolved child session id.
 */
data class ProgrammaticAgentSpawnResult(
    val handle: JobHandle,
    val childSessionId: String,
    val childName: String,
    val depth: Int,
    val lifecycle: Lifecycle,
)

/**
 * Spawn a child agent that is constructed directly by the caller,
 * bypassing [AgentTemplate] / [SubAgentSpec] validation.  Use this
 * when a module wants a fixed-orchestration setup whose agents are
 * built by code (e.g. `VulnDetector` pre-creates its 3 STANDBY
 * layer-1 children in [AgentModule.onBeforeFirstRun]).
 *
 * The caller owns everything the template would normally provide:
 * tool list, harness, system prompt, max iterations, lifecycle, and
 * the [AkibaAgent] instance itself.  The framework still creates the
 * child session, persistent memory, transcript, and registers the
 * coroutine with the per-binary [AgentRuntime].
 *
 * @param parent        the calling module (provides LLM config,
 *                       binary id, scope).
 * @param agentFactory  builds the [AkibaAgent]. Called once with
 *                       the freshly-allocated [JobHandle] so the
 *                       child can stash it via
 *                       `agent.runtimeHandle = handle`.
 * @param parentSessionId  the direct parent's session id.  For
 *                          top-level root agents this is the same
 *                          as [rootSessionId].
 * @param rootSessionId    root session id of the tree the child
 *                          belongs to.
 * @param depth         depth in the tree (1 for direct child of root).
 * @param lifecycle     the [Lifecycle] the child runs with.
 * @param taskPrompt    initial user message; the same value is
 *                       used for both the transcript and
 *                       `agent.run()`.
 * @param name          cosmetic child name; defaults to
 *                       `"sub-agent-${nanos}"`.
 */
@Throws(IllegalStateException::class)
fun spawnChildFromAgentProgrammatically(
    parent: AgentModule,
    agentDbClient: AgentDatabaseClient,
    agentFactory: (JobHandle) -> AkibaAgent,
    parentSessionId: String,
    rootSessionId: String,
    depth: Int,
    lifecycle: Lifecycle = Lifecycle.ONE_SHOT,
    taskPrompt: String,
    name: String? = null,
): ProgrammaticAgentSpawnResult {
    parent.agent ?: throw IllegalStateException("parent agent is not initialised")
    val llmConfig = parent.publicLLMConfig()

    return LLMClientFactory.create(llmConfig).use { _ ->
        val childName = name ?: "sub-agent-${System.nanoTime()}"
        val childSessionId = agentDbClient.createSession(
            sessionName = "sub-agent::$childName",
            binaryId = parent.id,
            moduleName = "${parent.javaClass.simpleName}::SubAgent(adhoc)",
            modelName = llmConfig.modelName,
            parentSessionId = parentSessionId,
        )
        val childMemory = persistentChatMemory(agentDbClient, childSessionId, maxMessages = 0)
        val childTranscript = AgentTranscriptWriter(agentDbClient, childSessionId)
        childTranscript.writeSessionStart(
            moduleName = "${parent.javaClass.simpleName}::SubAgent(adhoc)",
            binaryId = parent.id,
            modelName = llmConfig.modelName,
            strategy = "react",
        )
        childTranscript.writeUserMessage(taskPrompt)

        val factoryRef: (suspend (JobHandle) -> AkibaAgent) = { handle ->
            try {
                val agent = agentFactory(handle)
                agent.runtimeHandle = handle
                val sp = try { agent.systemPrompt.orEmpty() } catch (_: Throwable) { "" }
                if (sp.isNotBlank()) childTranscript.writeSystemPrompt(sp)
                agent
            } catch (e: Throwable) {
                childTranscript.close()
                throw e
            }
        }

        val runtime = AgentRuntime.forBinary(parent.id, agentDbClient)
        val handle = runtime.spawn(
            parentSessionId = parentSessionId,
            childSessionId = childSessionId,
            rootSessionId = rootSessionId,
            templateId = null,
            depth = depth,
            initialLifecycle = lifecycle,
            taskPrompt = taskPrompt,
            factory = factoryRef,
        )
        ProgrammaticAgentSpawnResult(
            handle = handle,
            childSessionId = childSessionId,
            childName = childName,
            depth = depth,
            lifecycle = lifecycle,
        )
    }
}
