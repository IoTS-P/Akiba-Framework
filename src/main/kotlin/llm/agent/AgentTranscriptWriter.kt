package org.iotsplab.akiba.llm.agent

import java.io.BufferedWriter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Writes a Markdown-formatted transcript of agent interactions to a file.
 *
 * The transcript is written in append mode so that repeated runs produce
 * a continuous log. Each entry is timestamped and labeled by role
 * (system, user, assistant, tool-call, tool-result).
 *
 * For tool calls whose arguments contain multiline code (e.g. `run_script`),
 * the source is rendered in a fenced Kotlin code block for readability.
 *
 * The output file lives at `<logDir>/agent_transcript.md`.
 *
 * ### Thread safety
 *
 * A single [AgentTranscriptWriter] is only used by one agent execution at a
 * time (within a single [StrategyContext]), so synchronization is not needed.
 */
class AgentTranscriptWriter(logDir: Path) {

    private val transcriptPath: Path = logDir.resolve("agent_transcript.md")
    private val writer: BufferedWriter

    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** Tools whose "source" or "code" argument should be rendered as a code block. */
    private val codeArgTools = setOf("run_script")

    init {
        Files.createDirectories(logDir)
        writer = Files.newBufferedWriter(
            transcriptPath,
            Charsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        )
    }

    // ---- Public API ----------------------------------------------------------

    /** Write session header at the start of an agent run. */
    fun writeSessionStart(moduleName: String, binaryId: Int, modelName: String, strategy: String) {
        val now = timestamp()
        writer.appendLine()
        writer.appendLine("---")
        writer.appendLine()
        writer.appendLine("# Agent Session — $now")
        writer.appendLine()
        writer.appendLine("| Field | Value |")
        writer.appendLine("|-------|-------|")
        writer.appendLine("| Module | `$moduleName` |")
        writer.appendLine("| Binary ID | $binaryId |")
        writer.appendLine("| Model | `$modelName` |")
        writer.appendLine("| Strategy | $strategy |")
        writer.appendLine("| Started | $now |")
        writer.appendLine()
        flush()
    }

    /** Record the system prompt. */
    fun writeSystemPrompt(prompt: String) {
        writeSection("System Prompt", "system", prompt)
    }

    /** Record a user message (typically the task prompt). */
    fun writeUserMessage(message: String) {
        writeSection("User", "user", message)
    }

    /** Record the assistant's raw response. */
    fun writeAssistantMessage(message: String, iteration: Int) {
        val header = "Assistant (iteration $iteration)"
        writeSection(header, "assistant", message)
    }

    /** Record a tool call (action). */
    fun writeToolCall(toolName: String, argumentsJson: String, arguments: Map<String, Any?>, iteration: Int) {
        val now = timestamp()
        writer.appendLine("### 🔧 Tool Call — `$toolName` <sub>$now | iteration $iteration</sub>")
        writer.appendLine()

        // If the tool has a code argument, render it nicely
        if (toolName in codeArgTools) {
            val source = arguments["source"] as? String
            if (source != null) {
                // Render non-code arguments first
                val otherArgs = arguments.filterKeys { it != "source" }
                if (otherArgs.isNotEmpty()) {
                    writer.appendLine("**Parameters:**")
                    otherArgs.forEach { (k, v) -> writer.appendLine("- `$k`: `$v`") }
                    writer.appendLine()
                }
                writer.appendLine("**Source code:**")
                writer.appendLine()
                writer.appendLine("```kotlin")
                writer.appendLine(source)
                writer.appendLine("```")
                writer.appendLine()
                flush()
                return
            }
        }

        // Default: render the full JSON in a json code block
        writer.appendLine("```json")
        writer.appendLine(argumentsJson)
        writer.appendLine("```")
        writer.appendLine()
        flush()
    }

    /** Record a tool result (observation). */
    fun writeToolResult(toolName: String, result: String, durationMs: Long? = null) {
        val now = timestamp()
        val durationPart = if (durationMs != null) " (${durationMs}ms)" else ""
        writer.appendLine("### 📋 Tool Result — `$toolName`$durationPart <sub>$now</sub>")
        writer.appendLine()

        // If result looks like JSON, use json block; otherwise plain text
        val trimmed = result.trim()
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))
        ) {
            writer.appendLine("```json")
            writer.appendLine(result.take(MAX_RESULT_CHARS))
            writer.appendLine("```")
        } else {
            writer.appendLine("```")
            writer.appendLine(result.take(MAX_RESULT_CHARS))
            writer.appendLine("```")
        }

        if (result.length > MAX_RESULT_CHARS) {
            writer.appendLine()
            writer.appendLine("*... truncated (${result.length} chars total)*")
        }

        writer.appendLine()
        flush()
    }

    /** Record a format reminder injected by the strategy. */
    fun writeFormatReminder(message: String) {
        writeSection("Format Reminder (injected)", "info", message)
    }

    /** Record the final result of the agent run. */
    fun writeSessionEnd(result: AgentResult) {
        val now = timestamp()
        writer.appendLine("---")
        writer.appendLine()
        writer.appendLine("## Session Complete — $now")
        writer.appendLine()
        writer.appendLine("| Metric | Value |")
        writer.appendLine("|--------|-------|")
        writer.appendLine("| Stop Reason | ${result.stopReason} |")
        writer.appendLine("| Iterations | ${result.iterations} |")
        writer.appendLine("| Tool Calls | ${result.toolCallsMade} |")
        writer.appendLine("| Input Tokens | ${result.totalInputTokens} |")
        writer.appendLine("| Output Tokens | ${result.totalOutputTokens} |")
        writer.appendLine()
        writer.appendLine("### Final Output")
        writer.appendLine()
        writer.appendLine(result.output)
        writer.appendLine()
        writer.appendLine("---")
        writer.appendLine()
        flush()
    }

    /** Flush and close the writer. Call this when the agent run finishes. */
    fun close() {
        try {
            writer.flush()
            writer.close()
        } catch (_: IOException) { }
    }

    // ---- Internal ------------------------------------------------------------

    private fun writeSection(header: String, role: String, content: String) {
        val now = timestamp()
        writer.appendLine("### ${roleEmoji(role)} $header <sub>$now</sub>")
        writer.appendLine()
        writer.appendLine(content)
        writer.appendLine()
        flush()
    }

    private fun roleEmoji(role: String): String = when (role) {
        "system" -> "⚙️"
        "user" -> "👤"
        "assistant" -> "🤖"
        "info" -> "ℹ️"
        else -> "📝"
    }

    private fun timestamp(): String = LocalDateTime.now().format(timeFormatter)

    private fun flush() {
        try { writer.flush() } catch (_: IOException) { }
    }

    companion object {
        /** Maximum characters for tool results in the transcript. */
        private const val MAX_RESULT_CHARS = 10000
    }
}
