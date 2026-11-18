package org.iotsplab.akiba.managers

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists

/**
 * Keeps a program's files in step with the program itself.
 *
 * Ghidra owns the program; the workspace only follows. So every operation here runs *after* the
 * Ghidra side has already succeeded, and a failure here is reported rather than thrown: losing a
 * program because its directory could not be moved would be the worse outcome.
 */
object ProgramWorkspace {

    /** Where the files of one program (or one folder of programs) live. */
    fun dir(workspaceRoot: String, project: String, entryPath: String): Path =
        WorkspacePaths.programRoot(workspaceRoot, project, entryPath)

    /** Remove a program's directory, or a folder's whole subtree. */
    fun delete(workspaceRoot: String, project: String, entryPath: String): Boolean {
        val dir = dir(workspaceRoot, project, entryPath)
        if (!dir.exists()) return true
        return try {
            deleteRecursively(dir)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Move a program's directory to where the program now lives. */
    fun move(from: Path, to: Path): Boolean = try {
        if (!from.exists()) {
            true
        } else if (from.toRealPath() == to.toRealPathOrNull()) {
            true
        } else if (to.exists()) {
            // Two programs must never share one directory; refuse instead of merging.
            false
        } else {
            Files.createDirectories(to.parent)
            Files.move(from, to)
            removeEmptyParents(from.parent)
            true
        }
    } catch (_: Exception) {
        false
    }

    /** Copy a program's directory, used by "copy with workspace". */
    fun copy(from: Path, to: Path): Boolean = try {
        if (!from.exists()) {
            true
        } else if (to.exists()) {
            false
        } else {
            copyRecursively(from, to)
            true
        }
    } catch (_: Exception) {
        false
    }

    private fun Path.toRealPathOrNull(): Path? = try {
        if (exists()) toRealPath() else null
    } catch (_: Exception) {
        null
    }

    private fun deleteRecursively(dir: Path) {
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun copyRecursively(from: Path, to: Path) {
        Files.walk(from).use { stream ->
            stream.forEach { src ->
                val dest = to.resolve(from.relativize(src).toString())
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest)
                } else {
                    Files.createDirectories(dest.parent)
                    Files.copy(
                        src, dest,
                        StandardCopyOption.COPY_ATTRIBUTES,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            }
        }
    }

    /** A moved program must not leave empty `programs/foo/bar/` chains behind. */
    private fun removeEmptyParents(start: Path?) {
        var dir = start
        while (dir != null && dir.exists() && Files.isDirectory(dir) && dir.fileName?.toString() != "programs") {
            val empty = Files.list(dir).use { !it.findAny().isPresent }
            if (!empty) break
            Files.deleteIfExists(dir)
            dir = dir.parent
        }
    }
}
