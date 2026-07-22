package org.iotsplab.akiba.llm.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.iotsplab.akiba.module.AkibaModule
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.pathString

// ============================================================
//  WorkspaceFileTools — sandboxed file I/O for LLM agents
// ============================================================

/**
 * Build all workspace file tools for the given [parent] [AkibaModule].
 *
 * These tools allow the LLM agent to read, write, list, move, and delete
 * files **strictly within** the module's workspace directory.  All paths
 * are validated against path-traversal attacks before any I/O is performed.
 *
 * Available tools:
 * - `read_workspace_file`   — read file contents (text)
 * - `write_workspace_file`  — create or overwrite a file
 * - `list_workspace_dir`    — list directory contents
 * - `create_workspace_dir`  — create directories
 * - `move_workspace_file`   — move or rename files / directories
 * - `delete_workspace_file` — delete files or directories
 * - `grep_workspace`       — search file contents with regex
 *
 * ```kotlin
 * override fun defineTools(): List<Tool> = BuiltInTools.all(this, agentDbClient) + WorkspaceFileTools(this)
 * ```
 */
fun WorkspaceFileTools(parent: AkibaModule): List<Tool> = listOf(
    ReadWorkspaceFileTool(parent),
    WriteWorkspaceFileTool(parent),
    ListWorkspaceDirTool(parent),
    CreateWorkspaceDirTool(parent),
    MoveWorkspaceFileTool(parent),
    DeleteWorkspaceFileTool(parent),
    GrepWorkspaceTool(parent),
)

// ============================================================
//  Security helpers
// ============================================================

private val wsMapper = jacksonObjectMapper()

/** Maximum characters returned by [ReadWorkspaceFileTool] (≈ 200 KB of text). */
private const val MAX_READ_CHARS = 200_000

/** Maximum characters accepted by [WriteWorkspaceFileTool] (≈ 1 MB of text). */
private const val MAX_WRITE_CHARS = 1_000_000

/**
 * Resolve and validate a path within the workspace directory.
 *
 * Security checks (Rule 3: Path Traversal & Zip Slip — CWE-22):
 * - Reject absolute paths (starting with `/` or containing Windows drive letters)
 * - Reject paths containing `..` path components
 * - Reject paths containing NUL bytes
 * - Normalize the resolved path and verify it is within the workspace boundary
 * - If the file exists, verify the real (symlink-resolved) path is still inside the workspace
 *
 * @param parent          The owning [AkibaModule].
 * @param relativePath    The relative path provided by the LLM.
 * @return The validated absolute [Path] within the workspace, or `null` if the path is invalid.
 */
private fun resolveAndValidateWorkspacePath(parent: AkibaModule, relativePath: String): Path? {
    if (relativePath.isBlank()) return null
    if (relativePath.contains('\u0000')) return null  // NUL byte injection

    val p = relativePath.trim()
    // Reject absolute paths
    if (p.startsWith("/")) return null
    if (p.length >= 2 && p[1] == ':') return null  // Windows drive letter (C:\…)
    if (p.startsWith("\\")) return null

    // Reject paths containing .. components (prevents ../../etc/passwd)
    val components = p.split('/', '\\')
    if (components.any { it == ".." }) return null

    val workspaceRoot = parent.workspaceDir.normalize()
    val resolved = workspaceRoot.resolve(p).normalize()

    // Boundary check: the resolved path must be within the workspace
    if (!resolved.startsWith(workspaceRoot)) return null

    // If the path exists, verify symlinks don't escape the workspace
    if (Files.exists(resolved)) {
        try {
            val realPath = resolved.toRealPath()
            val realWorkspaceRoot = workspaceRoot.toRealPath()
            if (!realPath.startsWith(realWorkspaceRoot)) return null
        } catch (_: Exception) {
            return null
        }
    }

    return resolved
}

/**
 * Result of resolving a path that may be inside **or outside** the workspace.
 *
 * - When [insideWorkspace] is `true`, the path was resolved within the
 *   workspace boundary and is safe to access without confirmation.
 * - When [insideWorkspace] is `false`, the path resolves to a location
 *   outside the workspace. The caller **must** request user confirmation
 *   before performing any I/O on it.
 */
private data class ResolvedPath(
    val path: Path,
    val insideWorkspace: Boolean
)

