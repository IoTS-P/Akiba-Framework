package org.iotsplab.akiba.llm.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.*
import org.iotsplab.akiba.llm.agent.AgentModule
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
    parent: AgentModule,
    requireConfirmation: Boolean = true,
    maxOutputChars: Int = 40000
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
        appendLine("  - The workspace directory contains a `binary` symlink pointing to a copy of")
        appendLine("    the analyzed binary file. You can use `./binary` to access it with tools")
        appendLine("    like xxd, file, binwalk, etc. This copy is separate from the original —")
        appendLine("    modifying it does NOT affect the Ghidra project's binary.")
        appendLine("  - The loaded binary is also accessible via `program` in run_script")
        appendLine("  - Do NOT use shell for: objdump, readelf, strings, nm — use run_script instead,")
        appendLine("    which has direct access to the same information via Ghidra APIs")
        appendLine()
        appendLine("Working directory:")
        appendLine("  - Relative paths resolve within the workspace (default: workspace root).")
        appendLine("  - Absolute paths are accepted for workDir, but using a directory **outside**")
        appendLine("    the workspace requires user confirmation before the command runs.")
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
            "Optional relative subdirectory within the workspace, or an absolute path. " +
                "Defaults to the workspace root. Absolute paths outside the workspace " +
                "require user confirmation.",
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
    //
    // Relative paths resolve within the workspace (the default). Absolute
    // paths are accepted, but if the resolved CWD is outside the workspace
    // boundary the user must confirm before the command can run.
    val cwd = if (workDirRel != null) {
        val isAbsolute = workDirRel.startsWith("/") ||
            (workDirRel.length >= 2 && workDirRel[1] == ':') ||
            workDirRel.startsWith("\\")
        if (isAbsolute) {
            val absPath = java.nio.file.Paths.get(workDirRel).normalize().toFile()
            // Check if this is outside the workspace
            val workspaceRoot = parent.workspaceDir.normalize()
            val resolvedAbs = absPath.toPath().normalize()
            if (!resolvedAbs.startsWith(workspaceRoot)) {
                // Outside workspace — require confirmation
                val sessionId = (parent as? org.iotsplab.akiba.llm.agent.AgentModule)?.agentSessionId
                val serverPort = detectWorkerServerPort()
                val approved = if (!sessionId.isNullOrBlank() && serverPort != null) {
                    requestConfirmationViaHttp(
                        serverPort = serverPort,
                        sessionId = sessionId,
                        toolName = "run_shell",
                        command = "use CWD: ${absPath.absolutePath}",
                        workingDirectory = "(outside workspace)",
                        timeout = 0,
                        action = "file_access",
                        targetPath = absPath.absolutePath
                    )
                } else if (!sessionId.isNullOrBlank()) {
                    ConfirmationManager.requestFileAccessConfirmationBlocking(
                        sessionId = sessionId,
                        toolName = "run_shell",
                        operation = "use CWD",
                        targetPath = absPath.absolutePath
                    )
                } else {
                    false
                }
                if (!approved) {
                    return@Tool mapper.writeValueAsString(mapOf(
                        "denied" to true,
                        "message" to "User denied use of working directory outside workspace: ${absPath.absolutePath}",
                        "command" to command
                    ))
                }
            }
            if (!absPath.exists()) absPath.mkdirs()
            absPath
        } else {
            parent.resolveWorkspacePath(workDirRel).toFile().also {
                if (!it.exists()) it.mkdirs()
            }
        }
    } else {
        parent.workspaceDir.toFile()
    }

    // ---- User confirmation gate ----
    //
    // When [requireConfirmation] is true the tool blocks until the user
    // approves or denies the command. Three backends are supported:
    //
    // 1. HTTP callback (when running in a separate worker process):
    //    The worker POSTs to `POST /agent/internal/confirmation/request`
    //    on the server and blocks on the HTTP response (long-poll). The
    //    server registers the request in [ConfirmationManager] (visible to
    //    the frontend) and suspends until the user responds via
    //    `POST .../confirmation/respond`.
    //
    // 2. In-process (when running in the same process as the server):
    //    The request is registered in [ConfirmationManager] directly and
    //    the tool thread blocks on a CompletableDeferred (with a 5-minute
    //    timeout) until the user responds.
    //
    // 3. Stdin fallback (when no session ID is available — e.g. CLI mode):
    //    The classic y/N prompt on System.in. Used in development / testing.
    //
    if (requireConfirmation) {
        val sessionId = (parent as? org.iotsplab.akiba.llm.agent.AgentModule)?.agentSessionId
        val serverPort = detectWorkerServerPort()

        val approved = when {
            // Cross-process: worker → HTTP long-poll → server → ConfirmationManager
            !sessionId.isNullOrBlank() && serverPort != null -> {
                requestConfirmationViaHttp(
                    serverPort = serverPort,
                    sessionId = sessionId,
                    toolName = "run_shell",
                    command = command,
                    workingDirectory = cwd.absolutePath,
                    timeout = timeout,
                    action = "shell_command"
                )
            }
            // In-process: tool and HTTP server share the same JVM
            !sessionId.isNullOrBlank() -> {
                ConfirmationManager.requestConfirmationBlocking(
                    sessionId = sessionId,
                    toolName = "run_shell",
                    command = command,
                    workingDirectory = cwd.absolutePath,
                    timeout = timeout,
                    action = "shell_command"
                )
            }
            // Stdin fallback for CLI / dev mode
            else -> {
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
                response == "y" || response == "yes"
            }
        }

        if (!approved) {
            return@Tool mapper.writeValueAsString(mapOf(
                "denied" to true,
                "message" to "Command execution denied by user.",
                "command" to command
            ))
        }
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
