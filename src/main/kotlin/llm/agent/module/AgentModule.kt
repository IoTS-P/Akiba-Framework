package org.iotsplab.akiba.llm.agent.module

import ghidra.program.model.listing.Program
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.jar.JarFile
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.data.database.DatabaseClient
import org.iotsplab.akiba.llm.agent.builder.ToolDefinitionBuilder
import org.iotsplab.akiba.llm.agent.builder.akibaAgent
import org.iotsplab.akiba.llm.agent.core.AgentHarness
import org.iotsplab.akiba.llm.agent.core.AgentPrompts
import org.iotsplab.akiba.llm.agent.core.AgentResult
import org.iotsplab.akiba.llm.agent.core.AgentStrategy
import org.iotsplab.akiba.llm.agent.core.AgentTranscriptWriter
import org.iotsplab.akiba.llm.agent.core.AkibaAgent
import org.iotsplab.akiba.llm.agent.core.DefaultAgentHarness
import org.iotsplab.akiba.llm.agent.core.ProcessExitReason
import org.iotsplab.akiba.llm.agent.core.StopReason
import org.iotsplab.akiba.llm.agent.mailbox.AgentMailboxService
import org.iotsplab.akiba.llm.agent.runtime.AgentRuntime
import org.iotsplab.akiba.llm.agent.runtime.AgentSessionReconciler
import org.iotsplab.akiba.llm.agent.runtime.AsyncAgentServices
import org.iotsplab.akiba.llm.agent.runtime.RuntimeState
import org.iotsplab.akiba.llm.agent.template.AgentTemplate
import org.iotsplab.akiba.llm.agent.template.AgentTemplateRegistry
import org.iotsplab.akiba.llm.agent.template.Lifecycle
import org.iotsplab.akiba.llm.agent.template.spawnChildFromAgentProgrammatically
import org.iotsplab.akiba.llm.client.AkibaLLMClient
import org.iotsplab.akiba.llm.client.LLMClientFactory
import org.iotsplab.akiba.llm.client.LLMConfig
import org.iotsplab.akiba.llm.memory.ChatMemory
import org.iotsplab.akiba.llm.memory.MemoryManager
import org.iotsplab.akiba.llm.memory.inMemoryChatMemory
import org.iotsplab.akiba.llm.memory.persistentChatMemory
import org.iotsplab.akiba.llm.skill.SkillManager
import org.iotsplab.akiba.llm.skill.SkillManager.SkillInfo
import org.iotsplab.akiba.llm.tool.core.BuiltInTools
import org.iotsplab.akiba.llm.tool.core.Tool
import org.iotsplab.akiba.llm.tool.core.ToolRegistry
import org.iotsplab.akiba.managers.ConfigManager
import org.iotsplab.akiba.managers.WorkspaceManager
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.utils.ProcedureArgumentsDeserializer
import org.iotsplab.akiba.utils.WithAgentMaxIterations
import org.iotsplab.akiba.utils.WithBundledSkills
import org.iotsplab.akiba.utils.WithScriptFile
import org.iotsplab.akiba.llm.agent.strategy.ReActStrategy

// ============================================================
//  AgentModule — one-click deployable agent module
// ============================================================