/**
 * Resolve an arbitrary path (relative or absolute) against the workspace.
 *
 * Unlike [resolveAndValidateWorkspacePath], this function **does not reject**
 * paths outside the workspace. Instead it returns a [ResolvedPath] indicating
 * whether the final (normalised, symlink-checked) path is inside or outside.
 *
 * Security checks still performed:
 * - Reject NUL bytes
 * - If the file exists, verify the real (symlink-resolved) path to detect
 *   symlink escapes
 *
 * @param parent     The owning [AkibaModule].
 * @param rawPath     The path string (relative to workspace, or absolute).
 * @return The [ResolvedPath], or `null` if the path is fundamentally invalid
 *         (blank, contains NUL bytes, or symlink-resolves to a path that
 *         cannot be determined).
 */
private fun resolvePathWithBoundary(parent: AkibaModule, rawPath: String): ResolvedPath? {
    if (rawPath.isBlank()) return null
    if (rawPath.contains('\u0000')) return null

    val workspaceRoot = parent.workspaceDir.normalize()
    val p = rawPath.trim()

    val resolved = if (p.startsWith("/") || (p.length >= 2 && p[1] == ':') || p.startsWith("\\")) {
        // Absolute path — resolve as-is
        java.nio.file.Paths.get(p).normalize()
    } else {
        // Relative path — resolve against workspace root
        workspaceRoot.resolve(p).normalize()
    }

    var inside = resolved.startsWith(workspaceRoot)

    // If the path exists, check the real (symlink-resolved) path
    if (Files.exists(resolved)) {
        try {
            val realPath = resolved.toRealPath()
            val realWorkspaceRoot = workspaceRoot.toRealPath()
            inside = realPath.startsWith(realWorkspaceRoot)
        } catch (_: Exception) {
            return null
        }
    }

    return ResolvedPath(resolved, inside)
}

/**
 * Request user confirmation for a file operation on a path outside the
 * workspace. Works in both in-process and cross-process (worker) modes.
 *
 * @return `true` if the user approved, `false` if denied or timed out.
 */
private fun confirmFileAccess(
    parent: AkibaModule,
    toolName: String,
    operation: String,
    targetPath: Path
): Boolean {
    // agentSessionId is defined on the agent.AgentModule subclass, not the
    // base module.AkibaModule. The tool's `parent` is typed as the base
    // class, so we cast to access the session ID. If the cast fails (the
    // module is not an agent module), auto-deny.
    val sessionId = (parent as? org.iotsplab.akiba.llm.agent.AgentModule)?.agentSessionId
        ?: return false
    val serverPort = detectWorkerServerPort()
    val pathStr = targetPath.toString()

    return if (serverPort != null) {
        // Cross-process: worker → HTTP long-poll → server → ConfirmationManager
        requestConfirmationViaHttp(
            serverPort = serverPort,
            sessionId = sessionId,
            toolName = toolName,
            command = "$operation $pathStr",
            workingDirectory = "(outside workspace)",
            timeout = 0,
            action = "file_access",
            targetPath = pathStr
        )
    } else {
        // In-process
        ConfirmationManager.requestFileAccessConfirmationBlocking(
            sessionId = sessionId,
            toolName = toolName,
            operation = operation,
            targetPath = pathStr
        )
    }
}

// ============================================================
//  read_workspace_file
// ============================================================

