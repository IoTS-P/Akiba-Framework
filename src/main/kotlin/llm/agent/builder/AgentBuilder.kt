package org.iotsplab.akiba.llm.agent.builder

import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.agent.context.ModelContextWindows
import org.iotsplab.akiba.llm.agent.core.AgentHarness
import org.iotsplab.akiba.llm.agent.core.AgentStrategy
import org.iotsplab.akiba.llm.agent.core.AgentTranscriptWriter
import org.iotsplab.akiba.llm.agent.core.AkibaAgent
import org.iotsplab.akiba.llm.agent.core.DefaultAgentHarness
import org.iotsplab.akiba.llm.agent.core.ToolResultDuplicateDetector
import org.iotsplab.akiba.llm.agent.mailbox.AgentMailboxService
import org.iotsplab.akiba.llm.agent.runtime.JobHandle
import org.iotsplab.akiba.llm.agent.template.Lifecycle
import org.iotsplab.akiba.llm.agent.template.ProgrammaticSubAgentBuilder
import org.iotsplab.akiba.llm.agent.template.ProgrammaticSubAgentSpec
import org.iotsplab.akiba.llm.client.AkibaLLMClient
import org.iotsplab.akiba.llm.client.LLMClientFactory
import org.iotsplab.akiba.llm.client.LLMConfig
import org.iotsplab.akiba.llm.client.LLMProvider
import org.iotsplab.akiba.llm.memory.ChatMemory
import org.iotsplab.akiba.llm.memory.InMemoryChatMemory
import org.iotsplab.akiba.llm.memory.MemoryManager
import org.iotsplab.akiba.llm.memory.persistentChatMemory
import org.iotsplab.akiba.llm.tool.core.Tool
import org.iotsplab.akiba.llm.tool.core.ToolParameter
import org.iotsplab.akiba.llm.tool.core.ToolRegistry
import org.iotsplab.akiba.managers.ConfigManager
import org.iotsplab.akiba.llm.agent.strategy.ReActStrategy

// ============================================================
//  AgentBuilder — DSL-style builder for AkibaAgent
// ============================================================

/**
 * Builder for constructing an [AkibaAgent] via the [akibaAgent] DSL.
 *
 * ```kotlin
 * val agent = akibaAgent {
 *     system("You are a binary analysis assistant.")
 *     maxIterations(15)
 * }
 * ```
 */
class AgentBuilder {

    // ---- LLM client configuration ----------------------------------------

    private var llmClient: AkibaLLMClient? = null
    private var llmConfig: LLMConfig? = null

    /** Provide a pre-built [AkibaLLMClient]. */
    fun client(client: AkibaLLMClient) {
        this.llmClient = client
    }

    /** Provide an [LLMConfig] to create the client from. */
    fun config(config: LLMConfig) {
        this.llmConfig = config
    }

    /** Shorthand: set the provider. Must be combined with [model] and [apiKey]. */
    fun provider(provider: LLMProvider) {
        pendingProvider = provider
        tryBuildConfig()
    }

    /** Shorthand: set the model name. */
    fun model(name: String) {
        pendingModel = name
        tryBuildConfig()
    }

    /** Shorthand: set the API key. */
    fun apiKey(key: String) {
        pendingApiKey = key
        tryBuildConfig()
    }

    /** Shorthand: set the base URL override. */
    fun baseUrl(url: String) {
        pendingBaseUrl = url
        tryBuildConfig()
    }

    /**
     * Load LLM configuration from the global config (`configs.json` → `llm` section).
     *
     * This is the recommended way to configure an agent when LLM settings
     * should be shared across all modules and changeable at deployment time.
     *
     * ```kotlin
     * val agent = akibaAgent {
     *     fromGlobalConfig()
     *     system("You are a binary analyst.")
     *     // ...
     * }
     * ```
     *
     * @throws IllegalStateException if the global config has no `llm` section
     *                              or the section is not fully configured.
     */
    fun fromGlobalConfig() {
        val source = ConfigManager.llmConf
            ?: throw IllegalStateException(
                "No 'llm' section found in the global config (configs.json). " +
                "Add an 'llm' block with provider, modelName, and apiKeyEnv/apiKey."
            )
        if (!source.isConfigured) {
            throw IllegalStateException(
                "The global 'llm' config is incomplete. " +
                "Both 'provider' and 'modelName' must be specified."
            )
        }
        llmConfig = source.toLLMConfig()
    }

