package org.iotsplab.akiba.llm.agent

import ghidra.program.model.listing.Program
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.client.LLMClientFactory
import org.iotsplab.akiba.llm.client.LLMConfig
import org.iotsplab.akiba.llm.memory.MemoryManager
import org.iotsplab.akiba.llm.memory.inMemoryChatMemory
import org.iotsplab.akiba.llm.memory.persistentChatMemory
import org.iotsplab.akiba.managers.ConfigManager
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.llm.tool.BuiltInTools
import org.iotsplab.akiba.utils.*

// ============================================================
//  Annotations for Agent Module DSL
// ============================================================

/**
 * Declare the agent's system prompt inline on the class.
 *
 * For longer prompts, prefer passing the prompt programmatically
 * via [AgentModule.agentSystemPrompt].
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class WithAgentSystemPrompt(val prompt: String)

/**
 * Declare the maximum ReAct iterations for the agent.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class WithAgentMaxIterations(val iterations: Int = 10)

// ============================================================
//  AgentModule — one-click deployable agent module
// ============================================================

/**
 * An [AkibaModule] that wraps an LLM agent for one-click deployment.
 *
 * Subclasses define their agent's behavior by overriding the
 * configuration methods and using annotations.  The module
 * automatically:
 *
 * 1. Creates a session in the agent database
 * 2. Builds the LLM client from (in priority order):
 *    a. Programmatic override ([agentLLMConfig])
 *    b. Global config (`configs.json` → `llm` section via [LLMSource])
 *    c. Throws if neither is available
 * 3. Sets up conversation memory (persistent by default)
 * 4. Registers tools via [defineTools]
 * 5. Runs the agent with the task prompt
 * 6. Stores the result via [updateData]
 *
 * ### Minimal example (global config driven):
 *
 * In `configs.json`:
 * ```json
 * {
 *   "llm": {
 *     "provider": "DEEP_SEEK",
 *     "modelName": "deepseek-v4-flash",
 *     "apiKeyEnv": "DEEPSEEK_API_KEY"
 *   }
 * }
 * ```
 *
 * In code:
 * ```kotlin
 * @WithAgentSystemPrompt("You are a binary analysis assistant.")
 * @WithAgentMaxIterations(15)
 * @WithTableColumn("analysis", "TEXT")
 * @WithTableColumn("iterations", "INTEGER")
 * class BinaryAnalyst(
 *     configPath: String? = null,
 *     id: Int,
 *     program: Program?,
 *     consoleLogLevel: Level = Level.INFO,
 *     fileLogLevel: Level = Level.INFO
 * ) : AgentModule(configPath, id, program, consoleLogLevel = consoleLogLevel, fileLogLevel = fileLogLevel) {
 *
 *     override fun defineTools(): List<Tool> = listOf(
 *         Tool("list_functions", "List all functions in the binary", emptyList()) {
 *             program?.let { p ->
 *                 val fm = p.functionManager
 *                 fm.getFunctions(true).take(50).joinToString("\n") { "${it.name} @ ${it.entryPoint}" }
 *             } ?: "No program loaded"
 *         }
 *     )
 *
 *     override fun taskPrompt(): String =
 *         "Analyze this binary and identify its purpose, entry point, and key functions."
 * }
 * ```
 *
 * ### Programmatic override example:
 *
 * ```kotlin
 * @WithTableColumn("result", "TEXT")
 * class FlexibleAgent(
 *     configPath: String? = null,
 *     id: Int,
 *     program: Program?,
 *     consoleLogLevel: Level = Level.INFO,
 *     fileLogLevel: Level = Level.INFO
 * ) : AgentModule(configPath, id, program, consoleLogLevel = consoleLogLevel, fileLogLevel = fileLogLevel) {
 *
 *     override fun agentLLMConfig(): LLMConfig = LLMConfig(
 *         provider = LLMProvider.OLLAMA,
 *         modelName = "qwen3.6",
 *         apiKey = "ollama",
 *         baseUrl = "http://localhost:11434"
 *     )
 *
 *     override fun agentSystemPrompt(): String = "You are a reverse engineering assistant."
 *
 *     override fun defineTools(): List<Tool> = listOf(...)
 *
 *     override fun taskPrompt(): String = "Find vulnerabilities in this binary"
 * }
 * ```
 */
