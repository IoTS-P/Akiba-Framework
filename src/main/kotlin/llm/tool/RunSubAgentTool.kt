package org.iotsplab.akiba.llm.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.agent.*
import org.iotsplab.akiba.llm.client.LLMClientFactory
import org.iotsplab.akiba.llm.memory.MemoryManager
import org.iotsplab.akiba.llm.memory.inMemoryChatMemory
import org.iotsplab.akiba.llm.memory.persistentChatMemory

/**
 * Create a tool that spawns a child LLM agent with its own prompt
 * and tool set, runs it to completion, and returns the child's
 * final answer.
 *
 * This enables multi-agent collaboration: a "planner" agent can
 * delegate research or analysis sub-tasks to specialized child
 * agents. Each child gets its own conversation history and memory,
 * but shares the parent's LLM configuration.
 *
 * The child agent's tools default to the parent's tool registry.
 * Override via the `toolNames` parameter to select a subset.
 */
fun RunSubAgentTool(parent: AgentModule): Tool = Tool(
    name = "run_sub_agent",
    description = buildString {
        appendLine("Spawn a child LLM agent to handle a sub-task independently.")
        appendLine("The child agent gets its own conversation history and tools.")
        appendLine("Specify a system prompt and a task prompt for the child.")
        appendLine("You can optionally specify which tools the child may use.")
        appendLine("The child's final answer is returned as a string.")
    },
    parameters = listOf(
        ToolParameter(
            "systemPrompt", "string",
            "System prompt for the child agent, defining its role and behavior.",
            required = true
        ),
        ToolParameter(
            "taskPrompt", "string",
            "The task/question to send to the child agent.",
            required = true
        ),
        ToolParameter(
            "toolNames", "string",
            "Comma-separated list of tool names the child may use. " +
                "If omitted, the child inherits all tools from the parent agent.",
            required = false
        ),
        ToolParameter(
            "maxIterations", "integer",
            "Maximum iterations for the child agent. Default: 5.",
            required = false
        )
    )
) { args ->
    val systemPrompt = args["systemPrompt"] as? String
        ?: return@Tool "Error: 'systemPrompt' parameter is required"
    val taskPrompt = args["taskPrompt"] as? String
        ?: return@Tool "Error: 'taskPrompt' parameter is required"
    val toolNamesStr = args["toolNames"] as? String
    val maxIter = (args["maxIterations"] as? Number)?.toInt() ?: 5

    val mapper = jacksonObjectMapper()

    try {
        val llmConfig = parent.resolveLLMConfigInternal()
        val llmClient = LLMClientFactory.create(llmConfig)

        llmClient.use { client ->
            val childRegistry = ToolRegistry()
            val parentAgent = parent.agent
                ?: return@Tool "Error: Parent agent not initialized yet"

            if (toolNamesStr.isNullOrBlank()) {
                parentAgent.toolRegistry.all().forEach { childRegistry.register(it) }
            } else {
                val names = toolNamesStr.split(",").map { it.trim() }.toSet()
                for (tool in parentAgent.toolRegistry.all()) {
                    if (tool.name in names) childRegistry.register(tool)
                }
            }

            val childSessionId = try {
                AgentDatabaseClient.createSession(
                    sessionName = "sub-agent-${System.nanoTime()}",
                    binaryId = parent.id,
                    moduleName = "${parent.javaClass.simpleName}::SubAgent",
                    modelName = llmConfig.modelName
                )
            } catch (_: Exception) { null }

            val childMemory = if (childSessionId != null)
                persistentChatMemory(childSessionId, maxMessages = 0)
            else
                inMemoryChatMemory()

            val childMemoryManager = childSessionId?.let {
                MemoryManager(it, parent.id)
            }

            val childAgent = AkibaAgent(
                client = client,
                systemPrompt = systemPrompt,
                memory = childMemory,
                memoryManager = childMemoryManager,
                toolRegistry = childRegistry,
                maxIterations = maxIter,
                sessionId = childSessionId,
                enrichSystemPromptWithMemory = true,
                auditToolCalls = true,
                strategy = ReActStrategy()
            )

            val result = childAgent.run(taskPrompt)

            if (childSessionId != null) {
                try {
                    val status = when (result.stopReason) {
                        StopReason.COMPLETED -> "completed"
                        StopReason.MAX_ITERATIONS -> "max_iterations"
                        StopReason.ERROR -> "error"
                    }
                    AgentDatabaseClient.updateSession(childSessionId, status = status)
                } catch (_: Exception) {}
            }

            mapper.writeValueAsString(mapOf(
                "output" to result.output,
                "iterations" to result.iterations,
                "toolCalls" to result.toolCallsMade,
                "stopReason" to result.stopReason.name
            ))
        }
    } catch (e: Exception) {
        "Error running sub-agent: ${e.message}"
    }
}