    /**
     * Resolve LLM configuration from the global config (`configs.json` → `llm` section).
     *
     * This is an alias for [fromGlobalConfig] kept for backward compatibility.
     *
     * @throws IllegalStateException if the global config has no `llm` section
     *                              or the section is not fully configured.
     */
    fun fromRuntimeOrGlobalConfig() {
        fromGlobalConfig()
    }

    private var pendingProvider: LLMProvider? = null
    private var pendingModel: String? = null
    private var pendingApiKey: String? = null
    private var pendingBaseUrl: String? = null

    private fun tryBuildConfig() {
        val p = pendingProvider ?: return
        val m = pendingModel ?: return
        val k = pendingApiKey ?: return
        llmConfig = LLMConfig(
            provider = p,
            modelName = m,
            apiKey = k,
            baseUrl = pendingBaseUrl
        )
    }

    // ---- System prompt ---------------------------------------------------

    private var systemPrompt: String? = null

    /** Set the system prompt. */
    fun system(prompt: String) {
        systemPrompt = prompt
    }

    // ---- Memory ----------------------------------------------------------

    private var chatMemory: ChatMemory? = null
    private var memoryManager: MemoryManager? = null
    private var sessionId: String? = null
    private var binaryId: Int? = null
    private var agentDbClient: AgentDatabaseClient? = null
    private var transcript: AgentTranscriptWriter? = null

    /** Set the [AgentDatabaseClient] for session persistence and audit. */
    fun agentDbClient(client: AgentDatabaseClient) {
        agentDbClient = client
    }

    /** Configure chat memory. */
    fun memory(block: MemoryBuilder.() -> Unit) {
        chatMemory = MemoryBuilder(agentDbClient).apply(block).build()
    }

    /** Set a pre-built [ChatMemory]. */
    fun memory(mem: ChatMemory) {
        chatMemory = mem
    }

    /** Set the session ID for persistence. Also creates a [MemoryManager]. */
    fun session(id: String) {
        sessionId = id
    }

    /** Set the binary ID for memory scoping. */
    fun binary(id: Int) {
        binaryId = id
    }

    /** Provide a pre-built [MemoryManager]. */
    fun cognitiveMemory(mgr: MemoryManager) {
        memoryManager = mgr
    }

    /**
     * Provide a pre-built [AgentTranscriptWriter] for this agent.
     *
     * When set, the strategy will write every LLM interaction, tool call
     * and tool result to the writer (typically a writer targeting the
     * agent's session row in the database). The Markdown rendered there
     * powers the per-session export endpoint and the agent-tree view.
     *
     * Sub-agent factories built via [akibaAgent] should pass the
     * transcript writer from [SubAgentFactoryContext.transcript] here
     * so child sessions get the same export / front-end visibility
     * as top-level sessions.
     */
    fun transcript(writer: AgentTranscriptWriter?) {
        transcript = writer
    }

    // ---- Tools -----------------------------------------------------------

    private val toolRegistry = ToolRegistry()

    /** Configure tools using a DSL block. */
    fun tools(block: ToolBuilderContext.() -> Unit) {
        ToolBuilderContext(toolRegistry).apply(block)
    }

    /** Register pre-built tools. */
    fun tools(vararg toolList: Tool) {
        toolRegistry.registerAll(*toolList)
    }

    /** Register pre-built tools from a collection. */
    fun tools(tools: Collection<Tool>) {
        toolRegistry.registerAll(tools)
    }

    // ---- Agent parameters ------------------------------------------------

    private var maxIter: Int = 10
    private var enrich: Boolean = true
    private var audit: Boolean = true
    private var agentStrategy: AgentStrategy = ReActStrategy()
    private var duplicateDetector: ToolResultDuplicateDetector? = null
    private var harness: AgentHarness = DefaultAgentHarness

