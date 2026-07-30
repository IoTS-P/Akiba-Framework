package org.iotsplab.akiba.llm.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.agent.AgentModule
import org.iotsplab.akiba.llm.client.LLMClientFactory
import org.iotsplab.akiba.llm.memory.AgentChatMessage

// ============================================================
//  BtwAssistant — constrained side assistant for follow-up questions
// ============================================================

/**
 * Answers the user's follow-up ("BTW") questions while an
 * `ask_user_choice` request is pending. Guard rails:
 *
 * - **Read-only tools only** — no `run_shell`, no writes, nothing
 *   requiring user confirmation (nested modals would deadlock the UI),
 *   no `ask_user_choice` (recursion).
 * - **Pinned session history** — `read_current_session_history` is bound
 *   to the session that raised the choice; arbitrary sessions are
 *   unreachable (`query_session_history` is excluded for this reason).
 * - **Iteration cap** — at most [MAX_TOOL_ROUNDS] tool rounds per question.
 * - **No main-memory pollution** — private message list, nothing written
 *   to the agent's memory or DB; `ask_user_choice` embeds the BTW
 *   transcript in its result instead.
 */
class BtwAssistant(
    private val parent: AgentModule,
    choiceQuestion: String,
    choiceOptions: List<String>
) {
    companion object {
        /** Maximum tool-use rounds per follow-up question. */
        const val MAX_TOOL_ROUNDS = 3

        /** Per-tool-result size cap fed back into the side conversation. */
        const val MAX_TOOL_RESULT_CHARS = 4000

        /** Read-only tool names the side assistant may use. */
        private val ALLOWED_TOOL_NAMES = setOf(
            "query_module_data",
            "query_ghidra_api",
            "read_workspace_file",
            "list_workspace_dir",
            "grep_workspace",
            "search_history_tool_calls",
            "read_history_tool_call",
            "query_memories",
            "read_current_session_history"
        )
    }

    private val tools: List<Tool> = buildTools()
    private val systemPrompt: String = buildSystemPrompt(choiceQuestion, choiceOptions)

    /** Build the read-only built-in subset plus the pinned history tool. */
    private fun buildTools(): List<Tool> {
        val sessionId = parent.agentSessionId
        val all = BuiltInTools.all(parent, parent.agentDbClient)
        val filtered = all.filter { it.name in ALLOWED_TOOL_NAMES }.toMutableList()
        if (!sessionId.isNullOrBlank()) {
            filtered += PinnedSessionHistoryTool(parent.agentDbClient, sessionId)
        }
        return filtered
    }

    private fun buildSystemPrompt(choiceQuestion: String, choiceOptions: List<String>): String {
        val toolDocs = tools.joinToString("\n") { tool ->
            val params = tool.parameters.joinToString(", ") { p ->
                "${p.name}: ${p.type}${if (p.required) "" else " (optional)"}"
            }
            "- ${tool.name}($params): ${tool.description.lineSequence().firstOrNull() ?: ""}"
        }
        return buildString {
            appendLine("You are a helper assistant answering FOLLOW-UP questions from the user.")
            appendLine("The main analysis agent is paused, waiting for the user to decide on a")
            appendLine("multiple-choice question. Your ONLY job is to give the user the information")
            appendLine("they need to make that decision.")
            appendLine()
            appendLine("The pending question shown to the user is:")
            appendLine("  \"$choiceQuestion\"")
            appendLine("The offered options are:")
            choiceOptions.forEachIndexed { i, opt -> appendLine("  ${i + 1}. $opt") }
            appendLine()
            appendLine("Rules:")
            appendLine("- Answer concisely and factually; the user is waiting.")
            appendLine("- You may use the read-only tools below to inspect the analysis state")
            appendLine("  and the agent's session history. Do NOT attempt any write/execute action.")
            appendLine("- Never ask the user new questions; if you cannot answer, say so honestly")
            appendLine("  and suggest what the main agent could do once it resumes.")
            appendLine("- Tool call format (one per fenced block):")
            appendLine("  ```json")
            appendLine("  {\"tool_call\": {\"name\": \"<tool>\", \"arguments\": {<args>}}}")
            appendLine("  ```")
            appendLine("- When you have enough information, answer directly in plain text.")
            appendLine()
            appendLine("Available tools:")
            appendLine(toolDocs)
        }
    }

    /**
     * Answer one follow-up question via a bounded text-based tool loop
     * (max [MAX_TOOL_ROUNDS] rounds, then a forced final answer).
     * Blocking — call from a worker thread.
     */
    fun answer(userQuestion: String): String {
        val config = parent.publicLLMConfig()
        LLMClientFactory.create(config).use { client ->
            val messages = mutableListOf(
                AgentChatMessage(role = "user", content = userQuestion)
            )

            repeat(MAX_TOOL_ROUNDS) {
                val completion = client.chat(systemPrompt, messages, tools = null)
                val calls = ToolCallParser.parseAllFromCompletion(completion)
                if (calls.isEmpty()) {
                    return ToolCallParser.stripThinking(completion.content).ifBlank {
                        "(the assistant returned an empty answer)"
                    }
                }
                messages.add(AgentChatMessage(role = "assistant", content = completion.content))
                for (call in calls.take(4)) {
                    val tool = tools.firstOrNull { it.name == call.name }
                    val result = when {
                        tool == null ->
                            "Error: tool '${call.name}' is not available to the follow-up assistant."
                        else -> runCatching { tool.safeExecute(call.arguments) }
                            .getOrElse { "Tool '${call.name}' failed: ${it.message}" }
                    }
                    messages.add(AgentChatMessage(
                        role = "user",
                        content = "**Observation (${call.name}):** ${result.take(MAX_TOOL_RESULT_CHARS)}"
                    ))
                }
            }

            // Tool budget exhausted — force a final answer without tools.
            messages.add(AgentChatMessage(
                role = "user",
                content = "You have used your tool budget. Now answer the user's " +
                    "follow-up question directly with what you have learned."
            ))
            val final = client.chat(systemPrompt, messages, tools = null)
            return ToolCallParser.stripThinking(final.content).ifBlank {
                "(the assistant could not produce an answer)"
            }
        }
    }
}

