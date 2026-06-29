package org.iotsplab.akiba.llm.agent

import ghidra.program.model.listing.Program
import kotlinx.coroutines.launch
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.data.database.DatabaseClient
import org.iotsplab.akiba.llm.client.AkibaLLMClient
import org.iotsplab.akiba.llm.client.LLMClientFactory
import org.iotsplab.akiba.llm.client.LLMConfig
import org.iotsplab.akiba.llm.memory.ChatMemory
import org.iotsplab.akiba.llm.memory.MemoryManager
import org.iotsplab.akiba.llm.memory.inMemoryChatMemory
import org.iotsplab.akiba.llm.memory.persistentChatMemory
import org.iotsplab.akiba.llm.skill.SkillManager
import org.iotsplab.akiba.llm.skill.SkillManager.SkillInfo
import org.iotsplab.akiba.managers.ConfigManager
import org.iotsplab.akiba.managers.WorkspaceManager
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.llm.tool.BuiltInTools
import org.iotsplab.akiba.llm.tool.Tool
import org.iotsplab.akiba.llm.tool.ToolParameter
import org.iotsplab.akiba.llm.tool.ToolRegistry
import org.iotsplab.akiba.utils.ProcedureArgumentsDeserializer
import org.iotsplab.akiba.utils.WithAgentMaxIterations
import org.iotsplab.akiba.utils.WithBundledSkills
import org.iotsplab.akiba.utils.WithScriptFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.jar.JarFile

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
 *     // Replace the default role framing with a custom one (and skip the
 *     // default rules block because this agent uses its own instructions).
 *     override val agentSystemPrompt: String = "You are a binary analysis assistant."
 *     override val agentRules: String? = ""
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
 *     // Override the framework default system prompt with a custom one.
 *     override val agentSystemPrompt: String = "You are a reverse engineering assistant."
 *
 *     // Skip the framework default rules entirely.
 *     override val agentRules: String? = ""
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
    tableName: String? = null,
    dbClient: DatabaseClient = DatabaseClient.global
        ?: error("DatabaseClient.global not initialized. In CLI mode this is set by WorkspaceManager.initDatabase(); in server mode pass a per-request instance explicitly.")
) : AkibaModule(
    configPath, defaultConfig, id, program, properties,
    consoleLogLevel, fileLogLevel, tableName,
    dbClient = dbClient
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

    /** The agent database client. */
    val agentDbClient = AgentDatabaseClient(dbClient)

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
     * Public bridge for callers outside the framework module
     * (e.g. `akiba_mod_private` building sub-agents in
     * `onBeforeFirstRun`) that need the resolved LLM config
     * without going through protected [agentLLMConfig].
     */
    fun publicLLMConfig(): LLMConfig = resolveLLMConfig()

    /**
     * The system prompt this agent runs with. The framework appends
     * [agentRules] (separated by a blank line) after this prompt when
     * it is non-blank, then hands the result to [AkibaAgent] as the
     * agent's `systemPrompt`.
     *
     * Override this to provide a complete custom system prompt. The
     * default is [AgentPrompts.DEFAULT_SYSTEM_PROMPT], the framework's
     * standard role framing. To extend it with a per-agent
     * specialization while keeping the default role intact, prepend
     * the default explicitly:
     *
     * ```kotlin
     * override val agentSystemPrompt: String =
     *     AgentPrompts.DEFAULT_SYSTEM_PROMPT + "\n\n" + """
     *         Specialization for this session: ...
     *     """.trimIndent()
     * ```
     *
     * To completely replace the default role (e.g. for an agent that
     * is NOT a binary-analysis assistant), just override with a
     * standalone prompt and consider also overriding [agentRules] to
     * `""` to skip the binary-analysis-flavored default rules.
     */
    protected open val agentSystemPrompt: String = AgentPrompts.DEFAULT_SYSTEM_PROMPT

    /**
     * Rules appended after [agentSystemPrompt]. The default
     * ([AgentPrompts.DEFAULT_AGENT_RULES]) is a generic set of
     * tool-usage, memory, and tool-call rules that most Akiba agents
     * need.
     *
     * Override to:
     *  - return a different rules block (e.g. a domain-specific
     *    policy that should replace the framework defaults),
     *  - return an empty string to skip rules entirely (for agents
     *    that do their own prompt engineering and do not want the
     *    binary-analysis-flavored default rules bleeding in),
     *  - leave the default (null) to keep [AgentPrompts.DEFAULT_AGENT_RULES].
     */
    protected open val agentRules: String? = null

    /**
     * Provide the task prompt that the agent should work on.
     *
     * This is the user message sent to the agent. It must be provided
     * either by overriding this method or by setting it in the module's
     * config class.
     */
    protected abstract fun taskPrompt(): String

    /**
     * Optional extension point fired once between "templates are
     * registered with the registry" and "first [taskPrompt] is
     * computed". Default is a no-op.
     *
     * Use this to set up runtime state that the [taskPrompt] itself
     * needs to reference — e.g. VulnDetector spawns its layer-1
     * standby children here so it can embed their session ids in
     * the root agent's first-turn prompt (which is what tells the
     * root "send `start` to `batch_linear_planner`").
     *
     * The hook is suspendable; long-running setup (DB writes, child
     * coroutine spawns, ...) is allowed. Exceptions here abort
     * [startProcess] and surface in the `try/catch` upstream.
     *
     * The hook runs AFTER [installAnnotatedBundledSkills] and the
     * template registration step, so the [AgentTemplateRegistry]
     * is populated and the module's [agentSessionId] is set.
     */
    protected open suspend fun onBeforeFirstRun() {}

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
     * - `spawn_sub_agent` — spawn a child LLM agent (template or freeform)
     * - `await_agent` — wait for an async child to reach a target state
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
     * Username used for user-scoped built-in resources such as skills.
     * Batch modules normally fall back to the default `akiba` user; interactive
     * chat modules can override this with the authenticated web username.
     */
    protected open fun toolUsername(): String? = null

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
     * Domain-specific workflow harness for enforcing task-level process rules.
     * The default harness is a no-op.
     */
    protected open fun agentHarness(): AgentHarness = DefaultAgentHarness

    /**
     * Pre-built [AgentTemplate]s that this module contributes to the
     * current workflow scope.  Templates are auto-registered against
     * this module's [agentSessionId] in [startProcess] and auto-unregistered
     * in the cleanup `finally` block and in [close].
     *
     * The returned list is consulted once per run; mutation after the run
     * has started is not reflected.
     *
     * Example (DSL form):
     * ```
     * override fun agentTemplates(): List<AgentTemplate> = listOf(
     *     agentTemplate("binary_crypto_worker") {
     *         description = "..."
     *         baseSystemPrompt = "..."
     *         maxIterations(6)
     *         strategy(StrategySpecId.REACT)
     *         allowTools("search_skill", "read_skill")
     *         input("task") { type = "string"; required = true }
     *         factory { ctx -> akibaAgent { ... } }
     *     }
     * )
     * ```
     */
    protected open fun agentTemplates(): List<AgentTemplate> = emptyList()

    /**
     * Templates registered imperatively at runtime (e.g. inside
     * `startProcess` after some dynamic configuration step).  These
     * are auto-registered in [startProcess] *after* the [agentTemplates]
     * list, so a module can mix the two styles: static templates via
     * [agentTemplates], dynamic ones via [registerAgentTemplate].
     */
    protected fun registerAgentTemplate(template: AgentTemplate) {
        if (agentSessionId == null) {
            logger.warn(
                "registerAgentTemplate('{}') called before agentSessionId exists; " +
                    "this template will be lost when the module closes.",
                template.id
            )
        }
        dynamicTemplates[template.id] = template
    }

    /** Internal accumulator for [registerAgentTemplate]. */
    private val dynamicTemplates: LinkedHashMap<String, AgentTemplate> = linkedMapOf()

    /**
     * Compose the final template list for this module: the static
     * [agentTemplates] list followed by any templates added through
     * [registerAgentTemplate] (in registration order).  Called once
     * by [startProcess].
     */
    private fun collectAllTemplates(): List<AgentTemplate> {
        val static = try { agentTemplates() } catch (e: Exception) {
            logger.error("agentTemplates() threw: ${e.message}", e)
            emptyList()
        }
        return static + dynamicTemplates.values
    }

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
     * Lifecycle policy applied to this module's session. Default
     * [Lifecycle.ONE_SHOT] makes the session terminal after a
     * successful run; [Lifecycle.STANDBY] parks it so it keeps
     * accepting mailbox messages.
     */
    protected open fun lifecycle(): Lifecycle = Lifecycle.ONE_SHOT

    /**
     * Optional mailbox service attached to this module's session.
     * When non-null, the default [AgentHarness.beforeIteration]
     * drains unread messages before each LLM call, and the
     * `send_agent_message` / `read_agent_messages` /
     * `publish_agent_artifact` / `read_agent_artifact` tools become
     * available if the module also adds them via [defineTools] (see
     * [org.iotsplab.akiba.llm.tool.AgentMailboxTools]). Default null
     * keeps the legacy tool surface unchanged.
     */
    protected open fun agentMailboxService(): AgentMailboxService? = null

    // ---- Bundled-skill installation ---------------------------------------

    /**
     * Per-instance idempotency guard for [installAnnotatedBundledSkills].
     * Multiple sequential runs of the same module (e.g. on different
     * binaries) skip re-installing identical skill content.
     */
    @Volatile
    private var bundledSkillsInstalled: Boolean = false

    /**
     * Install a single skill shipped alongside this module.
     *
     * The [jarOrDirPath] is the path the framework recorded for this
     * module's main class — typically a `.jar` file.  When the module
     * is running from an exploded source tree (dev mode) this is a
     * directory instead, in which case [resourcePath] is resolved
     * relative to that directory.
     *
     * In the JAR case every entry whose name starts with [resourcePath]
     * is streamed into a temporary directory; in the directory case the
     * folder at `<jarOrDirPath>/<resourcePath>` is copied verbatim.
     * The resulting tree is then handed to
     * [SkillManager.installSkillDirectory], which validates the
     * `skill.json` / `SKILL.md` / `instructions.md` entry file and
     * copies the skill into the per-user skill root.
     *
     * Returns the installed [SkillInfo] on success, or null when the
     * resource was missing / unreadable / failed to validate.  Errors
     * are logged at WARN level so a single broken skill does not abort
     * the surrounding startup sequence.
     *
     * @param jarOrDirPath path returned by
     *                      [ProcedureArgumentsDeserializer.allModules]
     *                      for this module's fully-qualified class name.
     * @param resourcePath JAR entry prefix / subdirectory inside
     *                      [jarOrDirPath], e.g. `"skills/foo/"`.
     * @param username      skill namespace to install under.
     */
    protected fun installBundledSkill(
        jarOrDirPath: Path,
        resourcePath: String,
        username: String = "akiba",
    ): SkillInfo? {
        if (!Files.exists(jarOrDirPath)) {
            logger.warn(
                "AgentModule ${this.javaClass.simpleName}: cannot locate module " +
                    "distribution at $jarOrDirPath, skipping skill '$resourcePath'"
            )
            return null
        }
        val tmp = Files.createTempDirectory("akiba_bundled_skill_")
        try {
            var copied = false
            if (Files.isRegularFile(jarOrDirPath)) {
                JarFile(jarOrDirPath.toFile()).use { jar ->
                    val entries = jar.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory || !entry.name.startsWith(resourcePath)) continue
                        val rel = entry.name.removePrefix(resourcePath)
                        if (rel.isBlank()) continue
                        val out = tmp.resolve(rel).normalize()
                        Files.createDirectories(out.parent)
                        jar.getInputStream(entry).use { input ->
                            Files.newOutputStream(out).use { output -> input.copyTo(output) }
                        }
                        copied = true
                    }
                }
            } else if (Files.isDirectory(jarOrDirPath)) {
                val sourceRoot = jarOrDirPath.resolve(resourcePath.trimEnd('/'))
                if (!Files.isDirectory(sourceRoot)) {
                    logger.warn(
                        "AgentModule ${this.javaClass.simpleName}: skill resource " +
                            "'$resourcePath' not found in $jarOrDirPath"
                    )
                    return null
                }
                Files.walk(sourceRoot).use { stream ->
                    stream.forEach { src ->
                        val relPath = sourceRoot.relativize(src).toString()
                        if (relPath.isBlank()) return@forEach
                        val out = tmp.resolve(relPath).normalize()
                        if (Files.isDirectory(src)) {
                            Files.createDirectories(out)
                        } else {
                            Files.createDirectories(out.parent)
                            Files.copy(src, out, StandardCopyOption.REPLACE_EXISTING)
                            copied = true
                        }
                    }
                }
            } else {
                logger.warn(
                    "AgentModule ${this.javaClass.simpleName}: module path " +
                        "$jarOrDirPath is neither a regular file nor a directory"
                )
                return null
            }

            if (!copied) {
                logger.warn(
                    "AgentModule ${this.javaClass.simpleName}: skill resource " +
                        "'$resourcePath' not found in $jarOrDirPath"
                )
                return null
            }
            val installed = SkillManager.installSkillDirectory(username, tmp)
            logger.info(
                "AgentModule ${this.javaClass.simpleName}: installed bundled skill " +
                    "'${installed.id}' v${installed.version} for user '$username'"
            )
            return installed
        } catch (e: Exception) {
            logger.warn(
                "AgentModule ${this.javaClass.simpleName}: failed to install bundled " +
                    "skill '$resourcePath': ${e.message}"
            )
            return null
        } finally {
            runCatching {
                Files.walk(tmp).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }

    /**
     * Read this module's [WithBundledSkills] annotation (if any) and
     * install every listed skill via [installBundledSkill].
     *
     * The framework's module-class-to-JAR map ([ProcedureArgumentsDeserializer.allModules])
     * is consulted to find this module's distribution.  When the entry is
     * missing the call is a no-op (with a warning), so a misconfigured
     * environment never breaks module startup.
     *
     * Idempotent: a per-instance [bundledSkillsInstalled] flag short-circuits
     * subsequent calls so that long-lived modules that re-run `startProcess`
     * (e.g. on a different binary) don't re-extract identical content.
     */
    protected fun installAnnotatedBundledSkills() {
        if (bundledSkillsInstalled) return
        val annotation = this::class.annotations
            .filterIsInstance<WithBundledSkills>()
            .firstOrNull() ?: run {
            bundledSkillsInstalled = true
            return
        }
        val className = this::class.qualifiedName
        val modulePath = if (className != null) {
            ProcedureArgumentsDeserializer.allModules[className]
        } else null
        if (modulePath == null) {
            logger.warn(
                "AgentModule ${this.javaClass.simpleName}: cannot locate module " +
                    "distribution, skipping ${annotation.resourcePaths.size} bundled skill(s)"
            )
            bundledSkillsInstalled = true
            return
        }
        for (resourcePath in annotation.resourcePaths) {
            installBundledSkill(modulePath, resourcePath, annotation.username)
        }
        bundledSkillsInstalled = true
    }

    // ---- Bundled-script installation --------------------------------------

    /**
     * Per-instance idempotency guard for [installAnnotatedBundledScripts].
     * Multiple sequential runs of the same module (e.g. on different
     * binaries) skip re-installing identical script content; the
     * `createScript` call is itself idempotent on `(name, author)`, so
     * this flag is purely a startup-cost optimization.
     */
    @Volatile
    private var bundledScriptsInstalled: Boolean = false

    /**
     * Install every `.kts` script declared by this module's
     * [WithScriptFile] annotation.
     *
     * Each entry in [WithScriptFile.resourcePaths] is treated as a
     * **single `.kts` file path** (e.g.
     * `"script_library/group_functions.kts"`) and is installed via
     * [installBundledScript]. Entries that end in `/` are interpreted
     * as directory paths and are **rejected** — they are logged at
     * WARN level and skipped. There is no directory auto-discovery;
     * each script must be listed explicitly in the annotation.
     *
     * When the module's distribution cannot be located, the call is
     * a no-op (with a warning) — a misconfigured environment never
     * breaks module startup. Idempotent: a per-instance
     * [bundledScriptsInstalled] flag short-circuits subsequent calls.
     */
    protected fun installAnnotatedBundledScripts() {
        if (bundledScriptsInstalled) return
        val annotation = this::class.annotations
            .filterIsInstance<WithScriptFile>()
            .firstOrNull() ?: run {
            bundledScriptsInstalled = true
            return
        }
        val className = this::class.qualifiedName
        val modulePath = if (className != null) {
            ProcedureArgumentsDeserializer.allModules[className]
        } else null
        if (modulePath == null) {
            logger.warn(
                "AgentModule ${this.javaClass.simpleName}: cannot locate module " +
                    "distribution, skipping ${annotation.resourcePaths.size} bundled script(s)"
            )
            bundledScriptsInstalled = true
            return
        }
        val author = annotation.author.takeIf { it.isNotBlank() }
            ?: this.javaClass.simpleName
        for (resourcePath in annotation.resourcePaths) {
            if (resourcePath.endsWith("/")) {
                logger.warn(
                    "AgentModule ${this.javaClass.simpleName}: @WithScriptFile entry " +
                        "'$resourcePath' ends in '/' and looks like a directory path. " +
                        "Directories are not supported by @WithScriptFile — each script " +
                        "must be listed as a single .kts file. Skipping this entry."
                )
                continue
            }
            installBundledScript(modulePath, resourcePath, author)
        }
        bundledScriptsInstalled = true
    }

    /**
     * Install a single `script_library/<file>.kts` file shipped alongside
     * this module.
     *
     * The [jarOrDirPath] is the path the framework recorded for this
     * module's main class — typically a `.jar` file. When the module
     * is running from an exploded source tree (dev mode) this is a
     * directory instead, in which case [resourcePath] is resolved
     * relative to that directory.
     *
     * The file's `// @name:` / `// @description:` header comments are
     * parsed (matching the format produced by the framework's
     * script-library convention); the resolved name falls back to the
     * file's basename when no header is present. The result is fed
     * into [AgentDatabaseClient.createScript]; the database's
     * "same author → overwrite" deduplication makes this call
     * idempotent against re-runs.
     *
     * Returns `true` on success, `false` when the resource was missing
     * or the script could not be registered (e.g. DB unavailable).
     * Errors are logged at WARN level so a single broken script does
     * not abort the surrounding startup sequence.
     *
     * @param jarOrDirPath path returned by
     *                      [ProcedureArgumentsDeserializer.allModules]
     *                      for this module's fully-qualified class name.
     * @param resourcePath JAR entry / subdirectory-relative file path
     *                      inside [jarOrDirPath], e.g.
     *                      `"script_library/group_functions.kts"`.
     *                      Must NOT end in `/`; directory prefixes are
     *                      not supported by this entry point.
     * @param author        author tag to pass to
     *                      [AgentDatabaseClient.createScript]. Use a
     *                      module-stable name so re-installs are
     *                      detected as updates rather than duplicates.
     */
    protected fun installBundledScript(
        jarOrDirPath: Path,
        resourcePath: String,
        author: String,
    ): Boolean {
        if (!Files.exists(jarOrDirPath)) {
            logger.warn(
                "AgentModule ${this.javaClass.simpleName}: cannot locate module " +
                    "distribution at $jarOrDirPath, skipping script '$resourcePath'"
            )
            return false
        }
        if (resourcePath.endsWith("/")) {
            logger.warn(
                "AgentModule ${this.javaClass.simpleName}: installBundledScript " +
                    "rejected directory-form path '$resourcePath' (directories are " +
                    "not supported; pass a single .kts file path instead)"
            )
            return false
        }
        val (source, fileName) = readBundledResource(jarOrDirPath, resourcePath)
            ?: return false
        return registerScriptFromSource(source, fileName, resourcePath, author)
    }

    /**
     * Read [resourcePath] from this module's distribution. Returns a
     * `Pair(text, fileName)` on success, or `null` when the resource
     * is missing. Reads from a JAR via [JarFile] when [jarOrDirPath]
     * points at a regular file, or directly from disk when it points
     * at a directory (dev mode).
     */
    private fun readBundledResource(
        jarOrDirPath: Path,
        resourcePath: String,
    ): Pair<String, String>? {
        val fileName = resourcePath.substringAfterLast('/')
        return try {
            if (Files.isRegularFile(jarOrDirPath)) {
                JarFile(jarOrDirPath.toFile()).use { jar ->
                    val entry = jar.getEntry(resourcePath)
                        ?: return null
                    val source = jar.getInputStream(entry)
                        .bufferedReader(Charsets.UTF_8).use { it.readText() }
                    source to fileName
                }
            } else if (Files.isDirectory(jarOrDirPath)) {
                val onDisk = jarOrDirPath.resolve(resourcePath)
                if (!Files.isRegularFile(onDisk)) return null
                val source = Files.newBufferedReader(onDisk, Charsets.UTF_8).use { it.readText() }
                source to fileName
            } else {
                logger.warn(
                    "AgentModule ${this.javaClass.simpleName}: module path " +
                        "$jarOrDirPath is neither a regular file nor a directory"
                )
                null
            }
        } catch (e: Exception) {
            logger.warn(
                "AgentModule ${this.javaClass.simpleName}: failed to read " +
                    "script resource '$resourcePath': ${e.message}"
            )
            null
        }
    }

    /**
     * Parse [source]'s `// @name:` / `// @description:` header comments
     * and register the result via [AgentDatabaseClient.createScript].
     * The default name falls back to [fileName] minus its `.kts`
     * extension; the default description is empty. Returns `true` on
     * success, `false` on any failure (logged at WARN).
     *
     * The script is registered with `language="kotlin"`, `saveResult=true`,
     * and a 10 MiB output cap — matching the convention used by the
     * framework's existing `seedPresetScripts` flow.
     */
    private fun registerScriptFromSource(
        source: String,
        fileName: String,
        resourcePath: String,
        author: String,
    ): Boolean {
        val meta = parseScriptMeta(source, fileName) ?: return false
        return try {
            agentDbClient.createScript(
                name = meta.name,
                description = meta.description,
                author = author,
                code = source,
                language = "kotlin",
                saveResult = true,
                maxOutputSize = 10L * 1024L * 1024L,
            )
            logger.info(
                "AgentModule ${this.javaClass.simpleName}: installed bundled script " +
                    "'${meta.name}' (author='$author') from '$resourcePath'"
            )
            true
        } catch (e: Exception) {
            logger.warn(
                "AgentModule ${this.javaClass.simpleName}: failed to install bundled " +
                    "script from '$resourcePath': ${e.message}"
            )
            false
        }
    }

    /**
     * Parse the `// @name:` / `// @description:` header comments at the
     * top of a `script_library/<file>.kts` file. Returns `null` if the
     * file has no `// @name:` line at all (the script would have no
     * discoverable name and is rejected). Falls back to the file
     * basename when `// @name:` is missing.
     */
    private fun parseScriptMeta(source: String, fileName: String): ScriptMeta? {
        var name: String? = null
        var description = ""
        for (line in source.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("//")) {
                // Blank line is OK; any other non-comment terminates the header.
                if (trimmed.isNotEmpty()) break
                continue
            }
            when {
                trimmed.startsWith("// @name:") ->
                    name = trimmed.removePrefix("// @name:").trim()
                trimmed.startsWith("// @description:") ->
                    description = trimmed.removePrefix("// @description:").trim()
            }
        }
        val resolvedName = name?.takeIf { it.isNotBlank() }
            ?: fileName.removeSuffix(".kts")
        return ScriptMeta(resolvedName, description)
    }

    private data class ScriptMeta(val name: String, val description: String)

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

    /**
     * Build the [AkibaAgent] for this module.
     *
     * The default implementation wires up a [akibaAgent] DSL with
     * everything [startProcess] has staged:
     *  - the [AkibaLLMClient] resolved from the module's LLM config
     *  - chat memory (persistent or in-memory)
     *  - cognitive memory (when the session is created)
     *  - the tool registry (already includes [defineTools] output +
     *    the built-in tool set)
     *  - the transcript writer
     *
     * Override to fully customise the agent (e.g. inject extra
     * sub-agents via the [akibaAgent] DSL's `subAgent { ... }` block,
     * set a non-default [Lifecycle], swap in a domain-specific
     * [AgentHarness], etc.).  The override should keep the standard
     * fields (`client`, `memory`, `agentDbClient`, `transcript`,
     * `sessionId`) intact unless the module really does not need
     * them — most overrides only add `subAgent` declarations and
     * tweak the [Lifecycle] / harness.
     */
    protected open fun buildAgent(
        llmClient: AkibaLLMClient,
        chatMemory: ChatMemory,
        memoryManager: MemoryManager?,
        toolRegistry: ToolRegistry,
        transcript: AgentTranscriptWriter,
        sessionId: String?,
    ): AkibaAgent = akibaAgent {
        client(llmClient)
        system(resolveSystemPrompt())
        memory(chatMemory)
        memoryManager?.let { cognitiveMemory(it) }
        tools(toolRegistry.all())
        sessionId?.let { session(it) }
        transcript(transcript)
        strategy(agentStrategy())
        maxIterations(resolveMaxIterations())
        enrichSystemPrompt(true)
        auditToolCalls(true)
        agentDbClient(this@AgentModule.agentDbClient)
        harness(agentHarness())
        lifecycle(lifecycle())
        mailboxService(agentMailboxService())
        logger(this@AgentModule.logger)
    }

    /**
     * Spawn every [SubAgentSpec] declared in `agent.subAgents` before
     * the parent's first turn.  Default implementation iterates the
     * list and calls [spawnChildFromAgentProgrammatically] for each
     * entry.  Subclasses that want a different policy (e.g. defer
     * spawning until a later phase, or spawn ad-hoc children in
     * addition) can override.
     *
     * Runs AFTER the agent session is created and BEFORE
     * [onBeforeFirstRun], so subclass overrides of [onBeforeFirstRun]
     * / [taskPrompt] can already see the children's session ids
     * (read from the [AgentRuntime] via `agentDbClient` if needed,
     * or stashed in module fields by the override).
     */
    protected open suspend fun spawnConfiguredSubAgents(agent: AkibaAgent) {
        val rootSessionId = agentSessionId ?: return
        for (spec in agent.subAgents) {
            try {
                spawnChildFromAgentProgrammatically(
                    parent = this,
                    agentDbClient = agentDbClient,
                    agentFactory = spec.agentFactory,
                    parentSessionId = rootSessionId,
                    rootSessionId = rootSessionId,
                    depth = spec.depth,
                    lifecycle = spec.lifecycle,
                    taskPrompt = spec.taskPrompt,
                    name = spec.name,
                )
                logger.info(
                    "AgentModule sub-agent startup: spawned ${spec.name} " +
                        "(lifecycle=${spec.lifecycle}, depth=${spec.depth})"
                )
            } catch (e: Exception) {
                logger.error(
                    "AgentModule failed to spawn sub-agent ${spec.name}: ${e.message}", e,
                )
            }
        }
    }

    override suspend fun startProcess() {
        logger.info("AgentModule starting: ${this.javaClass.simpleName}")

        // 0. Install any skills bundled with this module. Subclasses that
        //    override startProcess can either call super.startProcess() or
        //    invoke installAnnotatedBundledSkills() directly to opt in.
        try {
            installAnnotatedBundledSkills()
        } catch (e: Exception) {
            logger.warn("Bundled skill installation failed: ${e.message}")
        }

        // 0b. Install any `script_library/<file>.kts` files bundled with this
        //     module. Done BEFORE the agent session is created so that
        //     agents and child agents that immediately call
        //     `script_library action=run scriptName=...` find their
        //     scripts already registered. Subclasses that override
        //     startProcess can either call super.startProcess() or invoke
        //     installAnnotatedBundledScripts() directly to opt in.
        try {
            installAnnotatedBundledScripts()
        } catch (e: Exception) {
            logger.warn("Bundled script installation failed: ${e.message}")
        }

        // 1. Create agent session
        val sessionId = try {
            agentDbClient.createSession(
                sessionName = "${this.javaClass.simpleName}-$id",
                binaryId = id,
                moduleName = this.javaClass.simpleName,
                modelName = resolveModelName(),
                projectName = WorkspaceManager.activeProjectName
            )
        } catch (e: Exception) {
            logger.warn("Failed to create agent session: ${e.message}")
            null
        }
        agentSessionId = sessionId

        // Apply standby lifecycle up-front so the send_agent_message
        // route enforces the rule from session creation onward.
        if (sessionId != null && lifecycle() == Lifecycle.STANDBY) {
            try {
                agentDbClient.setSessionLifecycle(sessionId, "standby")
            } catch (e: Exception) {
                logger.warn("Failed to set session lifecycle to standby: ${e.message}")
            }
        }

        // 2. Build LLM client
        val llmConfig = resolveLLMConfig()
        val llmClient = LLMClientFactory.create(llmConfig)
        logger.info("Agent LLM: ${llmConfig.provider.displayName} / ${llmConfig.modelName}")

        // 3. Build memory
        val chatMemory = if (usePersistentMemory() && sessionId != null) {
            persistentChatMemory(agentDbClient, sessionId, maxMemoryMessages())
        } else {
            inMemoryChatMemory(maxMemoryMessages())
        }

        // 4. Build memory manager
        val memoryManager = if (sessionId != null) {
            MemoryManager(agentDbClient, sessionId, id)
        } else null

        // 5. Register tools
        val toolRegistry = ToolRegistry()
        val tools = defineTools()
        toolRegistry.registerAll(tools)

        // Register built-in tools (sub-module, sub-agent, DB queries)
        if (includeBuiltInTools()) {
            BuiltInTools.registerAll(toolRegistry, this, agentDbClient, toolUsername())
        }

        logger.info("Agent tools: ${toolRegistry.names()}")

        // 6. Build agent with transcript writer
        val strategy = agentStrategy()
        logger.info("Agent strategy: ${strategy.name}")

        val transcript = AgentTranscriptWriter(agentDbClient, sessionId)
        val modelName = resolveModelName()

        val agent = buildAgent(
            llmClient = llmClient,
            chatMemory = chatMemory,
            memoryManager = memoryManager,
            toolRegistry = toolRegistry,
            transcript = transcript,
            sessionId = sessionId,
        )
        this.agent = agent

        // Write session start to transcript
        transcript.writeSessionStart(
            moduleName = this::class.simpleName ?: "AgentModule",
            binaryId = id,
            modelName = modelName,
            strategy = strategy.name
        )

        // 6.5. Register agent templates contributed by this module
        val registeredTemplateIds = mutableListOf<String>()
        val templates = collectAllTemplates()
        for (template in templates) {
            try {
                AgentTemplateRegistry.register(template, scopeId = sessionId ?: "__no_session__")
                registeredTemplateIds += template.id
            } catch (e: Exception) {
                logger.error("Failed to register template '${template.id}': ${e.message}")
            }
        }
        if (registeredTemplateIds.isNotEmpty()) {
            logger.info("Registered ${registeredTemplateIds.size} agent template(s) for scope='$sessionId': $registeredTemplateIds")
        }

        // 6.6. Spawn every [SubAgentSpec] declared in `agent.subAgents`
        //      (fixed-orchestration sub-agents).  Runs BEFORE
        //      [onBeforeFirstRun] so subclass hooks can already
        //      observe the children.
        spawnConfiguredSubAgents(agent)

        // 6.7. Module-defined pre-first-run hook.  Kept for backward
        //      compatibility; new code should prefer declaring
        //      sub-agents via the [akibaAgent] DSL's
        //      `subAgent { ... }` block.
        onBeforeFirstRun()

        // 7. Run agent
        val prompt = taskPrompt()
        logger.info("Agent task: ${prompt.take(200)}")
        transcript.writeUserMessage(prompt)

        try {
            val result = agent.run(prompt)
            agentResult = result
            logger.info("Agent completed: ${result.stopReason}, ${result.iterations} iterations, ${result.toolCallsMade} tool calls")
            if (result.stopReason == StopReason.ERROR)
                logger.error("Error message: ${agentResult?.output}")

            // 8. Process result
            processResult(result)

            // 9. Update session status. A successful run parks the
            //    session as `standby` for STANDBY lifecycle (still
            //    accepting mailbox messages) or terminates it as
            //    `closed`; error / max-iteration paths always
            //    terminate.
            if (sessionId != null) {
                try {
                    val status = when (result.stopReason) {
                        StopReason.COMPLETED ->
                            if (agent.lifecycle == Lifecycle.STANDBY) "standby" else "closed"
                        StopReason.STANDBY ->
                            if (agent.lifecycle == Lifecycle.STANDBY) "standby" else "closed"
                        StopReason.MAX_ITERATIONS -> "error"
                        StopReason.ERROR -> "error"
                    }
                    agentDbClient.updateSession(sessionId, status = status)
                    if (status == "standby") {
                        logger.info(
                            "Session $sessionId is in standby (lifecycle=standby); " +
                                "will continue to accept mailbox messages."
                        )
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            logger.error("Agent run failed: ${e.message}")
            failureSign = FAILED

            if (sessionId != null) {
                try {
                    agentDbClient.updateSession(sessionId, status = "error")
                } catch (_: Exception) {}
            }
        } finally {
            // Cascade-cancel any live sub-agents before we tear down.
            // Runs in a background coroutine so a slow cancel does not
            // block the parent's cleanup; on a process kill the OrphanReaper
            // will eventually pick this up.
            if (sessionId != null) {
                try {
                    val runtime = AgentRuntime.forBinary(id, agentDbClient)
                    kotlinx.coroutines.GlobalScope.launch {
                        try {
                            // The parent is closing.  Cascade-cancel
                            // its live descendants.  We pass
                            // callerSessionId = null because the
                            // parent is the source-of-truth
                            // authority, but the runtime has to
                            // walk each child individually; system
                            // privilege (null) lets the cleanup
                            // proceed regardless of any
                            // canBeCancelledBy=NONE policies the
                            // child templates declared.
                            val count = runtime.cancelSubtree(
                                rootSessionId = sessionId,
                                callerSessionId = null,
                                reason = "parent_closing",
                                graceMs = 30_000L,
                            )
                            if (count > 0) {
                                logger.info(
                                    "Cascade-cancelled $count live sub-agent(s) under " +
                                        "session $sessionId on parent close"
                                )
                            }
                        } catch (e: Exception) {
                            logger.warn("Cascade cancel on $sessionId failed: ${e.message}", e)
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to start cascade cancel: ${e.message}")
                }
            }

            // Always unregister any templates this module contributed, even
            // on failure, to prevent leaks across reruns on the same binary.
            unregisterContributedTemplates()
            transcript.close()
            llmClient.close()
        }
    }

    /**
     * Unregister every template this module contributed to the registry.
     * Idempotent: safe to call from both [startProcess]'s `finally`
     * block and [close] without double-removing.
     */
    private fun unregisterContributedTemplates() {
        val sid = agentSessionId ?: return
        val removed = AgentTemplateRegistry.unregisterScope(sid)
        if (removed > 0) {
            logger.info("Unregistered $removed agent template(s) for scope='$sid' (cleanup)")
        }
    }

    /**
     * Lifecycle hook mirroring [AkibaModule.close].  Releases any
     * templates this module contributed to [AgentTemplateRegistry] so
     * that re-runs of the same module do not see stale entries.
     *
     * The base [AkibaModule.close] is a no-op for most resources, but
     * we still invoke it via `super.close()` to keep parity with the
     * superclass contract.
     */
    override fun close() {
        try {
            unregisterContributedTemplates()
        } catch (e: Exception) {
            logger.warn("Template cleanup during close() failed: ${e.message}")
        }
        super.close()
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
        val base = agentSystemPrompt
        val rules = agentRules ?: AgentPrompts.DEFAULT_AGENT_RULES
        return if (rules.isBlank()) base else "$base\n\n$rules"
    }

    private fun resolveMaxIterations(): Int {
        val override = maxAgentIterations()
        if (override != 10) return override  // non-default override

        val annotation = this::class.annotations.filterIsInstance<WithAgentMaxIterations>().firstOrNull()
        return annotation?.iterations ?: 10
    }

    private fun resolveModelName(): String {
        agentLLMConfig()?.let { return it.modelName }
        val globalLLM = ConfigManager.llmConf
        if (globalLLM != null && globalLLM.modelName.isNotBlank()) {
            return globalLLM.modelName
        }
        return "unknown"
    }

    /**
     * Compose the final system prompt (base + rules).  Public bridge
     * for [buildAgent] / module subclasses that override [buildAgent]
     * and want to keep the framework's base + rules composition
     * intact.
     */
    fun composedSystemPrompt(): String = resolveSystemPrompt()

    /**
     * Resolve the max-iterations cap (override or
     * [WithAgentMaxIterations] annotation).  Public bridge for
     * [buildAgent] / module subclasses that override [buildAgent].
     */
    fun composedMaxIterations(): Int = resolveMaxIterations()
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