    /**
     * Install a custom domain [AgentHarness] (workflow gates, tool-call
     * redirects, final-answer validation) for this agent. By default the
     * builder installs [DefaultAgentHarness], which is a no-op.
     *
     * Sub-agent factories built via [akibaAgent] typically pass a custom
     * harness here so child agents enforce the same workflow rules the
     * parent enforces (e.g. VulnDetector sub-agent harnesses for
     * `linear_checker` / `recursive_checker` enforce the comment-after-
     * disassembly rule and the canonical comment format).
     */
    fun harness(h: AgentHarness) {
        harness = h
    }

    /** Set the maximum iteration limit. */
    fun maxIterations(n: Int) {
        maxIter = n
    }

    /** Whether to enrich the system prompt with memory context. */
    fun enrichSystemPrompt(value: Boolean) {
        enrich = value
    }

    /** Whether to audit tool calls to the database. */
    fun auditToolCalls(value: Boolean) {
        audit = value
    }

    /** Override the default tool result duplicate detector. */
    fun toolResultDuplicateDetector(detector: ToolResultDuplicateDetector?) {
        duplicateDetector = detector
    }

    /**
     * Set the execution strategy.
     *
     * ```kotlin
     * strategy(ReActStrategy())   // the default
     * ```
     */
    fun strategy(strategy: AgentStrategy) {
        agentStrategy = strategy
    }

    /**
     * Use the ReAct (Reasoning + Acting) strategy.
     *
     * Convenience shortcut for `strategy(ReActStrategy())`.
     */
    fun react() {
        agentStrategy = ReActStrategy()
    }

    // ---- Sub-agents ------------------------------------------------------

    private val subAgents = mutableListOf<ProgrammaticSubAgentSpec>()

    /**
     * Declare a child agent to be pre-created at startup.  The block
     * receives a [ProgrammaticSubAgentBuilder] for `depth` /
     * `lifecycle` / `taskPrompt` / `buildAgent { handle -> ... }`.
     * Each entry is materialised as a [ProgrammaticSubAgentSpec] in
     * [AkibaAgent.subAgents]; [AgentModule.spawnConfiguredSubAgents]
     * iterates that list and spawns each child via
     * [spawnChildFromAgentProgrammatically] before the parent's
     * first turn.
     *
     * ```kotlin
     * akibaAgent {
     *     client(...)
     *     system("...")
     *     subAgent("batch_linear_planner") {
     *         depth = 1
     *         lifecycle(Lifecycle.STANDBY)
     *         taskPrompt = "Read your inbox on every wake."
     *         buildAgent { handle ->
     *             AkibaAgent(client = ..., sessionId = handle.sessionId, ...)
     *         }
     *     }
     * }
     * ```
     */
    fun subAgent(name: String, configure: ProgrammaticSubAgentBuilder.() -> Unit) {
        subAgents += ProgrammaticSubAgentBuilder(name).apply(configure).build()
    }

    // ---- Lifecycle / mailbox / runtime / logger / compaction ------------

    private var lifecycleValue: Lifecycle = Lifecycle.ONE_SHOT
    private var mailboxServiceValue: AgentMailboxService? = null
    private var loggerValue: org.apache.logging.log4j.Logger? = null
    private var runtimeHandleValue: JobHandle? = null
    private var autoCompactValue: Boolean = true
    private var compactOnStandbyValue: Boolean = true
    private var compactThresholdValue: Double = 0.75
    private var compactKeepRoundsValue: Int = 2
    private var terminationHookOverride: (suspend AkibaAgent.() -> Unit)? = null

    /** Set the [Lifecycle] the agent runs with. Default [Lifecycle.ONE_SHOT]. */
    fun lifecycle(value: Lifecycle) {
        lifecycleValue = value
    }

    /** Provide a mailbox service for inter-agent communication. */
    fun mailboxService(value: AgentMailboxService?) {
        mailboxServiceValue = value
    }

    /** Provide a custom logger (defaults to a class-level logger). */
    fun logger(value: org.apache.logging.log4j.Logger) {
        loggerValue = value
    }

