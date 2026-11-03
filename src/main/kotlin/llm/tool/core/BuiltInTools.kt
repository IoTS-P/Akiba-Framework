package org.iotsplab.akiba.llm.tool.core

import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.agent.module.AgentModule
import org.iotsplab.akiba.llm.tool.analysis.ListModulesTool
import org.iotsplab.akiba.llm.tool.analysis.QueryGhidraAPITool
import org.iotsplab.akiba.llm.tool.analysis.QueryModuleDataTool
import org.iotsplab.akiba.llm.tool.analysis.ReadSkillTool
import org.iotsplab.akiba.llm.tool.analysis.RunModuleTool
import org.iotsplab.akiba.llm.tool.analysis.RunScriptTool
import org.iotsplab.akiba.llm.tool.analysis.ScriptLibraryTool
import org.iotsplab.akiba.llm.tool.analysis.SearchSkillTool
import org.iotsplab.akiba.llm.tool.coordination.AgentBuilderAlternativesTool
import org.iotsplab.akiba.llm.tool.coordination.AwaitMultipleChildrenTool
import org.iotsplab.akiba.llm.tool.coordination.GetAgentStatusTool
import org.iotsplab.akiba.llm.tool.coordination.ListSubAgentsTool
import org.iotsplab.akiba.llm.tool.coordination.SpawnSubAgentTool
import org.iotsplab.akiba.llm.tool.history.QueryMemoriesTool
import org.iotsplab.akiba.llm.tool.history.QuerySessionHistoryTool
import org.iotsplab.akiba.llm.tool.history.ReadHistoryToolCallTool
import org.iotsplab.akiba.llm.tool.history.SearchHistoryToolCallsTool
import org.iotsplab.akiba.llm.tool.ui.AskUserChoiceTool
import org.iotsplab.akiba.llm.tool.workspace.RunShellTool
import org.iotsplab.akiba.llm.tool.workspace.WorkspaceFileTools
import org.iotsplab.akiba.module.AkibaModule

// ============================================================
//  BuiltInTools — agent-accessible built-in tool collection
// ============================================================

/**
 * Factory object that provides ready-made [Tool] instances for common agent
 * operations.
 *
 * Tool definitions are split across the `core` (this factory, [Tool], parsing),
 * `analysis`, `coordination`, `history`, `workspace` and `ui` subpackages;
 * [all] assembles the default set, e.g.
 * `override fun defineTools(): List<Tool> = BuiltInTools.all(this)`.
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