private fun ReadWorkspaceFileTool(parent: AkibaModule): Tool = Tool(
    name = "read_workspace_file",
    description = buildString {
        appendLine("Read the contents of a file within the agent's workspace directory.")
        appendLine()
        appendLine("The workspace is an isolated sandbox where the agent can store intermediate results,")
        appendLine("notes, scripts, and other files produced during binary analysis.")
        appendLine()
        appendLine("Paths **outside** the workspace are also accepted, but reading them requires")
        appendLine("explicit user confirmation via the frontend. The tool will block until the user")
        appendLine("approves or denies the access (or a 5-minute timeout expires).")
        appendLine()
        appendLine("Parameters:")
        appendLine("  - path:     Relative path within the workspace, or an absolute path outside it")
        appendLine("  - maxChars: Maximum characters to read (default 200000). Content beyond this is truncated.")
        appendLine()
        appendLine("Security: Relative paths resolve within the workspace. Absolute paths are allowed")
        appendLine("but require user confirmation before access. NUL bytes and symlinks pointing to")
        appendLine("indeterminate locations are rejected.")
    },
    parameters = listOf(
        ToolParameter(
            "path", "string",
            "Relative path within the workspace, or an absolute path outside the workspace.",
            required = true
        ),
        ToolParameter(
            "maxChars", "integer",
            "Maximum characters to read. Default 200000. If the file is larger, content is truncated.",
            required = false
        )
    ),
    dedupStrategy = ToolDedupStrategy.RESULT_HASH,
) { args ->
    val pathStr = args["path"] as? String
        ?: return@Tool "Error: 'path' parameter is required"

    val maxChars = (args["maxChars"] as? Number)?.toInt()?.coerceIn(1, MAX_READ_CHARS) ?: MAX_READ_CHARS

    val resolved = resolvePathWithBoundary(parent, pathStr)
        ?: return@Tool wsMapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Invalid or unsafe path: '$pathStr'. NUL bytes and symlinks to indeterminate locations are rejected.",
            "workspaceDir" to parent.workspaceDir.toString()
        ))

    // If the path is outside the workspace, require user confirmation
    if (!resolved.insideWorkspace) {
        val approved = confirmFileAccess(parent, "read_workspace_file", "read", resolved.path)
        if (!approved) {
            return@Tool wsMapper.writeValueAsString(mapOf(
                "success" to false,
                "denied" to true,
                "error" to "User denied access to path outside workspace: $pathStr",
                "path" to pathStr
            ))
        }
    }

    val resolvedPath = resolved.path

    if (!Files.exists(resolvedPath)) {
        return@Tool wsMapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "File not found: $pathStr",
            "path" to pathStr
        ))
    }

    if (Files.isDirectory(resolvedPath)) {
        return@Tool wsMapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Path is a directory, not a file: $pathStr. Use list_workspace_dir to list directory contents.",
            "path" to pathStr
        ))
    }

    try {
        val content = Files.readString(resolvedPath)
        val truncated = content.length > maxChars
        val resultContent = if (truncated) content.substring(0, maxChars) else content

        wsMapper.writeValueAsString(mapOf(
            "success" to true,
            "path" to pathStr,
            "absolutePath" to resolvedPath.toString(),
            "size" to content.length,
            "truncated" to truncated,
            "content" to resultContent
        ))
    } catch (e: Exception) {
        wsMapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Failed to read file: ${e.message}",
            "path" to pathStr
        ))
    }
}

// ============================================================
//  write_workspace_file
// ============================================================

/**
 * Count how many times [needle] occurs in [haystack].
 * Used to enforce the uniqueness check required by str_replace-style edits.
 */
private fun countOccurrences(haystack: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var idx = 0
    while (true) {
        val found = haystack.indexOf(needle, idx)
        if (found < 0) break
        count++
        idx = found + needle.length
    }
    return count
}

