package org.iotsplab.akiba.llm.tool

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.agent.AgentModule
import org.iotsplab.akiba.llm.agent.AgentRuntime
import org.iotsplab.akiba.llm.agent.AgentTemplate
import org.iotsplab.akiba.llm.agent.AgentTemplateRegistry
import org.iotsplab.akiba.llm.agent.AgentTranscriptWriter
import org.iotsplab.akiba.llm.agent.AkibaAgent
import org.iotsplab.akiba.llm.agent.ConversationRegistry
import org.iotsplab.akiba.llm.agent.JobHandle
import org.iotsplab.akiba.llm.agent.Lifecycle
import org.iotsplab.akiba.llm.agent.ModelContextLengthService
import org.iotsplab.akiba.llm.agent.RuntimeState
import org.iotsplab.akiba.llm.agent.ReActStrategy
import org.iotsplab.akiba.llm.agent.ResolvedSubAgentSpec
import org.iotsplab.akiba.llm.agent.SYSTEM_SESSION_UUID
import org.iotsplab.akiba.llm.agent.SubAgentFactoryContext
import org.iotsplab.akiba.llm.agent.SubAgentSpec
import org.iotsplab.akiba.llm.agent.TemplateFactoryResolver
import org.iotsplab.akiba.llm.agent.TemplateInputSpec
import org.iotsplab.akiba.llm.agent.spawnChildFromAgentProgrammatically
import org.iotsplab.akiba.llm.client.LLMClientFactory
import org.iotsplab.akiba.llm.memory.MemoryManager
import org.iotsplab.akiba.llm.memory.persistentChatMemory
import org.iotsplab.akiba.llm.skill.SkillManager

/**
 * Build the async `spawn_sub_agent` tool. The parent gets a
 * [JobHandle] ticket immediately so it can keep working (or
 * issue more spawns / mailbox messages) while the child runs
 * in a coroutine on the per-binary [AgentRuntime].
 *
 * Two paths share the same tool:
 *
 *  1. **Template path** — pass `templateId` (and optionally
 *     `inputs`, `overrides`, `name`, `skillOverrides`). The
 *     orchestrator validates the spec against the template's
 *     `inputSchema` and `allowedOverrides`, then runs the
 *     template's factory.
 *
 *  2. **Freeform path** — omit `templateId` and instead pass
 *     `systemPrompt` + `taskPrompt`. Optional `toolNames`
 *     (comma-separated; defaults to the parent's full registry)
 *     and `maxIterations` (default 5). The orchestrator builds
 *     a minimal [AkibaAgent] directly, with no template
 *     validation.
 *
 * The parent typically follows up with `await_condition` (using
 * a `StateChanged` condition on the child) or `await_multiple_children`
 * to wait for a specific state.
 */
