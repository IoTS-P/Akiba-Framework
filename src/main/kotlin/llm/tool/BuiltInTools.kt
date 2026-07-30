package org.iotsplab.akiba.llm.tool

import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.agent.AgentModule
import org.iotsplab.akiba.module.AkibaModule

// ============================================================
//  BuiltInTools — agent-accessible built-in tool collection
// ============================================================

/**
 * Factory object that provides ready-made [Tool] instances for common
 * agent operations.
 *
 * Tool definitions are split into individual files in the
 * `org.iotsplab.akiba.llm.tool` package:
 *
 * - [RunModuleTool] — delegate work to another [AkibaModule]
 * - [SpawnSubAgentTool] — async fire-and-forget child LLM agent
 *   (template path or freeform path)
 * - [ListSubAgentsTool] — list/search the caller's sub-agents and
 *   their runtime states (with optional status filter and recursion)
 * - [AwaitMultipleChildrenTool] — batch-wait for N child agents in
 *   one call (mode=any|all)
 * - [AgentBuilderAlternativesTool] — describe available templates
 * - [QueryModuleDataTool] — query analysis results from the database
 * - [QuerySessionHistoryTool] — review past agent sessions
 * - [QueryMemoriesTool] — search the long-term memory store
 * - [ReadHistoryToolCallTool] — retrieve stored historical tool-call results
 * - [ListModulesTool] — list all available modules
 * - [RunScriptTool] — compile and run a Kotlin script dynamically
 * - [ScriptLibraryTool] — search and run pre-built scripts from the library
 * - [SearchSkillTool] / [ReadSkillTool] — discover installed skills and read skill files
 * - [QueryGhidraAPITool] — search and read Ghidra API documentation
 * - [RunShellTool] — execute shell commands in the module workspace
 * - [AskUserChoiceTool] — ask the user to pick one of several values
 *   (with an optional custom free-form answer)
 * - [WorkspaceFileTools] — read/write/list/move/delete files within the
 *   module workspace (sandboxed, path-traversal-safe)
 *
 * ### Usage in AgentModule
 *
 * ```kotlin
 * override fun defineTools(): List<Tool> = BuiltInTools.all(this)
 * ```
 *
 * Or pick individual tools:
 *
 * ```kotlin
 * override fun defineTools() = listOf(
 *     RunModuleTool(this),
 *     ListModulesTool(),
 * )
 * ```
 */
object BuiltInTools {

    /**
     * Create all built-in tools for the given [parent] [AgentModule].
     *
     * ```kotlin
     * override fun defineTools() = BuiltInTools.all(this)
     * ```
     */
    fun all(parent: AgentModule, agentDbClient: AgentDatabaseClient, username: String? = null): List<Tool> = listOf(
        RunModuleTool(parent),
        SpawnSubAgentTool(parent, agentDbClient),
        ListSubAgentsTool(parent, agentDbClient),
        AwaitMultipleChildrenTool(parent, agentDbClient),
        GetAgentStatusTool(agentDbClient, parent.agentSessionId),
        AgentBuilderAlternativesTool(parent),
        QueryModuleDataTool(parent),
        QuerySessionHistoryTool(agentDbClient),
        QueryMemoriesTool(agentDbClient),
        ReadHistoryToolCallTool(agentDbClient),
        SearchHistoryToolCallsTool(agentDbClient),
        ListModulesTool(),
        ScriptLibraryTool(parent, agentDbClient),
        SearchSkillTool(username),
        ReadSkillTool(username),
        RunScriptTool(parent, agentDbClient),
        QueryGhidraAPITool(),
        RunShellTool(parent),
        AskUserChoiceTool(parent)
    ) + WorkspaceFileTools(parent)

    /**
     * Register all built-in tools into a [ToolRegistry].
     *
     * ```kotlin
     * BuiltInTools.registerAll(registry, this, agentDbClient)
     * ```
     *
     * Note: the mailbox / artifact tools are NOT in this
     * default set — opt in by appending
     * `AgentMailboxTools(myMailboxService, parent.agentSessionId)` in
     * [AgentModule.defineTools].
     */
    fun registerAll(registry: ToolRegistry, parent: AgentModule, agentDbClient: AgentDatabaseClient, username: String? = null) {
        registry.registerAll(all(parent, agentDbClient, username))
    }
}
