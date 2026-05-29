package org.iotsplab.akiba.llm.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.*
import org.iotsplab.akiba.llm.agent.Tool
import org.iotsplab.akiba.llm.agent.ToolParameter
import org.iotsplab.akiba.module.AkibaModule
import java.io.InputStream

/**
 * Create a tool that allows the agent to execute shell commands.
 *
 * ### Security considerations
 *
 * - **Working directory**: Commands execute in the module's workspace directory
 *   (`~/.akiba/workspace/<Module>/<id>/`) by default. The agent is instructed to
 *   avoid absolute paths unless strictly necessary.
 * - **User confirmation**: By default, every command execution requires manual
 *   approval from the user via stdin (`y/n` prompt). This prevents unintended
 *   destructive operations.
 * - **Timeout**: Commands have a configurable timeout (default 60 seconds) to
 *   prevent long-running processes from blocking the agent loop.
 * - **Output buffering**: stdout/stderr are consumed in real-time by dedicated
 *   coroutines on [Dispatchers.IO] with a fixed-size buffer. Once the buffer is
 *   full, subsequent output is discarded (but the stream is still drained to
 *   prevent the child process from blocking on a full pipe).
 * - **Cancellation-safe**: If the enclosing coroutine (e.g. AkibaModule's
 *   timeout) is cancelled, the child process is immediately killed and all
 *   drain coroutines are cancelled, ensuring no resource leaks.
 *
 * ### Usage by the agent
 *
 * ```
 * run_shell {"command": "ls -la", "timeout": 30}
 * ```
 *
 * The agent receives a JSON response with `exitCode`, `stdout`, `stderr`, and
 * `workingDirectory`.
 *
 * @param parent The parent [AkibaModule] whose workspace dir is used as CWD.
 * @param requireConfirmation When true (default), the user must approve each
 *        command. Set to false only in fully automated/test environments.
 * @param maxOutputChars Maximum characters retained for stdout/stderr each.
 *        Beyond this limit, output is still read (to unblock the process) but
 *        discarded.
 */