    /** Provide a runtime handle (used by the [AgentRuntime] for cancellation). */
    fun runtimeHandle(value: JobHandle?) {
        runtimeHandleValue = value
    }

    /** Whether to auto-compact the conversation before each `run()`. */
    fun autoCompact(value: Boolean) {
        autoCompactValue = value
    }

    /** Whether to compact just before parking a STANDBY session. */
    fun compactOnStandby(value: Boolean) {
        compactOnStandbyValue = value
    }

    /** Fraction of [AkibaAgent.contextLength] that triggers compaction. */
    fun compactThreshold(value: Double) {
        compactThresholdValue = value
    }

    /** Number of recent rounds to retain during compaction. */
    fun compactKeepRounds(value: Int) {
        compactKeepRoundsValue = value
    }

    // ---- Termination -----------------------------------------------------

    /**
     * Override the [AkibaAgent.terminationHook] for this agent.
     *
     * The default hook runs [AkibaAgent.cascadeCancel] (cancels every
     * non-STANDBY descendant via the per-binary [AgentRuntime]), then
     * [AkibaAgent.close] (closes the LLM client and transcript). The block you
     * pass runs with the freshly-built [AkibaAgent] as receiver and **replaces**
     * that default — call [AkibaAgent.defaultTerminate] first if you want to
     * keep it and add steps around it.
     *
     * ```kotlin
     * onTermination {
     *     defaultTerminate()          // cascade + close
     *     unregisterMyTemplates()     // module-specific
     * }
     * ```
     *
     * Typical use: the surrounding [AgentModule] does per-module bookkeeping
     * (template unregistration, `runtime_state` flip, status update) at the end
     * of the run — see [AgentModule.startProcess].
     */
    fun onTermination(block: suspend AkibaAgent.() -> Unit) {
        terminationHookOverride = block
    }

    // ---- Build -----------------------------------------------------------

    /**
     * Build the [AkibaAgent] from the configured parameters.
     *
     * @throws IllegalStateException if no LLM client or config was provided.
     */
    fun build(): AkibaAgent {
        val resolvedClient = llmClient
            ?: llmConfig?.let { LLMClientFactory.create(it) }
            ?: throw IllegalStateException(
                "Agent requires an LLM client. Use client() or provider()/model()/apiKey()."
            )

        val resolvedMemory = chatMemory ?: InMemoryChatMemory()

        val resolvedMemoryManager = memoryManager
            ?: sessionId?.let { agentDbClient?.let { adc -> MemoryManager(adc, it, binaryId) } }

        val contextLength = ModelContextWindows.contextLength(resolvedClient.config.modelName)

        val agent = AkibaAgent(
            client = resolvedClient,
            systemPrompt = systemPrompt,
            memory = resolvedMemory,
            memoryManager = resolvedMemoryManager,
            toolRegistry = toolRegistry,
            maxIterations = maxIter,
            sessionId = sessionId,
            enrichSystemPromptWithMemory = enrich,
            auditToolCalls = audit,
            strategy = agentStrategy,
            contextLength = contextLength,
            agentDbClient = agentDbClient,
            toolResultDuplicateDetector = duplicateDetector,
            agentHarness = harness,
            transcript = transcript,
            autoCompact = autoCompactValue,
            compactOnStandby = compactOnStandbyValue,
            compactThreshold = compactThresholdValue,
            compactKeepRounds = compactKeepRoundsValue,
            lifecycle = lifecycleValue,
            mailboxService = mailboxServiceValue,
            logger = loggerValue ?: org.apache.logging.log4j.LogManager.getLogger(AkibaAgent::class.java),
            runtimeHandle = runtimeHandleValue,
            subAgents = subAgents.toList(),
        )
        // If the caller supplied a custom termination hook via
        // the `onTermination { ... }` DSL, install it on the
        // agent.  The default installed by AkibaAgent.init
        // (cascade + close) is replaced by the override; the
        // override can still call `defaultTerminate()` to keep
        // the standard cleanup and add extra steps around it.
        terminationHookOverride?.let { block ->
            agent.terminationHook = { block(agent) }
        }
        return agent
    }
}