fun SpawnSubAgentTool(
    parent: AgentModule,
    agentDbClient: AgentDatabaseClient,
    resolver: TemplateFactoryResolver = TemplateFactoryResolver(),
    /**
     * Optional override for the parent session of the spawned child.
     *
     * When null (default), the tool reads [AgentModule.agentSessionId]
     * — correct when the calling agent IS the root.  When non-null,
     * the value is used directly, which the framework's
     * `buildLayer1Agent` (and any other sub-agent factory) MUST set
     * so that grandchildren created by a layer-1 planner are
     * correctly parented under the layer-1, not the root.
     *
     * Without this parameter a `spawn_sub_agent` call from a
     * layer-1 STANDBY agent produces a child whose
     * `parentSessionId` column points to the root — the
     * per-context cap counters and the cascade-cancel walker both
     * see a flat tree instead of a two-level one, and the
     * children of different layer-1 agents share a single
     * per-root budget.
     */
    callerSessionId: String? = null,
    /**
     * Optional override for the SCOPE used to resolve `templateId`
     * (defaults to [callerSessionId] / the module session).
     *
     * Templates contributed by an [AgentModule] are registered under
     * the ROOT session's scope.  A layer-1 child that legitimately
     * needs to spawn a layer-2 template child (e.g. AndroidAnalyzer's
     * Java analyzer creating its dedicated native analyzer through
     * module-level tool code) would fail the scope check with
     * "template ... is not registered in this scope", because its own
     * session id is not a registered scope.  Module code can pass the
     * root session id here to resolve against the root scope while
     * keeping [callerSessionId] as the child's actual parent.
     *
     * Only pass this from trusted module code where the template id
     * is fixed by the module itself — never forward an LLM-chosen
     * scope.
     */
    templateScopeId: String? = null,
): Tool {
    val common = listOf(
        ToolParameter(
            "templateId", "string",
            "Template id from agent_builder_alternatives. If set, the template path is used " +
                "and `systemPrompt` / `taskPrompt` / `toolNames` / `maxIterations` are ignored. " +
                "If omitted, the freeform path is used and `systemPrompt` + `taskPrompt` are required.",
            required = false
        ),
        ToolParameter(
            "systemPrompt", "string",
            "[Freeform path] System prompt for the child agent.",
            required = false
        ),
        ToolParameter(
            "taskPrompt", "string",
            "[Freeform path] Task to send to the child agent.",
            required = false
        ),
        ToolParameter(
            "toolNames", "string",
            "[Freeform path] Comma-separated list of tool names the child may use. " +
                "Defaults to the parent's full registry.",
            required = false
        ),
        ToolParameter(
            "maxIterations", "integer",
            "[Freeform path] Maximum iterations for the child agent. Default 5.",
            required = false
        ),
        ToolParameter(
            "inputs", "object",
            "[Template path] Inputs declared by the template's inputSchema. " +
                "Omitted fields use defaults.",
            required = false
        ),
        ToolParameter(
            "overrides", "object",
            "[Template path] Optional overrides. Only fields in allowedOverrides are accepted.",
            required = false
        ),
        ToolParameter(
            "name", "string",
            "Optional human-readable name for the child, used in logs and transcript.",
            required = false
        ),
        ToolParameter(
            "skillOverrides", "string",
            "[Template path] Comma-separated list of additional skill ids.",
            required = false
        ),
        ToolParameter(
            "reuseSessionId", "string",
            "[Template path] Existing ERROR child session to reuse for a controlled retry. Internal scheduler use only.",
            required = false
        ),
        ToolParameter(
            "forceCompactBeforeRun", "boolean",
            "[Template path] When reusing a session, force one context compaction before rerunning the child.",
            required = false
        ),
    )
    return Tool(
        name = "spawn_sub_agent",
        description = buildString {
            appendLine("Spawn a child LLM agent ASYNCHRONOUSLY.")
            appendLine("Returns immediately with `{childSessionId, runtimeState, lifecycle, handle, mode}` —")
            appendLine("the actual run() happens in a background coroutine on the per-binary runtime.")
            appendLine("Use `await_condition` with a `StateChanged` condition on the child, or")
            appendLine("`await_multiple_children` to wait for it to reach a specific state")
            appendLine("(e.g. `closed` for one-shot, `standby` for parked).")
            appendLine()
            appendLine("Mode A — template (recommended for production): set `templateId` to a recipe")
            appendLine("returned by agent_builder_alternatives; the orchestrator validates inputs and")
            appendLine("overrides before the factory runs.")
            appendLine("Mode B — freeform (use sparingly): omit `templateId`; the orchestrator builds")
            appendLine("the child directly from `systemPrompt` / `taskPrompt` / `toolNames`.")
        },
        parameters = common,
    ) { args -> handleSpawn(args, parent, agentDbClient, resolver, callerSessionId, templateScopeId) }
}