abstract class AgentModule(
    configPath: String? = null,
    defaultConfig: Any? = null,
    id: Int = -1,
    program: Program? = null,
    properties: Map<String, String?> = mapOf(),
    consoleLogLevel: org.apache.logging.log4j.Level = org.apache.logging.log4j.Level.INFO,
    fileLogLevel: org.apache.logging.log4j.Level = org.apache.logging.log4j.Level.INFO,
    tableName: String? = null
) : AkibaModule(
    configPath, defaultConfig, id, program, properties,
    consoleLogLevel, fileLogLevel, tableName
) {

    /** The agent session ID (created during [startProcess]). */
    var agentSessionId: String? = null
        internal set

    /** The agent instance (created during [startProcess]). */
    var agent: AkibaAgent? = null
        internal set

    /** The result of the agent run. */
    var agentResult: AgentResult? = null
        private set

    // ---- Overridable configuration (programmatic) -------------------------

    /**
     * Provide the LLM configuration for this agent.
     *
     * Override this to programmatically set the LLM config.
     * This takes the highest priority, overriding the global config
     * (`configs.json` → `llm` section).
     *
     * @return the LLM config, or null to use the global config.
     */
    protected open fun agentLLMConfig(): LLMConfig? = null

    /**
     * Provide the system prompt for this agent.
     *
     * Override this to programmatically set the system prompt.
     * If the class is annotated with [WithAgentSystemPrompt], that
     * takes precedence unless this returns a non-null value.
     *
     * @return the system prompt, or null to use annotation-based default.
     */
    protected open fun agentSystemPrompt(): String? = null

    /**
     * Provide the task prompt that the agent should work on.
     *
     * This is the user message sent to the agent. It must be provided
     * either by overriding this method or by setting it in the module's
     * config class.
     */
    protected abstract fun taskPrompt(): String

    /**
     * Define the tools available to this agent.
     *
     * Override this to register tools. For convenience, you can also
     * use the [tool] helper function:
     *
     * ```kotlin
     * override fun defineTools(): List<Tool> = listOf(
     *     tool("search", "Search functions") {
     *         parameter("pattern", "string", "Regex")
     *         execute { args -> ... }
     *     }
     * )
     * ```
     */
    protected open fun defineTools(): List<Tool> = emptyList()

    /**
     * Whether to automatically include the built-in tool set
     * ([BuiltInTools]) when this agent starts.
     *
     * Built-in tools include:
     * - `run_module` — delegate work to another AkibaModule
     * - `run_sub_agent` — spawn a child LLM agent
     * - `query_module_data` — query analysis results from the database
     * - `query_session_history` — review past agent sessions
     * - `query_memories` — search the long-term memory store
     *
     * Override and return `false` to exclude built-in tools.
     * You can also selectively add tools from [BuiltInTools] in
     * [defineTools].
     */
    protected open fun includeBuiltInTools(): Boolean = true

    /**
     * Maximum ReAct iterations for the agent.
     * Can be overridden or set via [WithAgentMaxIterations] annotation.
     */
    protected open fun maxAgentIterations(): Int = 10

    /**
     * The execution strategy for this agent.
     *
     * Override to choose between [ReActStrategy], [PlanExecuteStrategy],
     * or a custom [AgentStrategy]. Defaults to [ReActStrategy].
     *
     * ```kotlin
     * override fun agentStrategy() = PlanExecuteStrategy(maxReplanCycles = 2)
     * ```
     */
    protected open fun agentStrategy(): AgentStrategy = ReActStrategy()

    /**
     * Whether to use persistent (database-backed) memory.
     * Override and return false to use in-memory only.
     */
    protected open fun usePersistentMemory(): Boolean = true

    /**
     * Maximum messages to keep in the sliding window.
     * 0 means unlimited.
     */
    protected open fun maxMemoryMessages(): Int = 0

    /**
     * Process the agent's result and write data to the database.
     *
     * Override this to customize how the agent's output is stored.
     * The default implementation writes the output text to an "analysis"
     * column if one is defined.
     */
    protected open fun processResult(result: AgentResult) {
//        val data = mutableMapOf<String, Any?>(
//            "analysis" to result.output,
//            "iterations" to result.iterations,
//            "tool_calls" to result.toolCallsMade
//        )
//        // Filter to only include columns that are actually defined
//        val definedColumns = allDefinedDbColumns.keys
//        val filteredData = data.filterKeys { it in definedColumns }
//        if (filteredData.isNotEmpty()) {
//            updateData(filteredData)
//        }
    }

    // ---- Lifecycle -------------------------------------------------------

    /**
     * Internal bridge for built-in tools (e.g. [BuiltInTools])
     * to resolve the LLM config without exposing the protected method.
     */
    internal fun resolveLLMConfigInternal(): LLMConfig = resolveLLMConfig()

    override suspend fun startProcess() {
        logger.info("AgentModule starting: ${this.javaClass.simpleName}")

        // 1. Create agent session
        val sessionId = try {
            AgentDatabaseClient.createSession(
                sessionName = "${this.javaClass.simpleName}-$id",
                binaryId = id,
                moduleName = this.javaClass.simpleName,
                modelName = resolveModelName()
            )
        } catch (e: Exception) {
            logger.warn("Failed to create agent session: ${e.message}")
            null
        }
        agentSessionId = sessionId

        // 2. Build LLM client
        val llmConfig = resolveLLMConfig()
        val llmClient = LLMClientFactory.create(llmConfig)
        logger.info("Agent LLM: ${llmConfig.provider.displayName} / ${llmConfig.modelName}")

        // 3. Build memory
        val chatMemory = if (usePersistentMemory() && sessionId != null) {
            persistentChatMemory(sessionId, maxMemoryMessages())
        } else {
            inMemoryChatMemory(maxMemoryMessages())
        }

        // 4. Build memory manager
        val memoryManager = if (sessionId != null) {
            MemoryManager(sessionId, id)
        } else null

        // 5. Register tools
        val toolRegistry = ToolRegistry()
        val tools = defineTools()
        toolRegistry.registerAll(tools)

        // Register built-in tools (sub-module, sub-agent, DB queries)
        if (includeBuiltInTools()) {
            BuiltInTools.registerAll(toolRegistry, this)
        }

        logger.info("Agent tools: ${toolRegistry.names()}")

        // 6. Build agent
        val strategy = agentStrategy()
        logger.info("Agent strategy: ${strategy.name}")

        val agent = AkibaAgent(
            client = llmClient,
            systemPrompt = resolveSystemPrompt(),
            memory = chatMemory,
            memoryManager = memoryManager,
            toolRegistry = toolRegistry,
            maxIterations = resolveMaxIterations(),
            sessionId = sessionId,
            enrichSystemPromptWithMemory = true,
            auditToolCalls = true,
            strategy = strategy,
            logger = logger
        )
        this.agent = agent

        // 7. Run agent
        val prompt = taskPrompt()
        logger.info("Agent task: ${prompt.take(200)}")

        try {
            val result = agent.run(prompt)
            agentResult = result
            logger.info("Agent completed: ${result.stopReason}, ${result.iterations} iterations, ${result.toolCallsMade} tool calls")

            // 8. Process result
            processResult(result)

            // 9. Update session status
            if (sessionId != null) {
                try {
                    val status = when (result.stopReason) {
                        StopReason.COMPLETED -> "completed"
                        StopReason.MAX_ITERATIONS -> "error"
                        StopReason.ERROR -> "error"
                    }
                    AgentDatabaseClient.updateSession(sessionId, status = status)
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            logger.error("Agent run failed: ${e.message}")
            failureSign = FAILED

            if (sessionId != null) {
                try {
                    AgentDatabaseClient.updateSession(sessionId, status = "error")
                } catch (_: Exception) {}
            }
        } finally {
            llmClient.close()
        }
    }

    // ---- Resolution helpers ----------------------------------------------

    /**
     * Resolve the LLM configuration for this agent.
     *
     * Priority order:
     * 1. Programmatic override ([agentLLMConfig])
     * 2. Global config (`configs.json` → `llm` section)
     * 3. Throw [IllegalStateException] if neither is available
     */
    private fun resolveLLMConfig(): LLMConfig {
        // 1. Programmatic override takes highest priority
        agentLLMConfig()?.let { return it }

        // 2. Try global config
        val globalLLM = ConfigManager.llmConf
        if (globalLLM != null && globalLLM.isConfigured) {
            return globalLLM.toLLMConfig()
        }

        // 3. No config available
        throw IllegalStateException(
            "AgentModule ${this.javaClass.simpleName} requires LLM configuration. " +
            "Either override agentLLMConfig(), or add an 'llm' section to configs.json. " +
            "Example:\n" +
            """{ "llm": { "provider": "DEEP_SEEK", "modelName": "deepseek-v4-flash", "apiKeyEnv": "DEEPSEEK_API_KEY" } }"""
        )
    }

    private fun resolveSystemPrompt(): String {
        val base = agentSystemPrompt()
            ?: this::class.annotations.filterIsInstance<WithAgentSystemPrompt>().firstOrNull()?.prompt
            ?: "You are an AI assistant specialized in binary analysis and reverse engineering."

        return "$base\n\n$DEFAULT_AGENT_RULES"
    }

    private fun resolveMaxIterations(): Int {
        val override = maxAgentIterations()
        if (override != 10) return override  // non-default override

        val annotation = this::class.annotations.filterIsInstance<WithAgentMaxIterations>().firstOrNull()
        return annotation?.iterations ?: 10
    }

    companion object {
        /**
         * Common rules appended to every agent's system prompt.
         * These provide essential guidance about the runtime environment.
         */
        private const val DEFAULT_AGENT_RULES = """IMPORTANT RULES:
1. The workspace directory is empty by default — there are NO binary files in it. The binary under analysis is already loaded into Ghidra as the current program; you do NOT need to locate or open any file manually.
2. NEVER use the shell tool (run_shell) for tasks that can be accomplished with other available tools. Shell commands are a last resort only.
3. For binary analysis tasks, prefer using query_ghidra_api to look up Ghidra API usage and run_script to execute Ghidra scripts against the loaded program. These tools give you direct access to decompiled code, function listings, cross-references, and data flow — use them proactively.
4. Scripts are written in Kotlin (not Java/Jython). They differ from standard Ghidra scripts but all Ghidra APIs are fully available."""
    }

    private fun resolveModelName(): String {
        agentLLMConfig()?.let { return it.modelName }
        val globalLLM = ConfigManager.llmConf
        if (globalLLM != null && globalLLM.modelName.isNotBlank()) {
            return globalLLM.modelName
        }
        return "unknown"
    }
}

// ============================================================
//  Convenience: inline tool builder for AgentModule
// ============================================================

/**
 * Build a [Tool] using a DSL-style inline builder.
 *
 * Usage inside [AgentModule.defineTools]:
 * ```kotlin
 * override fun defineTools() = listOf(
 *     tool("search_functions") {
 *         description = "Search for functions matching a pattern"
 *         parameter("pattern", "string", "Regex pattern")
 *         execute { args ->
 *             val pattern = args["pattern"] as String
 *             "Results for: $pattern"
 *         }
 *     }
 * )
 * ```
 */
fun tool(name: String, block: InlineToolBuilder.() -> Unit): Tool {
    return InlineToolBuilder(name).apply(block).build()
}

/**
 * Inline builder for [Tool] — mirrors [ToolDefinitionBuilder] but
 * can be used at the top level for convenience.
 */
class InlineToolBuilder(private val name: String) {
    var description: String = ""
    private val params = mutableListOf<ToolParameter>()
    private var executor: ((Map<String, Any?>) -> String)? = null

    fun parameter(
        name: String,
        type: String = "string",
        description: String = "",
        required: Boolean = true,
        enum: List<String>? = null
    ) {
        params.add(ToolParameter(name, type, description, required, enum))
    }

    fun execute(block: (Map<String, Any?>) -> String) {
        executor = block
    }

    internal fun build(): Tool = Tool(
        name = name,
        description = description,
        parameters = params.toList(),
        execute = executor ?: { "Tool '$name' has no execute implementation" }
    )
}
