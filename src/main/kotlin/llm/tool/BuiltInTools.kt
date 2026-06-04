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
 * - [RunSubAgentTool] — spawn a child LLM agent
 * - [QueryModuleDataTool] — query analysis results from the database
 * - [QuerySessionHistoryTool] — review past agent sessions
 * - [QueryMemoriesTool] — search the long-term memory store
 * - [ListModulesTool] — list all available modules
 * - [RunScriptTool] — compile and run a Kotlin script dynamically
 * - [ScriptLibraryTool] — search and run pre-built scripts from the library
 * - [QueryGhidraAPITool] — search and read Ghidra API documentation
 * - [RunShellTool] — execute shell commands in the module workspace
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
    fun all(parent: AgentModule, agentDbClient: AgentDatabaseClient): List<Tool> = listOf(
        RunModuleTool(parent),
        RunSubAgentTool(parent, agentDbClient),
        QueryModuleDataTool(parent),
        QuerySessionHistoryTool(agentDbClient),
        QueryMemoriesTool(agentDbClient),
        ListModulesTool(),
        ScriptLibraryTool(parent, agentDbClient),
        RunScriptTool(parent, agentDbClient),
        QueryGhidraAPITool(),
        RunShellTool(parent)
    )

    /**
     * Register all built-in tools into a [ToolRegistry].
     *
     * ```kotlin
     * BuiltInTools.registerAll(registry, this, agentDbClient)
     * ```
     */
    fun registerAll(registry: ToolRegistry, parent: AgentModule, agentDbClient: AgentDatabaseClient) {
        registry.registerAll(all(parent, agentDbClient))
    }
}
