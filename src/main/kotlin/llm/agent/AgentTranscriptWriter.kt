package org.iotsplab.akiba.llm.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Appends a Markdown-formatted transcript of agent interactions to the
 * database's session transcript field.
 *
 * Each interaction (system prompt, user message, assistant reply, tool call,
 * tool result, format reminder, session end) is formatted as a Markdown
 * section and appended immediately to the DB via [AgentDatabaseClient.appendTranscript].
 *
 * No local file is written — the transcript lives entirely in the database
 * and is accessible via the session export endpoint.
 *
 * ### Thread safety
 *
 * A single [AgentTranscriptWriter] is only used by one agent execution at a
 * time (within a single [StrategyContext]), so synchronization is not needed.
 */
class AgentTranscriptWriter(
    private val agentDbClient: AgentDatabaseClient?,
    private val sessionId: String?
) {

    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** Tools whose "source" or "code" argument should be rendered as a code block. */
    private val codeArgTools = setOf("run_script")

    // ---- Public API ----------------------------------------------------------

    /** Write session header at the start of an agent run. */
    fun writeSessionStart(moduleName: String, binaryId: Int, modelName: String, strategy: String) {
        val now = timestamp()
        val md = buildString {
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("# Agent Session — $now")
            appendLine()
            appendLine("| Field | Value |")
            appendLine("|-------|-------|")
            appendLine("| Module | `$moduleName` |")
            appendLine("| Binary ID | $binaryId |")
            appendLine("| Model | `$modelName` |")
            appendLine("| Strategy | $strategy |")
            appendLine("| Started | $now |")
            appendLine()
        }
        appendToDb(md)
    }

    /** Record the system prompt. */
    fun writeSystemPrompt(prompt: String) {
        writeSection("System Prompt", "system", prompt)
    }

    /** Record a user message (typically the task prompt). */
    fun writeUserMessage(message: String) {
        writeSection("User", "user", message)
    }

    /** Record the assistant's raw response, with optional cumulative token usage. */
    fun writeAssistantMessage(
        message: String,
        iteration: Int,
        totalInputTokens: Int = 0,
        totalOutputTokens: Int = 0
    ) {
        val header = "Assistant (iteration $iteration)"
        writeSection(header, "assistant", message)

        // Append cumulative token usage statistics after each iteration
        if (totalInputTokens > 0 || totalOutputTokens > 0) {
            val md = "**📊 Token Usage (cumulative):** input=`$totalInputTokens` | output=`$totalOutputTokens` | total=`${totalInputTokens + totalOutputTokens}`\n\n"
            appendToDb(md)
        }
    }

    /** Record a tool call (action). */
    fun writeToolCall(toolName: String, argumentsJson: String, arguments: Map<String, Any?>, iteration: Int) {
        val now = timestamp()
        val md = buildString {
            appendLine("### 🔧 Tool Call — `$toolName` <sub>$now | iteration $iteration</sub>")
            appendLine()

            // If the tool has a code argument, render it nicely
            if (toolName in codeArgTools) {
                val source = arguments["source"] as? String
                if (source != null) {
                    val otherArgs = arguments.filterKeys { it != "source" }
                    if (otherArgs.isNotEmpty()) {
                        appendLine("**Parameters:**")
                        otherArgs.forEach { (k, v) -> appendLine("- `$k`: `$v`") }
                        appendLine()
                    }
                    appendLine("**Source code:**")
                    appendLine()
                    appendLine("```kotlin")
                    appendLine(source)
                    appendLine("```")
                    appendLine()
                    appendToDb(toString())
                    return
                }
            }

            // Default: render the full JSON in a json code block
            appendLine("```json")
            appendLine(argumentsJson)
            appendLine("```")
            appendLine()
        }
        appendToDb(md)
    }

    /** Record a tool result (observation). */
    fun writeToolResult(toolName: String, result: String, durationMs: Long? = null) {
        val now = timestamp()
        val durationPart = if (durationMs != null) " (${durationMs}ms)" else ""
        val md = buildString {
            appendLine("### 📋 Tool Result — `$toolName`$durationPart <sub>$now</sub>")
            appendLine()

            val trimmed = result.trim()

            // Special handling for run_script: parse JSON and render as multi-line fields
            if (toolName == "run_script" && trimmed.startsWith("{")) {
                append(writeRunScriptResult(trimmed))
            } else if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                (trimmed.startsWith("[") && trimmed.endsWith("]"))
            ) {
                appendLine("```json")
                appendLine(result.take(MAX_RESULT_CHARS))
                appendLine("```")
            } else {
                appendLine("```")
                appendLine(result.take(MAX_RESULT_CHARS))
                appendLine("```")
            }

            if (result.length > MAX_RESULT_CHARS) {
                appendLine()
                appendLine("*... truncated (${result.length} chars total)*")
            }

            appendLine()
        }
        appendToDb(md)
    }

    /** Record a format reminder injected by the strategy. */
    fun writeFormatReminder(message: String) {
        writeSection("Format Reminder (injected)", "info", message)
    }

    /** Record the final result of the agent run. */
    fun writeSessionEnd(result: AgentResult) {
        val now = timestamp()
        val md = buildString {
            appendLine("---")
            appendLine()
            appendLine("## Session Complete — $now")
            appendLine()
            appendLine("| Metric | Value |")
            appendLine("|--------|-------|")
            appendLine("| Stop Reason | ${result.stopReason} |")
            appendLine("| Iterations | ${result.iterations} |")
            appendLine("| Tool Calls | ${result.toolCallsMade} |")
            appendLine("| Input Tokens | ${result.totalInputTokens} |")
            appendLine("| Output Tokens | ${result.totalOutputTokens} |")
            appendLine()
            appendLine("### Final Output")
            appendLine()
            appendLine(result.output)
            appendLine()
            appendLine("---")
            appendLine()
        }
        appendToDb(md)
    }

    /** No-op — content is already persisted to DB incrementally. */
    fun close() {
        // Nothing to close
    }

    // ---- Internal ------------------------------------------------------------

    private fun writeSection(header: String, role: String, content: String) {
        val now = timestamp()
        val md = "### ${roleEmoji(role)} $header <sub>$now</sub>\n\n$content\n\n"
        appendToDb(md)
    }

    /**
     * Render a [run_script] JSON result as a readable multi-line summary.
     * Returns the Markdown string instead of writing directly.
     */
    private fun writeRunScriptResult(jsonStr: String): String = buildString {
        try {
            val mapper = jacksonObjectMapper()
            val node: JsonNode = mapper.readTree(jsonStr)

            val success = node.path("success").asBoolean(false)
            val failureSign = node.path("failureSign").asText("FAILED")
            val statusIcon = if (success) "✅ SUCCESS" else "❌ $failureSign"
            appendLine("**Status:** $statusIcon")
            appendLine()

            val output = node.path("output").asText()
            if (output.isNotBlank()) {
                appendLine("**Output:**")
                appendLine()
                appendLine("```text")
                appendLine(output.take(MAX_RESULT_CHARS))
                appendLine("```")
                if (output.length > MAX_RESULT_CHARS) {
                    appendLine()
                    appendLine("*... output truncated (${output.length} chars total)*")
                }
                appendLine()
            } else {
                appendLine("**Output:** *(empty)*")
                appendLine()
            }

            val data = node.path("data")
            if (!data.isNull && !data.isMissingNode) {
                appendLine("**Data:**")
                appendLine()
                appendLine("```json")
                val dataStr = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data)
                appendLine(dataStr.take(MAX_RESULT_CHARS))
                appendLine("```")
                if (dataStr.length > MAX_RESULT_CHARS) {
                    appendLine()
                    appendLine("*... data truncated*")
                }
                appendLine()
            }

            val execMs = node.path("executionTimeMs").asLong(-1)
            val totalMs = node.path("totalTimeMs").asLong(-1)
            val timingParts = mutableListOf<String>()
            if (execMs >= 0) timingParts.add("Execution: `${execMs}ms`")
            if (totalMs >= 0) timingParts.add("Total: `${totalMs}ms`")
            if (timingParts.isNotEmpty()) {
                appendLine("**Timing:** ${timingParts.joinToString(" | ")}")
                appendLine()
            }
        } catch (_: Exception) {
            appendLine("```json")
            appendLine(jsonStr.take(MAX_RESULT_CHARS))
            appendLine("```")
        }
    }

    private fun roleEmoji(role: String): String = when (role) {
        "system" -> "⚙️"
        "user" -> "👤"
        "assistant" -> "🤖"
        "info" -> "ℹ️"
        else -> "📝"
    }

    private fun timestamp(): String = LocalDateTime.now().format(timeFormatter)

    private fun appendToDb(content: String) {
        if (sessionId != null && agentDbClient != null) {
            try {
                agentDbClient.appendTranscript(sessionId, content)
            } catch (_: Exception) {
                // Non-critical; transcript append failures should not crash the agent
            }
        }
    }

    companion object {
        /** Maximum characters for tool results in the transcript. */
        private const val MAX_RESULT_CHARS = 10000
    }
}
