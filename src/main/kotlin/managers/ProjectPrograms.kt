package org.iotsplab.akiba.managers

import ghidra.base.project.GhidraProject
import ghidra.framework.model.DomainFile
import ghidra.framework.model.DomainFolder
import ghidra.util.task.TaskMonitor
import java.nio.file.Files
import java.nio.file.Path
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

private val logger: Logger = LogManager.getLogger("ProjectPrograms")

/**
 * Read and edit the contents of a Ghidra project.
 *
 * Every call opens the project, does its work and closes it again: Ghidra
 * grants one process at a time, so a project held by an agent session answers
 * "busy" instead. The Projects browser therefore reads [ProjectIndex], and
 * this object refreshes that index whenever it had a project open anyway.
 */
object ProjectPrograms {

    data class CopyOutcome(val source: String, val program: String, val target: String, val targetFile: String)

    /**
     * @param asName optional target name; Ghidra appends a suffix when the name is
     *        taken instead of overwriting, and the outcome reports what was written
     * @throws ProjectBusyException when either project is open elsewhere
     * @throws IllegalArgumentException when a project or program does not exist
     */
    fun copy(
        sourceProject: String,
        programName: String,
        targetProject: String,
        projectDirectory: Path,
        asName: String? = null,
    ): CopyOutcome {
        require(sourceProject != targetProject) {
            "Source and target project are the same ('$sourceProject')"
        }
        requireExists(projectDirectory, sourceProject)
        requireExists(projectDirectory, targetProject)

        var source: GhidraProject? = null
        var target: GhidraProject? = null
        val outcome = try {
            source = open(sourceProject, projectDirectory)
            target = open(targetProject, projectDirectory)

            val program = findFile(source.projectData.rootFolder, programName)
                ?: throw IllegalArgumentException(
                    "Program '$programName' is not in project '$sourceProject'"
                )
            val copied: DomainFile = program.copyTo(target.projectData.rootFolder, TaskMonitor.DUMMY)
            // Rename the copy, never the source: the source project belongs to
            // another conversation and must not change as a side effect.
            if (asName != null && asName.isNotBlank() && asName != copied.name) {
                copied.setName(asName)
            }
            logger.info(
                "Copied program '{}' from project '{}' to '{}' as '{}'",
                programName, sourceProject, targetProject, copied.name
            )
            CopyOutcome(
                source = sourceProject,
                program = programName,
                target = targetProject,
                targetFile = copied.name,
            )
        } finally {
            runCatching { target?.close() }
            runCatching { source?.close() }
        }

        // Both handles are released, so the index can reopen the target itself.
        ProjectIndex.refresh(targetProject, projectDirectory)
        return outcome
    }

    /** Programs of a project, for the UI to offer as copy sources. */
    fun programs(projectName: String, projectDirectory: Path): List<String> {
        requireExists(projectDirectory, projectName)
        var project: GhidraProject? = null
        return try {
            project = open(projectName, projectDirectory)
            collect(project.projectData.rootFolder, "").sorted()
        } finally {
            runCatching { project?.close() }
        }
    }


    /** One entry of a project's tree. `path` is relative, `/`-separated. */
    data class Entry(
        val path: String,
        val name: String,
        val folder: Boolean,
        val size: Long,
        val modified: Long,
    )

    /** Full tree of a project, folders first at every level. */
    fun tree(projectName: String, projectDirectory: Path): List<Entry> {
        requireExists(projectDirectory, projectName)
        var project: GhidraProject? = null
        return try {
            project = open(projectName, projectDirectory)
            walk(project.projectData.rootFolder, "").sortedWith(
                compareBy({ !it.folder }, { it.path })
            )
        } finally {
            runCatching { project?.close() }
        }
    }

    fun createFolder(projectName: String, projectDirectory: Path, parentPath: String, name: String) {
        require(name.isNotBlank()) { "Folder name is required" }
        mutate(projectName, projectDirectory) { root ->
            val parent = resolveFolder(root, parentPath)
            require(parent.getFolder(name) == null && parent.getFile(name) == null) {
                "'$name' already exists in ${parentPath.ifBlank { "/" }}"
            }
            parent.createFolder(name)
            null
        }
    }