private fun WriteWorkspaceFileTool(parent: AkibaModule): Tool = Tool(
    name = "write_workspace_file",
    description = buildString {
        appendLine("Write or edit text content in a file within the agent's workspace directory.")
        appendLine()
        appendLine("Three mutually-exclusive modes are supported:")
        appendLine()
        appendLine("1. Full overwrite (default): provide `content` and the file is replaced entirely.")
        appendLine("2. Append: set `append=true` and `content` is added to the end of the file.")
        appendLine("3. Local edit (str_replace): provide `oldString` and `newString`. The first occurrence")
        appendLine("   of `oldString` is replaced with `newString`. This is the recommended way to modify")
        appendLine("   a few lines, mirroring Claude Code / OpenCode / Codex edit tools.")
        appendLine()
        appendLine("Paths **outside** the workspace are also accepted, but writing to them requires")
        appendLine("explicit user confirmation via the frontend. The tool will block until the user")
        appendLine("approves or denies the access (or a 5-minute timeout expires).")
        appendLine()
        appendLine("Parameters:")
        appendLine("  - path:       Relative path within the workspace, or an absolute path outside it")
        appendLine("  - content:    Full text content to write / append (required for modes 1 & 2)")
        appendLine("  - append:     If true, append to the file instead of overwriting (default false)")
        appendLine("  - oldString:  Exact text to find. Must be unique in the file unless replaceAll=true")
        appendLine("  - newString:  Replacement text (default empty string = delete oldString)")
        appendLine("  - replaceAll: If true, replace every occurrence of oldString (default false)")
        appendLine()
        appendLine("Security: Relative paths resolve within the workspace. Absolute paths are allowed")
        appendLine("but require user confirmation before access. Maximum content size: 1,000,000 characters.")
    },
    parameters = listOf(
        ToolParameter(
            "path", "string",
            "Relative path within the workspace, or an absolute path outside the workspace.",
            required = true
        ),
        ToolParameter(
            "content", "string",
            "The full text content to write or append. Required when neither oldString nor newString is provided.",
            required = false
        ),
        ToolParameter(
            "append", "boolean",
            "If true, append `content` to the file instead of overwriting. Ignored in local-edit mode. Default false.",
            required = false
        ),
        ToolParameter(
            "oldString", "string",
            "Exact text to replace. When provided, the tool performs a local edit instead of a full write.",
            required = false
        ),
        ToolParameter(
            "newString", "string",
            "Replacement text for the local edit. Defaults to empty (deletes oldString). Ignored unless oldString is set.",
            required = false
        ),
        ToolParameter(
            "replaceAll", "boolean",
            "If true, replace every occurrence of oldString. Default false (requires oldString to be unique).",
            required = false
        )
    ),
    dedupStrategy = ToolDedupStrategy.ARGS_ONLY,
) { args ->
    val pathStr = args["path"] as? String
        ?: return@Tool "Error: 'path' parameter is required"

    val oldString = args["oldString"] as? String
    val newString = (args["newString"] as? String) ?: ""
    val replaceAll = args["replaceAll"] as? Boolean ?: false

    val resolved = resolvePathWithBoundary(parent, pathStr)
        ?: return@Tool wsMapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Invalid or unsafe path: '$pathStr'. NUL bytes and symlinks to indeterminate locations are rejected.",
            "workspaceDir" to parent.workspaceDir.toString()
        ))

    // If the path is outside the workspace, require user confirmation
    if (!resolved.insideWorkspace) {
        val operation = when {
            oldString != null -> "edit"
            args["append"] as? Boolean == true -> "append"
            else -> "write"
        }
        val approved = confirmFileAccess(parent, "write_workspace_file", operation, resolved.path)
        if (!approved) {
            return@Tool wsMapper.writeValueAsString(mapOf(
                "success" to false,
                "denied" to true,
                "error" to "User denied access to path outside workspace: $pathStr",
                "path" to pathStr
            ))
        }
    }

    val resolvedPath = resolved.path

    // ---- Mode 3: local str_replace edit ----
    if (oldString != null) {
        if (oldString.isEmpty()) {
            return@Tool wsMapper.writeValueAsString(mapOf(
                "success" to false,
                "error" to "oldString must not be empty.",
                "path" to pathStr
            ))
        }
        if (!Files.exists(resolvedPath)) {
            return@Tool wsMapper.writeValueAsString(mapOf(
                "success" to false,
                "error" to "File not found: $pathStr. Cannot perform local edit on a non-existent file.",
                "path" to pathStr
            ))
        }
        if (Files.isDirectory(resolvedPath)) {
            return@Tool wsMapper.writeValueAsString(mapOf(
                "success" to false,
                "error" to "Path is a directory, not a file: $pathStr.",
                "path" to pathStr
            ))
        }

        try {
            val current = Files.readString(resolvedPath)
            val occurrences = countOccurrences(current, oldString)

            if (occurrences == 0) {
                return@Tool wsMapper.writeValueAsString(mapOf(
                    "success" to false,
                    "error" to "oldString not found in file: $pathStr. No changes made.",
                    "path" to pathStr
                ))
            }
            if (!replaceAll && occurrences > 1) {
                return@Tool wsMapper.writeValueAsString(mapOf(
                    "success" to false,
                    "error" to "oldString occurs $occurrences times in $pathStr; it must be unique. " +
                        "Provide more surrounding context to make it unique, or set replaceAll=true to replace all.",
                    "path" to pathStr,
                    "occurrences" to occurrences
                ))
            }

            val updated = if (replaceAll) current.replace(oldString, newString)
                         else current.replaceFirst(oldString, newString)

            if (updated.length > MAX_WRITE_CHARS) {
                return@Tool wsMapper.writeValueAsString(mapOf(
                    "success" to false,
                    "error" to "Resulting content exceeds maximum size of $MAX_WRITE_CHARS characters. Got ${updated.length} characters.",
                    "path" to pathStr
                ))
            }

            Files.writeString(resolvedPath, updated)
            wsMapper.writeValueAsString(mapOf(
                "success" to true,
                "path" to pathStr,
                "absolutePath" to resolvedPath.toString(),
                "bytesWritten" to updated.toByteArray(Charsets.UTF_8).size,
                "mode" to "edit",
                "replacements" to if (replaceAll) occurrences else 1,
                "message" to "Local edit applied successfully."
            ))
        } catch (e: Exception) {
            wsMapper.writeValueAsString(mapOf(
                "success" to false,
                "error" to "Failed to apply local edit: ${e.message}",
                "path" to pathStr
            ))
        }
        return@Tool "Error: unreachable"
    }

    // ---- Modes 1 & 2: full write / append ----
    val content = args["content"] as? String
        ?: return@Tool "Error: 'content' parameter is required (unless performing a local edit with oldString)."

    if (content.length > MAX_WRITE_CHARS) {
        return@Tool wsMapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Content exceeds maximum size of $MAX_WRITE_CHARS characters. Got ${content.length} characters.",
            "path" to pathStr
        ))
    }

    val append = args["append"] as? Boolean ?: false

    try {
        // Create parent directories if they don't exist
        val parentDir = resolvedPath.parent
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir)
        }

        if (append) {
            Files.writeString(resolvedPath, content, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
        } else {
            Files.writeString(resolvedPath, content)
        }

        wsMapper.writeValueAsString(mapOf(
            "success" to true,
            "path" to pathStr,
            "absolutePath" to resolvedPath.toString(),
            "bytesWritten" to content.toByteArray(Charsets.UTF_8).size,
            "appended" to append,
            "mode" to if (append) "append" else "write",
            "message" to "File ${if (append) "appended" else "written"} successfully."
        ))
    } catch (e: Exception) {
        wsMapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Failed to write file: ${e.message}",
            "path" to pathStr
        ))
    }
}

