package org.iotsplab.akiba.managers

import java.nio.file.Path

/**
 * Where a program or a conversation keeps its files.
 *
 * ```
 * <workspaceRoot>/<project>/
 *   programs/<path inside Ghidra>/     one directory per program
 *   sessions/<sessionId>/              one directory per conversation
 * ```
 *
 * Programs and sessions sit side by side on purpose: a conversation can switch the program it
 * works on, and deleting a program must not take a conversation's files with it.
 *
 * Every segment is sanitised before it reaches the filesystem, because program names come from
 * Ghidra (spaces, colons, slashes) and session ids come from a URL.
 */
object WorkspacePaths {

    /**
     * Names a workspace user may not create.
     *
     * `binary` is provisioned by Akiba at the root of every workspace: it is the read-only copy
     * of the program being analysed. Letting someone replace it, or create a folder with that
     * name, would hand the agent a different file than the one it was told to analyse.
     */
    private val RESERVED_ROOT_NAMES = setOf("binary")

    /** True when [name] is reserved and must not appear in, or be created in, a workspace root. */
    fun isReservedName(name: String): Boolean = name.lowercase() in RESERVED_ROOT_NAMES


    /** Longest a single path segment may be on common filesystems. */
    private const val MAX_SEGMENT = 120

    private val ILLEGAL = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

    /**
     * Make one directory name out of arbitrary text.
     *
     * `..` and `.` are not passed through: they mean something to the filesystem and nothing to
     * us, so they become literal names. Empty input becomes `_` rather than an empty segment.
     */
    fun sanitizeSegment(raw: String): String {
        val cleaned = ILLEGAL.replace(raw.trim(), "_")
            .replace(Regex("""\s+"""), "_")
            .trim('.', '_', ' ')
        if (cleaned.isEmpty()) return "_"
        if (cleaned == "." || cleaned == "..") return "_"
        return if (cleaned.length <= MAX_SEGMENT) cleaned else cleaned.take(MAX_SEGMENT)
    }

    /**
     * Split a Ghidra domain path into safe segments.
     *
     * Ghidra reports `/folder/prog.elf`; the leading slash is a project root, not a filesystem
     * root, so it is dropped. A program that lives in the project root still needs a directory of
     * its own, so an empty path becomes a single segment.
     */
    fun programSegments(programPath: String): List<String> {
        val parts = programPath.split('/', '\\')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map(::sanitizeSegment)
        return parts.ifEmpty { listOf("_") }
    }

    /** `<workspaceRoot>/<project>` */
    fun projectRoot(workspaceRoot: String, project: String): Path =
        Path.of(workspaceRoot).resolve(sanitizeSegment(project))

    /** `<workspaceRoot>/<project>/programs/<ghidra path>` */
    fun programRoot(workspaceRoot: String, project: String, programPath: String): Path {
        var dir = projectRoot(workspaceRoot, project).resolve("programs")
        for (segment in programSegments(programPath)) dir = dir.resolve(segment)
        return dir
    }

    /** `<workspaceRoot>/<project>/sessions/<sessionId>` */
    fun sessionRoot(workspaceRoot: String, project: String, sessionId: String): Path =
        projectRoot(workspaceRoot, project).resolve("sessions").resolve(sanitizeSegment(sessionId))

    /**
     * Where a module writes when nobody said otherwise.
     *
     * A module run from a script writes into one directory per module and is expected to be run
     * again over the same files; a module run for a conversation gets a timestamped directory so
     * two calls never overwrite each other's output.
     */
    fun moduleOutput(scopeRoot: Path, moduleClass: String, timestamped: Boolean, stamp: String): Path {
        val base = scopeRoot.resolve(sanitizeSegment(moduleClass))
        return if (timestamped) base.resolve(sanitizeSegment(stamp)) else base
    }

    /**
     * Turn a request path into a file inside [root], or null if it would leave.
     *
     * `..`, absolute paths and NUL bytes are refused before anything touches the disk. Existing
     * paths are re-checked after resolving symlinks, because a link inside the tree may point out
     * of it. The single allowed exception is a file named `binary`, which by design points at the
     * program's copy rather than the conversation's own files.
     */
    fun resolveInside(root: Path, requested: String): Path? {
        val clean = requested.trim().removePrefix("/")
        if (clean.contains('\u0000')) return null
        if (clean.split('/', '\\').any { it == ".." }) return null

        val base = root.normalize()
        val candidate = base.resolve(clean).normalize()
        if (!candidate.startsWith(base)) return null

        // A path that does not exist yet is judged by its nearest existing ancestor: a missing
        // file under `binary/` would otherwise be created on the far side of the link.
        var probe: Path? = candidate
        while (probe != null && !java.nio.file.Files.exists(probe)) probe = probe.parent
        val existing = probe ?: return null

        val real = runCatching { existing.toRealPath() }.getOrNull() ?: return null
        val realRoot = runCatching { base.toRealPath() }.getOrNull() ?: return null
        if (real.startsWith(realRoot)) return candidate

        // The one permitted exception: the link to the program's binary, asked for by name.
        val allowed = existing == candidate && candidate.fileName?.toString() == "binary"
        return if (allowed) candidate else null
    }
}