// ============================================================
//  PinnedSessionHistoryTool — read THIS session's messages only
// ============================================================

/**
 * Read-only tool that pages through the message history of the session
 * that raised the `ask_user_choice` request. The sessionId is pinned at
 * construction time (NOT a tool parameter), so the LLM cannot read
 * arbitrary sessions.
 */
fun PinnedSessionHistoryTool(
    agentDbClient: AgentDatabaseClient,
    pinnedSessionId: String
): Tool = Tool(
    name = "read_current_session_history",
    description = buildString {
        appendLine("Read the message history of the CURRENT agent session (the one that")
        appendLine("asked the user the pending question). Use it to recall what the agent")
        appendLine("has discovered, which tools it ran, and what their results were.")
        appendLine("Returns a JSON array of {index, role, content, toolName, toolResult}.")
    },
    parameters = listOf(
        ToolParameter(
            "fromIndex", "integer",
            "Start message index (pagination). Default: the most recent messages.",
            required = false
        ),
        ToolParameter(
            "limit", "integer",
            "Maximum number of messages to return. Default: 30, max: 100.",
            required = false
        )
    )
) { args ->
    val mapper = jacksonObjectMapper()
    val limit = ((args["limit"] as? Number)?.toInt() ?: 30).coerceIn(1, 100)
    val fromIndex = (args["fromIndex"] as? Number)?.toInt()

    try {
        val messages = if (fromIndex != null) {
            agentDbClient.getMessages(pinnedSessionId, fromIndex.coerceAtLeast(0), limit)
        } else {
            // Default: latest `limit` messages — fetch a generous window
            // from 0 and take the tail, since the daemon may not support
            // "last N" semantics directly.
            agentDbClient.getMessages(pinnedSessionId, 0, 500).takeLast(limit)
        }
        val summaries = messages.map { msg ->
            mapOf(
                "index" to msg.messageIndex,
                "role" to msg.role,
                "content" to msg.content?.take(2000),
                "toolName" to msg.toolName,
                "toolResult" to msg.toolResult?.take(1000)
            )
        }
        mapper.writeValueAsString(summaries)
    } catch (e: Exception) {
        "Error reading session history: ${e.message}"
    }
}