/**
 * An [AkibaModule] that wraps an LLM agent for one-click deployment.
 *
 * Subclasses define behaviour by overriding the configuration methods and using
 * annotations.  The module creates a session, builds the LLM client (programmatic
 * [agentLLMConfig], else the global `configs.json` `llm` section via [LLMSource],
 * else throws), sets up persistent conversation memory, registers the tools from
 * [defineTools], runs the agent with the task prompt and stores the result via
 * [updateData].
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

    /**
     * The agent session ID (created during [startProcess]).
     *
     * Setter is `protected` (not `internal`) because subclasses that
     * live in a different Gradle module — most notably the
     * `AkibaUtils` module's manual-agent flow — need to assign the
     * sessionId they received via the worker handshake.  Keeping the
     * setter `internal` would block that, which silently broke the
     * `read_workspace_file` / `write_workspace_file` confirmation
     * flow (the tools' `confirmFileAccess` helper returns `false`
     * when this is null, auto-denying the access without ever
     * showing the user a modal).
     */
    var agentSessionId: String? = null
        protected set

    /**
     * The agent instance (created during [startProcess]).
     *
     * Setter is `protected` for the same reason as [agentSessionId]:
     * subclasses in other Gradle modules (manual-agent workers) must
     * be able to publish the agent instance they built locally so
     * tools that read `parent.agent` (e.g. `spawn_sub_agent`,
     * `agent_builder_alternatives`) can find it.
     */
    var agent: AkibaAgent? = null
        protected set

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
     * The system prompt this agent runs with: [agentRules] is appended after it
     * (separated by a blank line when non-blank) and the result is handed to
     * [AkibaAgent] as `systemPrompt`.
     *
     * Defaults to [AgentPrompts.DEFAULT_SYSTEM_PROMPT]; override with
     * `AgentPrompts.DEFAULT_SYSTEM_PROMPT + "\n\n" + ...` to keep the standard role
     * framing, or with a standalone prompt (and `agentRules = ""`) to replace it.
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
     * Optional extension point fired once between "templates are registered" and
     * "first [taskPrompt] is computed"; no-op by default.
     *
     * Use it to set up runtime state that [taskPrompt] itself must reference — e.g.
     * VulnDetector spawns its layer-1 standby children here so their session ids can
     * go into the root agent's first-turn prompt.
     *
     * Runs after [installAnnotatedBundledSkills] and template registration, so the
     * [AgentTemplateRegistry] is populated and [agentSessionId] is set.  The hook is
     * suspendable, and an exception here aborts [startProcess].
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
     * - `await_multiple_children` — wait for async child agents to reach target states
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
     * Override to supply a custom [AgentStrategy]. Defaults to [ReActStrategy].
     *
     * ```kotlin
     * override fun agentStrategy() = MyCustomStrategy()
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
     * [org.iotsplab.akiba.llm.tool.coordination.AgentMailboxTools]). Default null
     * Default is a non-null [AgentMailboxService] backed by this
     * module's [agentDbClient] — all agents should have mailbox
     * access for unified management, including ONE_SHOT agents
     * (which won't park but may still need to send/receive
     * messages during their single run).
     */
    protected open fun agentMailboxService(): AgentMailboxService? =
        AgentMailboxService(agentDbClient)

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
     * [jarOrDirPath] is the path the framework recorded for this module's main class
     * — a `.jar` normally, or a directory when running from an exploded source tree,
     * in which case [resourcePath] is resolved relative to it.  JAR entries starting
     * with [resourcePath] are streamed into a temporary directory (the directory
     * case is copied verbatim), and [SkillManager.installSkillDirectory] then
     * validates the `skill.json` / `SKILL.md` / `instructions.md` entry file and
     * copies the skill into the per-user skill root.
     *
     * Returns the installed [SkillInfo], or null when the resource was missing,
     * unreadable or failed to validate — logged at WARN so one broken skill does not
     * abort the surrounding startup sequence.
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
     * Install every `.kts` script declared by this module's [WithScriptFile]
     * annotation.
     *
     * Each [WithScriptFile.resourcePaths] entry is a **single `.kts` file path**
     * (e.g. `"script_library/group_functions.kts"`) installed via
     * [installBundledScript]; entries ending in `/` are directory paths and are
     * rejected with a WARN log — there is no directory auto-discovery.
     *
     * When the module's distribution cannot be located the call is a no-op with a
     * warning, so a misconfigured environment never breaks startup.  Idempotent:
     * the per-instance [bundledScriptsInstalled] flag short-circuits subsequent
     * calls.
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
     * Install a single `script_library/<file>.kts` file shipped alongside this
     * module.
     *
     * [jarOrDirPath] is the path the framework recorded for this module's main
     * class — a `.jar` normally, or a directory when running from an exploded
     * source tree, in which case [resourcePath] is resolved relative to it.  The
     * file's `// @name:` / `// @description:` header comments are parsed (the name
     * falls back to the file's basename) and fed to
     * [AgentDatabaseClient.createScript], whose "same author → overwrite"
     * deduplication makes the call idempotent against re-runs.
     *
     * Returns `true` on success, `false` when the resource was missing or the
     * script could not be registered (e.g. DB unavailable) — logged at WARN so one
     * broken script does not abort startup.
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
                    coldStart = spec.coldStart,
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

        // 0. Start background async services for this binary (dispatcher, orphan
        //     reaper, watchdog), idempotent per-binary via
        //     AsyncAgentServices.forBinary.  Must run BEFORE sub-agent spawning or
        //     mailbox writes, or a STANDBY child that receives a mailbox message is
        //     never woken — the dispatcher is not polling yet.
        val asyncServices = try {
            AsyncAgentServices.forBinary(id, agentDbClient).also { it.startBackground() }
        } catch (e: Exception) {
            logger.warn("Failed to start AsyncAgentServices: ${e.message}")
            null
        }

        // 0-pre: Reconcile session rows left non-terminal by a previous (possibly
        //        crashed) process; without it the frontend's status pill stays on
        //        "Running" / "Cancelling" forever after a SIGKILL, OOM or container
        //        restart, because the `terminationHook` never ran (see
        //        [AgentSessionReconciler]).
        //
        //        Safe to call even when this JVM already ran the reconciler at
        //        startup — its in-process guard makes the second call a no-op.
        //        Errors are caught inside the reconciler; we log additionally to
        //        keep the per-module record straight.
        try {
            val report = asyncServices?.reconcileOnStartup()
                ?: AgentSessionReconciler(
                    agentDbClient = agentDbClient,
                    reasonTag = "module_startup_fallback",
                ).reconcile()
            if (report.reconciled > 0) {
                logger.info(
                    "AgentModule startup reconciliation: closed " +
                        "${report.reconciled} stale session(s) for binary=$id " +
                        "(scanned=${report.scanned}, failed=${report.failed})"
                )
            }
        } catch (e: Exception) {
            logger.warn("AgentModule startup reconciliation threw: ${e.message}")
        }

        // 0. Install any skills bundled with this module. Subclasses that
        //    override startProcess can either call super.startProcess() or
        //    invoke installAnnotatedBundledSkills() directly to opt in.
        try {
            installAnnotatedBundledSkills()
        } catch (e: Exception) {
            logger.warn("Bundled skill installation failed: ${e.message}")
        }

        // 0b. Install any `script_library/<file>.kts` files bundled with this
        //     module, BEFORE the agent session is created, so agents that immediately
        //     call `script_library action=run scriptName=...` find their scripts
        //     registered.  Subclasses overriding startProcess can call
        //     super.startProcess() or invoke installAnnotatedBundledScripts().
        try {
            installAnnotatedBundledScripts()
        } catch (e: Exception) {
            logger.warn("Bundled script installation failed: ${e.message}")
        }

        // 1. Create agent session.  When this module runs inside a
        //    workflow subprocess, the workflow's run id is propagated via
        //    the AKIBA_WORKFLOW_ID env var (set by WorkflowRoutes) so the
        //    session — and, by daemon-side inheritance, all its child
        //    sessions — can be grouped by workflow in the UI.
        val sessionId = try {
            agentDbClient.createSession(
                sessionName = "${this.javaClass.simpleName}-$id",
                binaryId = id,
                moduleName = this.javaClass.simpleName,
                modelName = resolveModelName(),
                projectName = WorkspaceManager.activeProjectName,
                workflowId = System.getenv("AKIBA_WORKFLOW_ID")?.takeIf { it.isNotBlank() }
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
        // Route adapter-level logs (chat requests, connection lifecycle)
        // into this module's log file instead of the global Root.log.
        // The same client instance is shared with sub-agents, so their
        // adapter logs land here too.
        (llmClient as? org.iotsplab.akiba.llm.client.AbstractLangChainAdapter)?.logger = logger
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
        val rootRuntime = AgentRuntime.forBinary(id, agentDbClient)

        // Install the root agent's termination hook — the chokepoint where
        // module-specific cleanup (template unregister plus the final runtime_state
        // flip from "cancelling" to "closed") runs on the root's exit; the
        // cascade-cancel and resource-release steps come from
        // [AkibaAgent.defaultTerminate], which the agent's `init` installs as the
        // baseline.
        //
        // The hook only fires on a "truly terminating" run (see the truth table on
        // [runWithTermination]); STANDBY parks leave it untouched so the session can
        // resume on a later mailbox message.
        //
        // The [AkibaAgent.cascadeCanceller] below lets the default cascade step
        // reach the per-binary [AgentRuntime] without the agent knowing its binary
        // id; the runtime installs the same kind of canceller for child agents (see
        // [AgentRuntime.runChildJob]).
        agent.cascadeCanceller = { sid, reason, graceMs ->
            rootRuntime.cascadeCancelChildren(sid, reason, graceMs)
        }
        agent.terminationHook = hook@{
            val sid = agent.sessionId
            if (sid.isNullOrBlank()) return@hook

            // 1. framework default: cascade-cancel children (ONE_SHOT
            //    descendants; STANDBY descendants become orphans for
            //    the OrphanReaper) + close the LLM client + transcript.
            try {
                agent.defaultTerminate()
            } catch (e: Exception) {
                logger.warn(
                    "Root $sid termination hook: defaultTerminate failed: ${e.message}",
                    e
                )
            }

            // 2. unregister every agent template this module
            //    contributed to the registry, so re-runs of the
            //    same module don't see stale entries.
            try {
                unregisterContributedTemplates()
            } catch (e: Exception) {
                logger.warn("Root $sid termination hook: template unregister failed: ${e.message}")
            }

            // 3: transition the root's runtime_state + status to "closed" so the
            //    frontend's status pill stops on the final frame — the second half
            //    of the two-step transition begun when the strategy wrote
            //    "cancelling".  Skipped when the status is "error": the framework
            //    does not lie about cause by overwriting an error.
            val alreadyError = runCatching { agentDbClient.getSession(sid) }
                .getOrNull()?.status == "error"
            if (!alreadyError) {
                try {
                    agentDbClient.setRuntimeState(
                        sid,
                        "closed",
                        closingReason = "root:parent_closing",
                    )
                } catch (e: Exception) {
                    logger.warn(
                        "Root $sid termination hook: setRuntimeState failed: ${e.message}"
                    )
                }
                try {
                    agentDbClient.updateSession(sid, status = "closed")
                } catch (e: Exception) {
                    logger.warn(
                        "Root $sid termination hook: updateSession status=closed failed: ${e.message}"
                    )
                }
            }
        }

        if (sessionId != null && agent.lifecycle == Lifecycle.STANDBY) {
            try {
                rootRuntime.registerRootStandbySession(
                    sessionId = sessionId,
                    lifecycle = agent.lifecycle,
                    taskPrompt = "<root-direct-run>",
                ) { _ -> agent }
                logger.info("Registered root standby session $sessionId with AgentRuntime for mailbox resume")
            } catch (e: Exception) {
                logger.warn("Failed to register root standby session $sessionId: ${e.message}")
            }
        }

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

        // 7: Run agent via [AkibaAgent.runWithTermination] (not [AkibaAgent.run]) so
        // the root's [terminationHook] — cascade-cancel, template unregister,
        // LLM/transcript close and the final "cancelling" → "closed" flip — fires
        // when, and only when, the run is "truly terminating"; STANDBY parks leave it
        // untouched so the session can resume on a later mailbox message.
        //
        // The strategy writes the session status itself on every path (answered,
        // STANDBY, MAX_ITERATIONS, ERROR, LLM failure), so startProcess does not
        // mirror that mapping: its only lifecycle contribution is firing the
        // [processCompletionLatch], and in EXIT mode awaiting it synchronously.
        val prompt = taskPrompt()
        logger.info("Agent task: ${prompt.take(200)}")
        transcript.writeUserMessage(prompt)

        val result: AgentResult? = try {
            agent.runWithTermination(prompt)
        } catch (e: Exception) {
            // The strategy writes session status itself for any
            // error it catches internally.  An exception that
            // escapes here is a strategy bug or a transport
            // failure the strategy's internal try/catch did not
            // anticipate; we mark the module as failed and fire
            // the [processCompletionLatch] with [ProcessExitReason.ERROR]
            // so the [agent.processCompletionLatch.await()] at
            // the end of [startProcess] returns instead of
            // blocking forever.  The [OrphanReaper] picks up any
            // children that did not get cascade-cancelled.
            logger.error("Agent run failed: ${e.message}")
            failureSign = FAILED
            // Surface the failure on the session row so the
            // frontend's error banner can show *why* the agent
            // died instead of leaving the status pill on
            // "running" forever.  The strategy's own
            // [updateSessionStatus] would have already written
            // an `error` row for errors it caught internally; an
            // exception that reaches this catch is one it did
            // NOT catch, so this is the only chance to flip the
            // state.
            if (sessionId != null) {
                val errorReason = "uncaught_exception: ${e.javaClass.simpleName}: ${e.message?.take(400)}"
                try {
                    agentDbClient.updateSession(sessionId, status = "error")
                } catch (_: Exception) {}
                try {
                    agentDbClient.setRuntimeState(
                        sessionId,
                        RuntimeState.ERROR.wire(),
                        closingReason = errorReason,
                    )
                } catch (_: Exception) {}
            }
            withContext(NonCancellable) {
                agent.processCompletionLatch.complete(ProcessExitReason.ERROR)
            }
            null
        }

        if (result != null) {
            agentResult = result
            logger.info(
                "Agent completed: ${result.stopReason}, ${result.iterations} iterations, " +
                    "${result.toolCallsMade} tool calls"
            )
            if (result.stopReason == StopReason.ERROR) {
                logger.error("Error message: ${result.output}")
            }
            processResult(result)
            // The root parks on an explicit await_condition or on the
            // MAX_ITERATIONS safety fallback for STANDBY agents.
            // A completed run always terminates.
            val rootParked = agent.lifecycle == Lifecycle.STANDBY &&
                (result.stopReason == StopReason.STANDBY ||
                    result.stopReason == StopReason.MAX_ITERATIONS)
            if (rootParked && sessionId != null) {
                rootRuntime.markRegisteredSessionStandby(
                    sessionId = sessionId,
                    reason = "root_parked:${result.stopReason.name.lowercase()}",
                )
                logger.info(
                    "Session $sessionId parked (lifecycle=STANDBY, " +
                        "stopReason=${result.stopReason}); the latch stays open — " +
                        "startProcess will block until the agent is woken from standby " +
                        "and runs a terminating turn, or until the caller cancels the agent."
                )
            }
        }

        // The "still alive in STANDBY" diagnostic is emitted inside
        // rootParked for both await_condition and MAX_ITERATIONS parks.

        // startProcess must not return until the root reaches a truly terminating
        // state: the completion latch fires only after the termination hook and
        // cleanup finish, and explicit STANDBY or the STANDBY-lifecycle
        // MAX_ITERATIONS fallback keep it open for a later wake cycle.
        //
        // Awaiting unconditionally is deliberate.  A caller wrapping `startProcess`
        // in `runBlocking` and then calling `awaitProcessExit()` would otherwise see
        // the latch already complete and conclude "all work is done" while the root
        // is still alive in STANDBY awaiting mailbox and layer-1 sub-agents may be
        // half-cancelled; expected uses are:
        //  (a) `runBlocking { module.startProcess() }` — blocks until the root's
        //      terminating turn; a STANDBY + PARK root that never gets an exit signal
        //      blocks indefinitely, which is the right semantics for "finish before
        //      I return";
        //  (b) `launch { ... }` plus `module.awaitProcessExit().join()` — same, on a
        //      background scope so the foreground can do other work;
        //  (c) `launch { ... }` with no await — fire-and-forget, mailbox-driven.
        agent.processCompletionLatch.await()
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

    // ---- Process-lifecycle bridges ----------------------------------------

    /**
     * Suspend until this module's [startProcess] `finally` block
     * has finished every cleanup step (cascade-cancel, template
     * unregister, transcript close, LLM client close) and the
     * agent's process is fully terminated. Parked STANDBY runs keep
     * waiting until a later terminating wake cycle.
     *
     * This is the module-level mirror of
     * [AkibaAgent.awaitProcessExit]; for an orchestrator that
     * holds the [AgentModule] reference but not the [AkibaAgent]
     * itself, this is the cleaner call site.
     *
     * Returns `null` if [startProcess] has not been called yet
     * (no agent instance to await on).
     *
     * See [AkibaAgent.processCompletionLatch] for the underlying
     * mechanism and [ProcessExitReason] for the possible return
     * values.
     */
    suspend fun awaitProcessExit(): ProcessExitReason? =
        agent?.processCompletionLatch?.await()

    /**
     * Non-blocking check — `true` when the module's [startProcess]
     * has finished its `finally` block and the underlying agent
     * has reached a terminal state. Parked runs return false.
     * Useful for orchestrators that want to poll instead of suspending.
     */
    fun isProcessTerminated(): Boolean =
        agent?.processCompletionLatch?.isCompleted == true

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
//  Convenience: top-level tool() entry point
// ============================================================

/**
 * Build a [Tool] using the shared DSL builder.
 *
 * Same builder as the `akibaAgent { tools { tool(...) { } } }` form, so both
 * entry points behave identically (a tool without `execute` fails at
 * definition time rather than returning an error string at call time).
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
fun tool(name: String, block: ToolDefinitionBuilder.() -> Unit): Tool {
    return ToolDefinitionBuilder(name).apply(block).build()
}