    fun renameEntry(projectName: String, projectDirectory: Path, path: String, newName: String) {
        require(newName.isNotBlank()) { "New name is required" }
        mutate(projectName, projectDirectory) { root ->
            val (parent, name) = resolveParent(root, path)
            require(parent.getFolder(newName) == null && parent.getFile(newName) == null) {
                "'$newName' already exists in this folder"
            }
            val folder = parent.getFolder(name)
            if (folder != null) folder.setName(newName) else
                (parent.getFile(name) ?: throw IllegalArgumentException("'$path' does not exist"))
                    .setName(newName)
            null
        }
    }

    fun deleteEntry(projectName: String, projectDirectory: Path, path: String) {
        mutate(projectName, projectDirectory) { root ->
            val (parent, name) = resolveParent(root, path)
            val folder = parent.getFolder(name)
            if (folder != null) folder.delete() else
                (parent.getFile(name) ?: throw IllegalArgumentException("'$path' does not exist"))
                    .delete()
            null
        }
    }

    private fun <T> mutate(projectName: String, projectDirectory: Path, block: (DomainFolder) -> T): T {
        requireExists(projectDirectory, projectName)
        var project: GhidraProject? = null
        val result = try {
            project = open(projectName, projectDirectory)
            block(project.projectData.rootFolder)
        } finally {
            runCatching { project?.close() }
        }
        // The project is closed again, so `tree` can reopen it without hitting
        // this process's own channel lock.
        ProjectIndex.refresh(projectName, projectDirectory)
        return result
    }

    private fun resolveFolder(root: DomainFolder, path: String): DomainFolder {
        if (path.isBlank()) return root
        return path.trim('/').split('/').fold(root) { acc, segment ->
            acc.getFolder(segment) ?: throw IllegalArgumentException("Folder '$path' does not exist")
        }
    }

    private fun resolveParent(root: DomainFolder, path: String): Pair<DomainFolder, String> {
        val segments = path.trim('/').split('/').filter { it.isNotBlank() }
        require(segments.isNotEmpty()) { "Path is required" }
        val parentPath = segments.dropLast(1).joinToString("/")
        return resolveFolder(root, parentPath) to segments.last()
    }

    private fun walk(folder: DomainFolder, prefix: String): List<Entry> {
        val entries = mutableListOf<Entry>()
        folder.folders.forEach { sub ->
            val path = prefix + sub.name
            entries += Entry(path, sub.name, true, 0, 0)
            entries += walk(sub, "$path/")
        }
        folder.files.forEach { file ->
            entries += Entry(prefix + file.name, file.name, false, file.length(), 0L)
        }
        return entries
    }

    private fun open(projectName: String, projectDirectory: Path): GhidraProject {
        // The first project access in a fresh JVM has to initialise Ghidra,
        // otherwise ToolUtils fails its static initialiser for the whole process.
        check(WorkspaceManager.initializeGhidra()) { "Ghidra application is not available" }
        return try {
            GhidraProject.openProject(projectDirectory.toAbsolutePath().toString(), projectName)
        } catch (e: Exception) {
            // LockException / FileInUseException both mean "somebody else has it".
            throw ProjectBusyException(projectName, ProjectLock.owner(projectDirectory, projectName), e)
        }
    }

    private fun requireExists(projectDirectory: Path, projectName: String) {
        val grp = projectDirectory.resolve("$projectName.gpr")
        val rep = projectDirectory.resolve("$projectName.rep")
        require(Files.isRegularFile(grp) && Files.isDirectory(rep)) {
            "Ghidra project '$projectName' does not exist"
        }
    }

    private fun findFile(folder: DomainFolder, name: String): DomainFile? {
        folder.files.firstOrNull { it.name == name }?.let { return it }
        for (sub in folder.folders) {
            findFile(sub, name)?.let { return it }
        }
        return null
    }

    private fun collect(folder: DomainFolder, prefix: String): List<String> =
        folder.files.map { prefix + it.name } + folder.folders.flatMap { collect(it, prefix + it.name + "/") }
}