private fun handleSpawn(
    args: Map<String, Any?>,
    parent: AgentModule,
    agentDbClient: AgentDatabaseClient,
    resolver: TemplateFactoryResolver,
    callerSessionId: String? = null,
    templateScopeId: String? = null,
): String {
    val mapper = jacksonObjectMapper()
    val templateId = (args["templateId"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
    val parentAgent = parent.agent ?: return "Error: parent agent not initialised"
    // When [callerSessionId] is provided (sub-agent calling through a
    // tool instance that was registered with its own session), use it.
    // Otherwise fall back to the module's session — the common case for
    // the root agent where caller == module owner.
    val parentSessionId = callerSessionId
        ?: parent.agentSessionId
        ?: return "Error: parent agent has no session id"

    if (templateId != null) {
        return spawnFromTemplate(
            args, templateId, parent, parentAgent, parentSessionId, agentDbClient, resolver, mapper,
            templateScopeId,
        )
    }
    return spawnFreeform(args, parent, parentAgent, parentSessionId, agentDbClient, mapper)
}

/**
 * Deliver a best-effort self-wake to the caller when `spawn_sub_agent`
 * fails before an [AgentRuntime] child exists.
 *
 * Runtime-managed children already notify their direct parent on
 * CLOSED/ERROR via [AgentRuntime].  However template input/rendering
 * failures can happen after the child DB session has been created but
 * BEFORE `runtime.spawn(...)` registers a [JobHandle].  In that gap
 * there is no runtime terminal transition, so without this helper the
 * parent (e.g. VulnDetector's Batch Linear Planner) may park forever
 * waiting for a child-completion message that can never arrive.
 */
private fun notifyParentOfPreSpawnFailure(
    agentDbClient: AgentDatabaseClient,
    parentSessionId: String,
    templateId: String?,
    childSessionId: String?,
    error: String,
    args: Map<String, Any?>,
): Long? = try {
    val body = buildString {
        appendLine("[sub-agent spawn failed]")
        appendLine("templateId: ${templateId ?: "<freeform>"}")
        if (childSessionId != null) appendLine("childSessionId: $childSessionId")
        appendLine("parentSessionId: $parentSessionId")
        appendLine("error: $error")
        appendLine()
        appendLine("This failure happened before the child was registered with AgentRuntime,")
        appendLine("so no normal child-terminal wake would be emitted. Treat this message")
        appendLine("as the failed child completion notification.")
        appendLine()
        appendLine("Original spawn_sub_agent arguments:")
        appendLine(jacksonObjectMapper().writeValueAsString(args))
        appendLine()
        appendLine("Action: fix the bad parameters (for linear_checker, use groupRef exactly")
        appendLine("as <groupingId>:<globalGroupIndex> from group_functions), then retry")
        appendLine("that child or continue with degraded coverage. Ack this message after handling.")
    }
    val msgId = agentDbClient.sendMailboxMessage(
        senderSessionId = "system",
        recipientSessionId = parentSessionId,
        kind = "error",
        subject = "sub-agent spawn failed: ${templateId ?: "freeform"}",
        body = body,
        priority = 10,
    )
    ConversationRegistry.register(
        messageId = msgId,
        senderSessionId = SYSTEM_SESSION_UUID,
        recipientSessionId = parentSessionId,
        inReplyTo = null,
    )
    msgId
} catch (_: Exception) {
    null
}

private fun markChildSessionError(
    agentDbClient: AgentDatabaseClient,
    childSessionId: String,
    reason: String,
) {
    try { agentDbClient.setRuntimeState(childSessionId, RuntimeState.ERROR.wire(), reason) } catch (_: Exception) {}
    try { agentDbClient.updateSession(childSessionId, status = "error") } catch (_: Exception) {}
}

private fun spawnFromTemplate(
    args: Map<String, Any?>,
    templateId: String,
    parent: AgentModule,
    parentAgent: AkibaAgent,
    parentSessionId: String,
    agentDbClient: AgentDatabaseClient,
    resolver: TemplateFactoryResolver,
    mapper: com.fasterxml.jackson.databind.ObjectMapper,
    templateScopeId: String? = null,
): String {
    val inputsNode = coerceArgsObject(args["inputs"])
    val overridesNode = coerceArgsObject(args["overrides"])
    val name = (args["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
    val skillOverridesRaw = (args["skillOverrides"] as? String)?.trim().orEmpty()
    val reuseSessionId = (args["reuseSessionId"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
    val forceCompactBeforeRun = when (val raw = args["forceCompactBeforeRun"]) {
        is Boolean -> raw
        is String -> raw.equals("true", ignoreCase = true)
        else -> false
    }

    // Template resolution scope: normally the caller's own session;
    // trusted module code may override it (e.g. a layer-1 child spawning
    // a module-registered layer-2 template resolves against the ROOT
    // scope where the module registered its templates).
    val template = AgentTemplateRegistry.resolveForScope(templateScopeId ?: parentSessionId, templateId)
        ?: run {
            val err = "template '$templateId' is not registered in this scope"
            val wakeId = notifyParentOfPreSpawnFailure(
                agentDbClient, parentSessionId, templateId, null, err, args
            )
            return mapper.writeValueAsString(mapOf(
                "status" to "error",
                "error" to err,
                "wakeMessageId" to wakeId,
                "hint" to "The failure was also sent to this agent's mailbox so it can wake/retry if it parks.",
            ))
        }

    val inputs = jsonNodeToMap(inputsNode)
    val overrides = jsonNodeToMap(overridesNode)
    val skillOverrides = skillOverridesRaw
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }

    val spec = SubAgentSpec(
        templateId = templateId,
        inputs = inputs,
        overrides = overrides,
        name = name,
        skillOverrides = skillOverrides,
    )
    val resolved = try {
        spec.validate(
            template = template,
            parentTools = parentAgent.toolRegistry.names(),
            parentDepth = parentAgentDepth(parent),
        )
    } catch (e: IllegalArgumentException) {
        val err = "invalid spec for template '$templateId': ${e.message}"
        val wakeId = notifyParentOfPreSpawnFailure(
            agentDbClient, parentSessionId, templateId, null, err, args
        )
        return mapper.writeValueAsString(mapOf(
            "status" to "error",
            "error" to err,
            "wakeMessageId" to wakeId,
            "hint" to "Fix the spawn_sub_agent inputs/overrides and retry. The failure was also sent to this agent's mailbox.",
        ))
    } catch (e: IllegalStateException) {
        val err = "invalid spec for template '$templateId': ${e.message}"
        val wakeId = notifyParentOfPreSpawnFailure(
            agentDbClient, parentSessionId, templateId, null, err, args
        )
        return mapper.writeValueAsString(mapOf(
            "status" to "error",
            "error" to err,
            "wakeMessageId" to wakeId,
            "hint" to "Fix the spawn_sub_agent inputs/overrides and retry. The failure was also sent to this agent's mailbox.",
        ))
    }

    val llmConfig = try {
        parent.resolveLLMConfigInternal()
    } catch (e: Exception) {
        return "Error: failed to resolve LLM config: ${e.message}"
    }

    return try {
        // NOTE: do NOT wrap this in `llmClient.use { ... }`.  The block
        // below only *registers* the child agent — `runtime.spawn` is
        // asynchronous and returns immediately, while the factory that
        // actually uses the client runs later in the child's own
        // coroutine.  If we used `use`, the block would exit before
        // the child even made its first LLM call, and `use` would
        // close the client (and the underlying JDK HttpClient's
        // connection pool) out from under the child.
        //
        // Ownership instead transfers to the child AkibaAgent — its
        // `close()` (invoked via `defaultTerminate()` / the runtime's
        // cleanup path) is what closes the client at end of life.
        val llmClient = LLMClientFactory.create(llmConfig)
        run {
            val childName = name ?: "${template.id}-${System.nanoTime()}"
            val childSessionId = try {
                if (reuseSessionId != null) {
                    val session = agentDbClient.getSession(reuseSessionId)
                    if (session.parentSessionId != parentSessionId) {
                        return@run "Error: reuseSessionId '$reuseSessionId' is not a direct child of caller session '$parentSessionId'"
                    }
                    val state = agentDbClient.getRuntimeState(reuseSessionId)?.runtimeState?.lowercase()
                    if (state != RuntimeState.ERROR.wire()) {
                        return@run "Error: reuseSessionId '$reuseSessionId' must be in runtime_state=error before retry; current=$state"
                    }
                    reuseSessionId
                } else {
                    agentDbClient.createSession(
                        sessionName = "sub-agent::$childName",
                        binaryId = parent.id,
                        moduleName = "${parent.javaClass.simpleName}::SubAgent(${template.id})",
                        modelName = llmConfig.modelName,
                        parentSessionId = parentSessionId,
                    )
                }
            } catch (e: Exception) {
                return@run "Error: failed to ${if (reuseSessionId != null) "reuse" else "create"} child session: ${e.message}"
            }

            val childMemory = persistentChatMemory(agentDbClient, childSessionId, maxMessages = 0)
            val childTranscript = AgentTranscriptWriter(agentDbClient, childSessionId)

            // [parentSessionId] is the caller's session (layer-1 when a
            // sub-agent calls this tool, or the root when the root calls
            // it).  [rootSessionId] must ALWAYS be the root's session so
            // the JobScheduler's per-root cap keys correctly and the
            // cascade-cancel walker can trace the tree from the real root.
            val rootSn = parent.agentSessionId ?: parentSessionId

            val factoryCtx = SubAgentFactoryContext(
                templateId = template.id,
                templateVersion = template.version,
                rootSessionId = rootSn,
                parentSessionId = parentSessionId,
                depth = resolved.childDepth,
                budget = template.budgetPolicy,
                toolPolicy = template.toolPolicy,
                inputs = resolved.resolvedInputs,
                appliedOverrides = resolved.appliedOverrides,
                resolvedToolNames = resolved.resolvedToolNames,
                llmClient = llmClient,
                childSessionId = childSessionId,
                childName = childName,
                template = template,
                resolvedSpec = resolved,
                agentDbClient = agentDbClient,
                memory = childMemory,
                transcript = childTranscript,
            )

            val taskPrompt = try {
                buildTemplateTaskPrompt(template, resolved, factoryCtx)
            } catch (e: Throwable) {
                val err = "failed to render task prompt for template '${template.id}': " +
                    "${e.javaClass.simpleName}: ${e.message}"
                markChildSessionError(agentDbClient, childSessionId, err)
                val wakeId = notifyParentOfPreSpawnFailure(
                    agentDbClient = agentDbClient,
                    parentSessionId = parentSessionId,
                    templateId = template.id,
                    childSessionId = childSessionId,
                    error = err,
                    args = args,
                )
                childTranscript.writeSessionStart(
                    moduleName = "${parent.javaClass.simpleName}::SubAgent(${template.id})",
                    binaryId = parent.id,
                    modelName = llmConfig.modelName,
                    strategy = resolved.effectiveStrategy.id.name.lowercase(),
                )
                childTranscript.writeUserMessage("[spawn_sub_agent failed before child run]\n$err")
                childTranscript.close()
                return@run mapper.writeValueAsString(mapOf(
                    "status" to "error",
                    "mode" to "template",
                    "templateId" to template.id,
                    "childSessionId" to childSessionId,
                    "parentSessionId" to parentSessionId,
                    "error" to err,
                    "wakeMessageId" to wakeId,
                    "hint" to "The child session was marked ERROR and an error wake was sent to the parent mailbox. Fix the bad inputs and retry.",
                ))
            }
            childTranscript.writeSessionStart(
                moduleName = "${parent.javaClass.simpleName}::SubAgent(${template.id})",
                binaryId = parent.id,
                modelName = llmConfig.modelName,
                strategy = resolved.effectiveStrategy.id.name.lowercase(),
            )
            // Defer the user-message transcript entry to when the
            // LLM actually sees it.  The template path defaults to
            // coldStart=true (it is the common case for STANDBY
            // children that park at spawn and wait for the first
            // mailbox message), so without this guard a STANDBY
            // child would have a "User" entry in its transcript
            // even though the LLM never received the taskPrompt.
            // ONE_SHOT templates (and any STANDBY template that
            // explicitly requests cold-start false in a future
            // extension) will call `agent.run(taskPrompt)`
            // immediately, so writing the user message now is
            // correct for those paths.
            if (resolved.effectiveLifecycle != Lifecycle.STANDBY) {
                childTranscript.writeUserMessage(taskPrompt)
            }

            val factoryRef: (suspend (JobHandle) -> AkibaAgent) = { handle ->
                try {
                    val agent = template.factory(factoryCtx)
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
                rootSessionId = rootSn,
                templateId = template.id,
                depth = resolved.childDepth,
                initialLifecycle = resolved.effectiveLifecycle,
                coldStart = resolved.effectiveColdStart,
                taskPrompt = taskPrompt,
                factory = factoryRef,
                forceCompactBeforeRun = reuseSessionId != null && forceCompactBeforeRun,
            )

            mapper.writeValueAsString(mapOf(
                "status" to "spawned",
                "mode" to "template",
                "childSessionId" to childSessionId,
                "parentSessionId" to parentSessionId,
                "templateId" to template.id,
                "lifecycle" to resolved.effectiveLifecycle.name.lowercase(),
                "runtimeState" to handle.state.value.wire(),
                "depth" to resolved.childDepth,
                "reusedSession" to (reuseSessionId != null),
                "forceCompactBeforeRun" to (reuseSessionId != null && forceCompactBeforeRun),
                "nextStep" to "Use await_multiple_children or await_condition to wait, " +
                    "or send_agent_message to push follow-up work.",
            ))
        }
    } catch (e: Exception) {
        "Error spawning sub-agent from template '$templateId': ${e.message}"
    }
}

private fun spawnFreeform(
    args: Map<String, Any?>,
    parent: AgentModule,
    parentAgent: AkibaAgent,
    parentSessionId: String,
    agentDbClient: AgentDatabaseClient,
    mapper: com.fasterxml.jackson.databind.ObjectMapper,
): String {
    val systemPrompt = (args["systemPrompt"] as? String)?.trim().orEmpty()
    if (systemPrompt.isBlank())
        return "Error: freeform path requires 'systemPrompt'"
    val taskPrompt = (args["taskPrompt"] as? String)?.trim().orEmpty()
    if (taskPrompt.isBlank())
        return "Error: freeform path requires 'taskPrompt'"
    val toolNamesStr = (args["toolNames"] as? String)?.trim().orEmpty()
    val maxIter = (args["maxIterations"] as? Number)?.toInt()?.coerceIn(1, 1000) ?: 5
    val name = (args["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() }

    val llmConfig = try {
        parent.publicLLMConfig()
    } catch (e: Exception) {
        return "Error: failed to resolve LLM config: ${e.message}"
    }

    // Build the child's tool registry: subset of parent's, or all
    // if the caller did not narrow the list.
    val childRegistry = ToolRegistry()
    if (toolNamesStr.isBlank()) {
        parentAgent.toolRegistry.all().forEach { childRegistry.register(it) }
    } else {
        val names = toolNamesStr.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val unknown = names - parentAgent.toolRegistry.names()
        if (unknown.isNotEmpty()) {
            return "Error: toolNames references tool(s) not in parent registry: $unknown"
        }
        for (tool in parentAgent.toolRegistry.all()) {
            if (tool.name in names) childRegistry.register(tool)
        }
    }

    return try {
        val result = spawnChildFromAgentProgrammatically(
            parent = parent,
            agentDbClient = agentDbClient,
            agentFactory = { handle ->
                val client = LLMClientFactory.create(llmConfig)
                AkibaAgent(
                    client = client,
                    systemPrompt = systemPrompt,
                    memory = persistentChatMemory(agentDbClient, handle.sessionId, maxMessages = 0),
                    memoryManager = MemoryManager(agentDbClient, handle.sessionId, parent.id),
                    toolRegistry = childRegistry,
                    maxIterations = maxIter,
                    sessionId = handle.sessionId,
                    enrichSystemPromptWithMemory = true,
                    auditToolCalls = true,
                    strategy = ReActStrategy(),
                    contextLength = ModelContextLengthService.getContextLength(
                        client.config.provider, client.config.modelName,
                    ),
                    lifecycle = Lifecycle.ONE_SHOT,
                    runtimeHandle = handle,
                )
            },
            parentSessionId = parentSessionId,
            // Free-form path: [parentSessionId] is the caller's
            // session; [rootSessionId] must be the real root so
            // per-root caps and cascade cancel work.  Derive from
            // the owning module (always the root-level AgentModule).
            rootSessionId = parent.agentSessionId ?: parentSessionId,
            depth = 1,
            lifecycle = Lifecycle.ONE_SHOT,
            taskPrompt = taskPrompt,
            name = name,
        )
        mapper.writeValueAsString(mapOf(
            "status" to "spawned",
            "mode" to "freeform",
            "childSessionId" to result.childSessionId,
            "parentSessionId" to parentSessionId,
            "templateId" to null,
            "lifecycle" to Lifecycle.ONE_SHOT.name.lowercase(),
            "runtimeState" to result.handle.state.value.wire(),
            "depth" to 1,
            "nextStep" to "Use await_multiple_children or await_condition to wait.",
        ))
    } catch (e: Exception) {
        "Error spawning freeform sub-agent: ${e.message}"
    }
}

// ============================================================
//  Helpers shared with the template path
// ============================================================

private fun parentAgentDepth(parent: AgentModule): Int = parent.agent?.let { 1 } ?: 1

private fun coerceArgsObject(raw: Any?): JsonNode = when (raw) {
    is JsonNode -> raw
    is Map<*, *> -> jacksonObjectMapper().valueToTree(raw)
    null -> jacksonObjectMapper().createObjectNode()
    else -> jacksonObjectMapper().createObjectNode()
}

private fun jsonNodeToMap(node: JsonNode): Map<String, Any?> {
    if (!node.isObject) return emptyMap()
    val out = LinkedHashMap<String, Any?>()
    node.fields().forEach { (k, v) ->
        out[k] = if (v.isValueNode || v.isNull) {
            when {
                v.isNull -> null
                v.isTextual -> v.asText()
                v.isInt -> v.asInt()
                v.isLong -> v.asLong()
                v.isDouble -> v.asDouble()
                v.isBoolean -> v.asBoolean()
                else -> v.asText()
            }
        } else v
    }
    return out
}

private fun buildTemplateTaskPrompt(
    template: AgentTemplate,
    resolved: ResolvedSubAgentSpec,
    ctx: SubAgentFactoryContext,
): String {
    val sb = StringBuilder()
    if (resolved.resolvedRequiredSkills.isNotEmpty()) {
        sb.appendLine("## Required Skill Content (auto-loaded)")
        for (skillId in resolved.resolvedRequiredSkills) {
            val readResult = try {
                SkillManager.readSkill(ctx.parentSessionId ?: "", skillId, maxChars = 8_000)
            } catch (_: Exception) { null }
            if (readResult != null) {
                sb.appendLine()
                sb.appendLine("--- skill:$skillId ---")
                sb.appendLine(readResult.content)
                sb.appendLine("--- end skill:$skillId ---")
            } else {
                sb.appendLine("(skill '$skillId' not found; skipped)")
            }
        }
        sb.appendLine()
    }
    sb.appendLine("## Template")
    sb.appendLine("id: ${template.id} v${template.version}")
    sb.appendLine("name: ${template.name}")
    sb.appendLine()
    sb.appendLine("## Inputs")
    for ((k, v) in resolved.resolvedInputs) {
        val spec = template.inputSchema[k]
        val display = if (spec?.secret == true) "<redacted>" else v?.toString().orEmpty()
        sb.appendLine("- $k: $display")
    }
    sb.appendLine()
    val customTask = try {
        template.taskPromptRenderer?.invoke(redactSecrets(resolved.resolvedInputs, template.inputSchema))
    } catch (_: Throwable) { null }
    if (!customTask.isNullOrBlank()) {
        sb.appendLine("## Concrete task")
        sb.appendLine(customTask.trimEnd())
        sb.appendLine()
    } else if (resolved.resolvedInputs.isNotEmpty()) {
        sb.appendLine("## Concrete task")
        sb.appendLine(
            "Apply the inputs above as the concrete task. " +
                "Inputs are not metadata — they specify the exact scope of this dispatch. " +
                "Do not start by re-discovering them; start by acting on them."
        )
        sb.appendLine()
    }
    sb.appendLine("## Task")
    val taskInput = resolved.resolvedInputs["task"] as? String
    if (!taskInput.isNullOrBlank()) sb.appendLine(taskInput)
    return sb.toString()
}

private fun redactSecrets(
    inputs: Map<String, Any?>,
    schema: Map<String, TemplateInputSpec>
): Map<String, Any?> {
    if (inputs.isEmpty()) return inputs
    val out = LinkedHashMap<String, Any?>(inputs.size)
    for ((k, v) in inputs) {
        out[k] = if (schema[k]?.secret == true) "<redacted>" else v
    }
    return out
}

// ============================================================
//  Programmatic spawn — used by modules to pre-create children
// ============================================================
//
// Same as the `spawnFromTemplate` tool path but invoked from the
// host (Kotlin) side rather than from a tool call. Intended for
// `startProcess`-time setup of a fixed agent tree: a parent module
// that always wants a known set of children alive before the root
// LLM starts running can spawn them here in code, so the LLM only
// sees `send_agent_message` to interact with them and cannot
// accidentally spawn new ones.
//
// All safety checks that the tool path enforces (template
// validation, budget / tool policy resolution, depth enforcement)
// are applied identically here.

/**
 * Result of a programmatic template spawn. Mirrors the JSON the
 * `spawn_sub_agent` tool returns but typed for host-side callers.
 */
data class ProgrammaticSpawnResult(
    val handle: JobHandle,
    val childSessionId: String,
    val childName: String,
    val templateId: String,
    val lifecycle: Lifecycle,
    val depth: Int,
    val resolvedSpec: ResolvedSubAgentSpec,
)

/**
 * Spawn a child agent from a registered template, exactly as the
 * `spawn_sub_agent` tool would, but driven by host code (typically
 * a module's `startProcess` for fixed-orchestration setups). Throws
 * on validation failure or runtime error; the caller is expected to
 * surface the message in logs and decide whether to abort startup.
 *
 * @param parent        the calling module (provides LLM config, binary id,
 *                       scope for template lookup).
 * @param template      the resolved template to spawn from.
 * @param parentSessionId  the direct parent's session id. For top-level
 *                          root agents this is the same as
 *                          [rootSessionId].
 * @param rootSessionId    the root session id of the tree this child
 *                          belongs to. MUST be the actual root — the
 *                          scheduler's per-root cap keys on this.
 * @param depth         depth in the tree (1 for direct child of root).
 * @param inputs        template `inputs` map (will be validated).
 * @param overrides     template `overrides` map (will be filtered).
 * @param name          optional cosmetic name. Defaults to
 *                       `"${template.id}-<nanos>"`.
 * @return              the spawned [JobHandle] plus the resolved
 *                       [ResolvedSubAgentSpec] for downstream use.
 */
@Throws(IllegalArgumentException::class, IllegalStateException::class)
fun spawnChildFromTemplateProgrammatically(
    parent: AgentModule,
    agentDbClient: AgentDatabaseClient,
    template: AgentTemplate,
    parentSessionId: String,
    rootSessionId: String,
    depth: Int,
    inputs: Map<String, Any?> = emptyMap(),
    overrides: Map<String, Any?> = emptyMap(),
    name: String? = null,
    /**
     * Optional override for the tool-inheritance check. When non-null,
     * the child's `ToolPolicy.allow` is validated against this set
     * instead of the parent's actual tool registry.  Use this when
     * the host knows the child needs tools the parent itself does
     * NOT have (e.g. the parent has no `spawn_sub_agent` but the
     * child is supposed to be a planner that can dispatch).
     */
    parentToolsOverride: Set<String>? = null,
): ProgrammaticSpawnResult {
    val parentAgent = parent.agent
        ?: throw IllegalStateException("parent agent is not initialised")
    val mapper = jacksonObjectMapper()

    val spec = SubAgentSpec(
        templateId = template.id,
        inputs = inputs,
        overrides = overrides,
        name = name,
    )
    val effectiveParentTools = parentToolsOverride
        ?: parentAgent.toolRegistry.names()
    val resolved = spec.validate(
        template = template,
        parentTools = effectiveParentTools,
        parentDepth = depth - 1,  // depth-1 == this child's depth; parent is the previous level
    )

    val llmConfig = parent.resolveLLMConfigInternal()
    val llmClient = LLMClientFactory.create(llmConfig)

    // NOTE: see the matching comment in `spawnFromTemplate` — do NOT
    // wrap this in `llmClient.use { ... }`.  `runtime.spawn` is
    // asynchronous, the child uses the client later in its own
    // coroutine, and `use` would close the client's JDK HttpClient
    // before the child even makes its first LLM call.  Ownership
    // transfers to the child AkibaAgent whose `close()` (invoked
    // via `defaultTerminate()` / the runtime's cleanup path) closes
    // the client at end of life.
    return run {
        val childName = name ?: "${template.id}-${System.nanoTime()}"
        val childSessionId = agentDbClient.createSession(
            sessionName = "sub-agent::$childName",
            binaryId = parent.id,
            moduleName = "${parent.javaClass.simpleName}::SubAgent(${template.id})",
            modelName = llmConfig.modelName,
            parentSessionId = parentSessionId,
        )

        val childMemory = persistentChatMemory(agentDbClient, childSessionId, maxMessages = 0)
        val childTranscript = AgentTranscriptWriter(agentDbClient, childSessionId)

        val factoryCtx = SubAgentFactoryContext(
            templateId = template.id,
            templateVersion = template.version,
            rootSessionId = rootSessionId,
            parentSessionId = parentSessionId,
            depth = depth,
            budget = template.budgetPolicy,
            toolPolicy = template.toolPolicy,
            inputs = resolved.resolvedInputs,
            appliedOverrides = resolved.appliedOverrides,
            resolvedToolNames = resolved.resolvedTools,
            llmClient = llmClient,
            childSessionId = childSessionId,
            childName = childName,
            template = template,
            resolvedSpec = resolved,
            agentDbClient = agentDbClient,
            memory = childMemory,
            transcript = childTranscript,
        )

        val taskPrompt = buildTemplateTaskPrompt(template, resolved, factoryCtx)
        childTranscript.writeSessionStart(
            moduleName = "${parent.javaClass.simpleName}::SubAgent(${template.id})",
            binaryId = parent.id,
            modelName = llmConfig.modelName,
            strategy = resolved.effectiveStrategy.id.name.lowercase(),
        )
        // Defer the user-message transcript entry to when the LLM
        // actually sees it.  Same rationale as the tool-path
        // `spawnFromTemplate` above: STANDBY children park at
        // spawn with coldStart=true and never call agent.run()
        // on the primary taskPrompt, so writing it to the
        // transcript now would create a "User" entry the LLM
        // never observed.  ONE_SHOT templates (and STANDBY
        // children that opt out of cold-start in a future
        // extension) will call agent.run(taskPrompt)
        // immediately, so the entry is correct for them.
        if (resolved.effectiveLifecycle != Lifecycle.STANDBY) {
            childTranscript.writeUserMessage(taskPrompt)
        }

        val factoryRef: (suspend (JobHandle) -> AkibaAgent) = { handle ->
            try {
                val agent = template.factory(factoryCtx)
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
            templateId = template.id,
            depth = depth,
            initialLifecycle = resolved.effectiveLifecycle,
            taskPrompt = taskPrompt,
            factory = factoryRef,
        )
        ProgrammaticSpawnResult(
            handle = handle,
            childSessionId = childSessionId,
            childName = childName,
            templateId = template.id,
            lifecycle = resolved.effectiveLifecycle,
            depth = depth,
            resolvedSpec = resolved,
        )
    }
}

// `resolvedTools` is a convenience accessor mirroring the private
// `resolvedToolNames` field of `ResolvedSubAgentSpec`. The struct
// itself exposes `resolvedToolNames`; the alias here just reads
// better at the call site and avoids the `...names` suffix that
// collides with the framework's own nomenclature.
private val ResolvedSubAgentSpec.resolvedTools: Set<String>
    get() = this.resolvedToolNames