// ============================================================
//  list_workspace_dir
// ============================================================

private fun ListWorkspaceDirTool(parent: AkibaModule): Tool = Tool(
    name = "list_workspace_dir",
    description = buildString {
        appendLine("List files and directories in the agent's workspace directory.")
        appendLine()
        appendLine("Parameters:")
        appendLine("  - path:       Relative path to a subdirectory within the workspace (default: workspace root)")
        appendLine("  - recursive:  If true, list files recursively (default false)")
        appendLine()
        appendLine("Returns a JSON array of entries, each with name, type (file/directory), and size (for files).")
        appendLine()
        appendLine("Security: Only relative paths within the workspace are accepted.")
    },
    parameters = listOf(
        ToolParameter(
            "path", "string",
            "Relative path to a subdirectory within the workspace. Defaults to the workspace root.",
            required = false
        ),
        ToolParameter(
            "recursive", "boolean",
            "If true, list files recursively. Default false.",
            required = false
        )
    ),
    dedupStrategy = ToolDedupStrategy.RESULT_HASH,
) { args ->
    val pathStr = (args["path"] as? String)?.takeIf { it.isNotBlank() } ?: "."
    val recursive = args["recursive"] as? Boolean ?: false

    val resolvedPath = resolveAndValidateWorkspacePath(parent, pathStr)
        ?: return@Tool wsMapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Invalid or unsafe path: '$pathStr'. Only relative paths within the workspace are allowed.",
            "workspaceDir" to parent.workspaceDir.toString()
        ))

    if (!Files.exists(resolvedPath)) {
        return@Tool wsMapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Directory not found: $pathStr",
            "path" to pathStr
        ))
    }

    if (!Files.isDirectory(resolvedPath)) {
        return@Tool wsMapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Path is not a directory: $pathStr",
            "path" to pathStr
        ))
    }

    try {
        val entries = mutableListOf<Map<String, Any?>>()

        if (recursive) {
            Files.walkFileTree(resolvedPath, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relPath = resolvedPath.relativize(file).toString()
                    entries.add(mapOf(
                        "path" to relPath,
                        "name" to file.fileName.toString(),
                        "type" to "file",
                        "size" to attrs.size()
                    ))
                    return FileVisitResult.CONTINUE
                }

                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir != resolvedPath) {
                        val relPath = resolvedPath.relativize(dir).toString()
                        entries.add(mapOf(
                            "path" to relPath,
                            "name" to dir.fileName.toString(),
                            "type" to "directory"
                        ))
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        } else {
            Files.list(resolvedPath).use { stream ->
                stream.forEach { entry ->
                    val isDir = Files.isDirectory(entry)
                    val size = if (!isDir) {
                        try { Files.size(entry) } catch (_: Exception) { 0L }
                    } else 0L
                    entries.add(mapOf(
                        "path" to entry.fileName.toString(),
                        "name" to entry.fileName.toString(),
                        "type" to if (isDir) "directory" else "file",
                        "size" to size
                    ))
                }
            }
        }

        // Sort: directories first, then files alphabetically
        entries.sortWith(compareBy(
            { if (it["type"] == "directory") 0 else 1 },
            { (it["name"] ?: "").toString() }
        ))

        wsMapper.writeValueAsString(mapOf(
            "success" to true,
            "path" to pathStr,
            "absolutePath" to resolvedPath.toString(),
            "entryCount" to entries.size,
            "entries" to entries
        ))
    } catch (e: Exception) {
        wsMapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Failed to list directory: ${e.message}",
            "path" to pathStr
        ))
    }
}

// ============================================================
//  create_workspace_dir
// ============================================================