// ============================================================
//  MemoryBuilder — DSL for chat memory
// ============================================================

/**
 * DSL builder for [ChatMemory].
 *
 * ```kotlin
 * memory {
 *     persistent(sessionId = "abc-123", maxMessages = 50)
 * }
 * // or: memory { inMemory(maxMessages = 20) }
 * ```
 */
class MemoryBuilder(private val agentDbClient: AgentDatabaseClient? = null) {
    private var factory: (() -> ChatMemory)? = null

    /** Use a persistent database-backed memory. */
    fun persistent(
        sessionId: String,
        maxMessages: Int = 0,
        maxTokens: Int = 0
    ) {
        val adc = agentDbClient
            ?: throw IllegalStateException(
                "persistentChatMemory requires an AgentDatabaseClient. " +
                "Use agentDbClient() in the builder to provide one."
            )
        factory = { persistentChatMemory(adc, sessionId, maxMessages, maxTokens) }
    }

    /** Use a pure in-memory memory. */
    fun inMemory(maxMessages: Int = 0) {
        factory = { InMemoryChatMemory(maxMessages) }
    }

    /** Use a custom [ChatMemory] implementation. */
    fun custom(mem: ChatMemory) {
        factory = { mem }
    }

    internal fun build(): ChatMemory =
        factory?.invoke() ?: InMemoryChatMemory()
}

// ============================================================
//  ToolBuilderContext — DSL for tool definition
// ============================================================

/**
 * DSL context for defining tools inside the [AgentBuilder.tools] block.
 *
 * ```kotlin
 * tool("search_functions") {
 *     parameter("pattern", "string", "Regex pattern to match")
 *     execute { args -> search(args["pattern"] as String) }
 * }
 * ```
 */
class ToolBuilderContext(private val registry: ToolRegistry) {

    /**
     * Define and register a tool.
     *
     * @param name Tool name (used by the LLM to reference this tool).
     * @param block Configuration block for the tool.
     */
    fun tool(name: String, block: ToolDefinitionBuilder.() -> Unit) {
        val builder = ToolDefinitionBuilder(name).apply(block)
        registry.register(builder.build())
    }
}

/**
 * Builder for a single [Tool] definition.
 */
class ToolDefinitionBuilder(private val name: String) {

    /** Human-readable description of what this tool does. */
    var description: String = ""

    private val params = mutableListOf<ToolParameter>()
    private var executor: ((Map<String, Any?>) -> String)? = null

    /** Add a parameter definition. */
    fun parameter(
        name: String,
        type: String = "string",
        description: String = "",
        required: Boolean = true,
        enum: List<String>? = null
    ) {
        params.add(ToolParameter(name, type, description, required, enum))
    }

    /** Set the execution function for this tool. */
    fun execute(block: (Map<String, Any?>) -> String) {
        executor = block
    }

    internal fun build(): Tool {
        return Tool(
            name = name,
            description = description,
            parameters = params.toList(),
            execute = executor ?: throw IllegalStateException(
                "Tool '$name' requires an execute() block"
            )
        )
    }
}

// ============================================================
//  Top-level DSL entry point
// ============================================================

/**
 * Create an [AkibaAgent] using a Kotlin DSL builder.
 *
 * This is the primary entry point for constructing agents in Akiba.
 *
 * ```kotlin
 * val agent = akibaAgent {
 *     provider(LLMProvider.DEEP_SEEK)
 *     model("deepseek-v4-flash")
 *     apiKey(System.getenv("DEEPSEEK_API_KEY"))
 *
 *     system("You are a binary analysis assistant.")
 *
 *     memory { persistent(sessionId = mySessionId) }
 *
 *     tools {
 *         tool("search") {
 *             description = "Search functions"
 *             parameter("pattern", "string", "Regex pattern")
 *             execute { args -> searchFunctions(args["pattern"] as String) }
 *         }
 *     }
 *
 *     maxIterations(10)
 * }
 *
 * val result = agent.run("Find the entry point of this binary")
 * ```
 */
fun akibaAgent(block: AgentBuilder.() -> Unit): AkibaAgent =
    AgentBuilder().apply(block).build()