fun RunShellTool(
    parent: AkibaModule,
    requireConfirmation: Boolean = true,
    maxOutputChars: Int = 8000
): Tool = Tool(
    name = "run_shell",
    description = buildString {
        appendLine("Execute a shell command in the module's workspace directory.")
        appendLine()
        appendLine("⚠️  WARNING: This tool is a LAST RESORT. Before using it, consider:")
        appendLine("  - Use `run_script` for ANY Ghidra binary analysis (listing functions,")
        appendLine("    decompiling, finding references, reading memory, etc.)")
        appendLine("  - Use `query_ghidra_api` to look up API classes and methods")
        appendLine("  - Use `query_module_data` to retrieve data from other modules")
        appendLine()
        appendLine("Only use run_shell when you need system utilities that cannot be accessed")
        appendLine("through Ghidra APIs or other tools (e.g. downloading a file, converting formats).")
        appendLine()
        appendLine("Constraints:")
        appendLine("  - Each command requires user confirmation (execution may be denied)")
        appendLine("  - The workspace directory is EMPTY by default — no binary files are in it")
        appendLine("  - The loaded binary is accessible ONLY via `currentProgram` in run_script")
        appendLine("  - Do NOT use shell for: objdump, readelf, strings, nm — use run_script instead,")
        appendLine("    which has direct access to the same information via Ghidra APIs")
    },
    parameters = listOf(
        ToolParameter(
            "command", "string",
            "The shell command to execute. Prefer relative paths within the workspace.",
            required = true
        ),
        ToolParameter(
            "timeout", "integer",
            "Timeout in seconds (default 60, max 300). Command is killed if exceeded.",
            required = false
        ),
        ToolParameter(
            "workDir", "string",
            "Optional relative subdirectory within the workspace to use as CWD. " +
                "Defaults to the workspace root.",
            required = false
        )
    )
) { args ->
    val mapper = jacksonObjectMapper()
    val command = args["command"] as? String
        ?: return@Tool "Error: 'command' parameter is required"

    if (command.isBlank()) {
        return@Tool "Error: command is empty"
    }

    val timeout = ((args["timeout"] as? Number)?.toInt() ?: 60).coerceIn(1, 300)
    val workDirRel = args["workDir"] as? String

    // Resolve working directory
    val cwd = if (workDirRel != null) {
        parent.resolveWorkspacePath(workDirRel).toFile().also {
            if (!it.exists()) it.mkdirs()
        }
    } else {
        parent.workspaceDir.toFile()
    }

    // User confirmation gate
    if (requireConfirmation) {
        System.err.println()
        System.err.println("┌─────────────────────────────────────────────────────────")
        System.err.println("│ [run_shell] Agent wants to execute:")
        System.err.println("│   Command: $command")
        System.err.println("│   CWD:     ${cwd.absolutePath}")
        System.err.println("│   Timeout: ${timeout}s")
        System.err.println("├─────────────────────────────────────────────────────────")
        System.err.print("│ Allow execution? [y/N]: ")
        System.err.flush()

        val response = try {
            System.`in`.bufferedReader().readLine()?.trim()?.lowercase()
        } catch (_: Exception) {
            null
        }

        if (response != "y" && response != "yes") {
            System.err.println("│ ✗ Denied by user.")
            System.err.println("└─────────────────────────────────────────────────────────")
            return@Tool mapper.writeValueAsString(mapOf(
                "denied" to true,
                "message" to "Command execution denied by user.",
                "command" to command
            ))
        }
        System.err.println("│ ✓ Approved.")
        System.err.println("└─────────────────────────────────────────────────────────")
    }

    // Execute the command inside a coroutine scope that:
    //  - Has its own timeout (the tool-level timeout)
    //  - Kills the child process on cancellation (either by tool timeout or
    //    by the parent AkibaModule's coroutine being cancelled externally)
    //  - Drains stdout/stderr on Dispatchers.IO coroutines with fixed buffers
    runBlocking {
        var process: Process? = null
        try {
            process = ProcessBuilder("/bin/sh", "-c", command)
                .directory(cwd)
                .redirectErrorStream(false)
                .start()

            val result = withTimeout(timeout * 1000L) {
                // Launch drain coroutines that consume output on IO dispatcher.
                // When this scope is cancelled, these coroutines are cancelled too;
                // the finally block below calls destroyForcibly() which closes
                // the streams, causing the blocking read() to throw and exit.
                val stdoutDeferred = async(Dispatchers.IO) {
                    drainStream(process.inputStream, maxOutputChars)
                }
                val stderrDeferred = async(Dispatchers.IO) {
                    drainStream(process.errorStream, maxOutputChars)
                }

                // Wait for process exit on an IO thread. withTimeout will cancel
                // us if the deadline is exceeded; CancellationException propagates
                // to the outer try where we destroyForcibly().
                val exitCode = withContext(Dispatchers.IO) {
                    process.waitFor()
                }

                val (stdout, stdoutTruncated) = stdoutDeferred.await()
                val (stderr, stderrTruncated) = stderrDeferred.await()

                mapper.writeValueAsString(mapOf(
                    "exitCode" to exitCode,
                    "stdout" to stdout,
                    "stderr" to stderr,
                    "stdoutTruncated" to stdoutTruncated,
                    "stderrTruncated" to stderrTruncated,
                    "workingDirectory" to cwd.absolutePath
                ))
            }
            result
        } catch (_: TimeoutCancellationException) {
            process?.destroyForcibly()
            mapper.writeValueAsString(mapOf(
                "exitCode" to -1,
                "error" to "Command timed out after ${timeout}s and was killed.",
                "workingDirectory" to cwd.absolutePath,
                "timedOut" to true
            ))
        } catch (_: CancellationException) {
            // Parent coroutine (AkibaModule) was cancelled — kill process immediately
            process?.destroyForcibly()
            mapper.writeValueAsString(mapOf(
                "exitCode" to -1,
                "error" to "Execution cancelled (parent module terminated).",
                "workingDirectory" to cwd.absolutePath,
                "cancelled" to true
            ))
        } catch (e: Exception) {
            process?.destroyForcibly()
            mapper.writeValueAsString(mapOf(
                "exitCode" to -1,
                "error" to "Failed to execute command: ${e.message}",
                "workingDirectory" to cwd.absolutePath
            ))
        }
    }
}

/**
 * Drain an [InputStream] into a fixed-capacity buffer.
 *
 * Reads 4 KB chunks. Once [maxChars] is reached, continues draining (to
 * prevent the child process from blocking on a full pipe) but discards data.
 *
 * This function runs on [Dispatchers.IO]. When the parent coroutine is
 * cancelled and the process is killed (destroyForcibly), the stream is
 * closed, causing `read()` to throw an IOException which breaks the loop.
 *
 * @return Pair of (captured output, whether truncation occurred).
 */
private fun drainStream(stream: InputStream, maxChars: Int): Pair<String, Boolean> {
    val buffer = StringBuilder(minOf(maxChars, 16384))
    var truncated = false
    try {
        val reader = stream.bufferedReader(Charsets.UTF_8)
        val chunk = CharArray(4096)
        while (true) {
            val n = reader.read(chunk)
            if (n < 0) break
            if (buffer.length < maxChars) {
                val remaining = maxChars - buffer.length
                if (n <= remaining) {
                    buffer.append(chunk, 0, n)
                } else {
                    buffer.append(chunk, 0, remaining)
                    truncated = true
                }
            }
            // Buffer full: keep reading to unblock the process, discard data.
        }
    } catch (_: Exception) {
        // Stream closed or IO error — expected during process kill / cancellation.
    }
    return buffer.toString() to truncated
}