private fun CreateWorkspaceDirTool(parent: AkibaModule): Tool = Tool(
    name = "create_workspace_dir",
    description = buildString {
        appendLine("Create a directory within the agent's workspace.")
        appendLine()
        appendLine("Parent directories are created automatically if they don't exist. If the directory")
        appendLine("already exists, the operation succeeds silently.")
        appendLine()
        appendLine("Parameters:")
        appendLine("  - path: Relative path of the directory to create within the workspace")
        appendLine()
        appendLine("Security: Only relative paths within the workspace are accepted.")
    },
    parameters = listOf(
        ToolParameter(
            "path", "string",
            "Relative path of the directory to create within the workspace.",
            required = true
        )
    ),
    dedupStrategy = ToolDedupStrategy.ARGS_ONLY,
) { args ->
    val pathStr = args["path"] as? String
        ?: return@Tool "Error: 'path' parameter is required"

    val resolvedPath = resolveAndValidateWorkspacePath(parent, pathStr)
        ?: return@Tool wsMapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Invalid or unsafe path: '$pathStr'. Only relative paths within the workspace are allowed.",
            "workspaceDir" to parent.workspaceDir.toString()
        ))

    try {
        Files.createDirectories(resolvedPath)
        wsMapper.writeValueAsString(mapOf(
            "success" to true,
            "path" to pathStr,
            "absolutePath" to resolvedPath.toString(),
            "message" to "Directory created successfully (or already existed)."
        ))
    } catch (e: Exception) {
        wsMapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Failed to create directory: ${e.message}",
            "path" to pathStr
        ))
    }
}

// ============================================================
//  move_workspace_file
// ============================================================

private fun MoveWorkspaceFileTool(parent: AkibaModule): Tool = Tool(
    name = "move_workspace_file",
    description = buildString {
        appendLine("Move or rename a file or directory within the agent's workspace.")
        appendLine()
        appendLine("Both the source and destination paths must be within the workspace directory.")
        appendLine("If the destination already exists, it will be overwritten (for files) or merged")
        appendLine("into (for directories where possible). Parent directories of the destination are")
        appendLine("created automatically.")
        appendLine()
        appendLine("Parameters:")
        appendLine("  - sourcePath:      Relative path of the file/directory to move")
        appendLine("  - destinationPath: Relative destination path within the workspace")
        appendLine()
        appendLine("Security: Only relative paths within the workspace are accepted.")
    },
    parameters = listOf(
        ToolParameter(
            "sourcePath", "string",
            "Relative path of the file or directory to move within the workspace.",
            required = true
        ),
        ToolParameter(
            "destinationPath", "string",
            "Relative destination path within the workspace.",
            required = true
        )
    ),
    dedupStrategy = ToolDedupStrategy.ARGS_ONLY,
) { args ->
    val sourceStr = args["sourcePath"] as? String
        ?: return@Tool "Error: 'sourcePath' parameter is required"
    val destStr = args["destinationPath"] as? String
        ?: return@Tool "Error: 'destinationPath' parameter is required"

    val resolvedSource = resolveAndValidateWorkspacePath(parent, sourceStr)
        ?: return@Tool wsMapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Invalid or unsafe source path: '$sourceStr'. Only relative paths within the workspace are allowed.",
            "workspaceDir" to parent.workspaceDir.toString()
        ))

    val resolvedDest = resolveAndValidateWorkspacePath(parent, destStr)
        ?: return@Tool wsMapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Invalid or unsafe destination path: '$destStr'. Only relative paths within the workspace are allowed.",
            "workspaceDir" to parent.workspaceDir.toString()
        ))

    if (!Files.exists(resolvedSource)) {
        return@Tool wsMapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Source not found: $sourceStr",
            "sourcePath" to sourceStr
        ))
    }

    try {
        // Create parent directories of the destination
        val destParent = resolvedDest.parent
        if (destParent != null && !Files.exists(destParent)) {
            Files.createDirectories(destParent)
        }

        Files.move(resolvedSource, resolvedDest, StandardCopyOption.REPLACE_EXISTING)

        wsMapper.writeValueAsString(mapOf(
            "success" to true,
            "sourcePath" to sourceStr,
            "destinationPath" to destStr,
            "absoluteDestination" to resolvedDest.toString(),
            "message" to "Moved successfully."
        ))
    } catch (e: Exception) {
        wsMapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Failed to move: ${e.message}",
            "sourcePath" to sourceStr,
            "destinationPath" to destStr
        ))
    }
}

// ============================================================
//  delete_workspace_file
// ============================================================

