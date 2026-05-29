package org.iotsplab.akiba.llm.tool

import org.iotsplab.akiba.llm.agent.AgentModule
import org.iotsplab.akiba.llm.agent.Tool
import org.iotsplab.akiba.llm.agent.ToolRegistry
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
    fun all(parent: AgentModule): List<Tool> = listOf(
        RunModuleTool(parent),
        RunSubAgentTool(parent),
        QueryModuleDataTool(parent),
        QuerySessionHistoryTool(),
        QueryMemoriesTool(),
        ListModulesTool(),
        RunScriptTool(parent),
        QueryGhidraAPITool(),
        RunShellTool(parent)
    )

    /**
     * Register all built-in tools into a [ToolRegistry].
     *
     * ```kotlin
     * BuiltInTools.registerAll(registry, this)
     * ```
     */
    fun registerAll(registry: ToolRegistry, parent: AgentModule) {
        registry.registerAll(all(parent))
    }
}