private fun DeleteWorkspaceFileTool(parent: AkibaModule): Tool = Tool(
    name = "delete_workspace_file",
    description = buildString {
        appendLine("Delete a file or directory within the agent's workspace.")
        appendLine()
        appendLine("Parameters:")
        appendLine("  - path:      Relative path of the file or directory to delete")
        appendLine("  - recursive: If true and the path is a directory, delete it and all its contents")
        appendLine("               recursively (default false). When false, directories must be empty.")
        appendLine()
        appendLine("WARNING: Deletion is permanent and cannot be undone.")
        appendLine()
        appendLine("Security: Only relative paths within the workspace are accepted. The workspace root")
        appendLine("directory itself cannot be deleted.")
    },
    parameters = listOf(
        ToolParameter(
            "path", "string",
            "Relative path of the file or directory to delete within the workspace.",
            required = true
        ),
        ToolParameter(
            "recursive", "boolean",
            "If true and path is a directory, delete all contents recursively. Default false.",
            required = false
        )
    ),
    dedupStrategy = ToolDedupStrategy.ARGS_ONLY,
) { args ->
    val pathStr = args["path"] as? String
        ?: return@Tool "Error: 'path' parameter is required"

    val recursive = args["recursive"] as? Boolean ?: false

    val resolvedPath = resolveAndValidateWorkspacePath(parent, pathStr)
        ?: return@Tool wsMapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Invalid or unsafe path: '$pathStr'. Only relative paths within the workspace are allowed.",
            "workspaceDir" to parent.workspaceDir.toString()
        ))

    // Prevent deletion of the workspace root itself
    if (resolvedPath == parent.workspaceDir.normalize()) {
        return@Tool wsMapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Cannot delete the workspace root directory."
        ))
    }

    if (!Files.exists(resolvedPath)) {
        return@Tool wsMapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Path not found: $pathStr",
            "path" to pathStr
        ))
    }

    try {
        val isDir = Files.isDirectory(resolvedPath)

        if (isDir && recursive) {
            // Recursive directory deletion
            Files.walkFileTree(resolvedPath, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        } else {
            Files.delete(resolvedPath)
        }

        wsMapper.writeValueAsString(mapOf(
            "success" to true,
            "path" to pathStr,
            "absolutePath" to resolvedPath.toString(),
            "type" to if (isDir) "directory" else "file",
            "recursive" to (isDir && recursive),
            "message" to "Deleted successfully."
        ))
    } catch (_: java.nio.file.DirectoryNotEmptyException) {
        wsMapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Directory is not empty. Set recursive=true to delete recursively.",
            "path" to pathStr
        ))
    } catch (e: Exception) {
        wsMapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Failed to delete: ${e.message}",
            "path" to pathStr
        ))
    }
}

// ============================================================
//  grep_workspace
// ============================================================

/** Maximum total match output characters returned by [GrepWorkspaceTool]. */
private const val MAX_GREP_OUTPUT_CHARS = 50_000

/** Maximum number of matches returned by [GrepWorkspaceTool]. */
private const val MAX_GREP_MATCHES = 200

/** Maximum file size for grep to read (skip binary / huge files). */
private const val MAX_GREP_FILE_SIZE: Long = 2_000_000

private fun GrepWorkspaceTool(parent: AkibaModule): Tool = Tool(
    name = "grep_workspace",
    description = buildString {
        appendLine("Search for a regex pattern across files in the agent's workspace directory.")
        appendLine()
        appendLine("Searches file contents line-by-line (like `grep -rn`). Only text files are")
        appendLine("searched; binary files and files larger than 2 MB are skipped. Results include")
        appendLine("the relative file path, line number, and the matching line content.")
        appendLine()
        appendLine("Paths **outside** the workspace are also accepted, but searching them requires")
        appendLine("explicit user confirmation via the frontend. The tool will block until the user")
        appendLine("approves or denies the access (or a 5-minute timeout expires).")
        appendLine()
        appendLine("Parameters:")
        appendLine("  - pattern:    Regular expression to search for (Java/Kotlin regex syntax)")
        appendLine("  - path:       Relative subdirectory or absolute path to search (default: workspace root)")
        appendLine("  - caseInsensitive: If true, perform case-insensitive matching (default false)")
        appendLine()
        appendLine("Security: Relative paths resolve within the workspace. Absolute paths are allowed")
        appendLine("but require user confirmation before access. NUL bytes and symlinks pointing to")
        appendLine("indeterminate locations are rejected.")
        appendLine("Maximum $MAX_GREP_MATCHES matches / $MAX_GREP_OUTPUT_CHARS characters returned.")
    },
    parameters = listOf(
        ToolParameter(
            "pattern", "string",
            "Regular expression to search for in file contents.",
            required = true
        ),
        ToolParameter(
            "path", "string",
            "Relative subdirectory within the workspace, or an absolute path outside it. Defaults to the workspace root.",
            required = false
        ),
        ToolParameter(
            "caseInsensitive", "boolean",
            "If true, perform case-insensitive matching. Default false.",
            required = false
        )
    ),
    dedupStrategy = ToolDedupStrategy.RESULT_HASH,
) { args ->
    val patternStr = args["pattern"] as? String
        ?: return@Tool "Error: 'pattern' parameter is required"

    if (patternStr.isEmpty()) {
        return@Tool wsMapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Pattern must not be empty."
        ))
    }

    val regex = try {
        val flags = if (args["caseInsensitive"] as? Boolean == true)
            setOf(RegexOption.IGNORE_CASE) else emptySet()
        Regex(patternStr, flags)
    } catch (e: Exception) {
        return@Tool wsMapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Invalid regex pattern: ${e.message}",
            "pattern" to patternStr
        ))
    }

    val pathStr = (args["path"] as? String)?.takeIf { it.isNotBlank() } ?: "."
    val resolved = resolvePathWithBoundary(parent, pathStr)
        ?: return@Tool wsMapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Invalid or unsafe path: '$pathStr'. NUL bytes and symlinks to indeterminate locations are rejected.",
            "workspaceDir" to parent.workspaceDir.toString()
        ))

    // If the path is outside the workspace, require user confirmation
    if (!resolved.insideWorkspace) {
        val approved = confirmFileAccess(parent, "grep_workspace", "grep", resolved.path)
        if (!approved) {
            return@Tool wsMapper.writeValueAsString(mapOf(
                "success" to false,
                "denied" to true,
                "error" to "User denied access to path outside workspace: $pathStr",
                "path" to pathStr
            ))
        }
    }

    val resolvedPath = resolved.path

    if (!Files.exists(resolvedPath)) {
        return@Tool wsMapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Path not found: $pathStr",
            "path" to pathStr
        ))
    }

    if (!Files.isDirectory(resolvedPath)) {
        return@Tool wsMapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Path is not a directory: $pathStr. Use a directory path for grep.",
            "path" to pathStr
        ))
    }

    val workspaceRoot = parent.workspaceDir.normalize()
    val matches = mutableListOf<Map<String, Any?>>()
    var filesSearched = 0
    var filesSkipped = 0
    var truncated = false
    var outputChars = 0

    try {
        Files.walkFileTree(resolvedPath, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                // Skip files larger than the size limit
                if (attrs.size() > MAX_GREP_FILE_SIZE) {
                    filesSkipped++
                    return FileVisitResult.CONTINUE
                }

                // Skip binary files by detecting NUL bytes in the first 8 KB
                try {
                    val probe = Files.newInputStream(file).use { it.readNBytes(8192) }
                    if (probe.any { it == 0.toByte() }) {
                        filesSkipped++
                        return FileVisitResult.CONTINUE
                    }
                } catch (_: Exception) {
                    filesSkipped++
                    return FileVisitResult.CONTINUE
                }

                filesSearched++

                try {
                    val content = Files.readString(file, Charset.defaultCharset())
                    val relPath = workspaceRoot.relativize(file.normalize()).pathString

                    content.lineSequence().forEachIndexed { idx, line ->
                        if (truncated) return@forEachIndexed
                        if (regex.containsMatchIn(line)) {
                            val matchEntry = mapOf<String, Any?>(
                                "file" to relPath,
                                "line" to (idx + 1),
                                "content" to line.take(500)
                            )
                            outputChars += matchEntry.toString().length
                            matches.add(matchEntry)
                            if (matches.size >= MAX_GREP_MATCHES || outputChars >= MAX_GREP_OUTPUT_CHARS) {
                                truncated = true
                            }
                        }
                    }
                } catch (_: Exception) {
                    // File could not be read as text — skip
                    filesSkipped++
                }

                return if (truncated) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }
        })
    } catch (e: Exception) {
        return@Tool wsMapper.writeValueAsString(mapOf(
            "success" to false,
            "error" to "Failed to search: ${e.message}",
            "path" to pathStr
        ))
    }

    wsMapper.writeValueAsString(mapOf(
        "success" to true,
        "pattern" to patternStr,
        "path" to pathStr,
        "caseInsensitive" to (args["caseInsensitive"] as? Boolean == true),
        "matchCount" to matches.size,
        "filesSearched" to filesSearched,
        "filesSkipped" to filesSkipped,
        "truncated" to truncated,
        "matches" to matches
    ))
}
