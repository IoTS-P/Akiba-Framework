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
 * Copy a program from one Ghidra project into another.
 *
 * Ghidra projects are self-contained: a program belongs to exactly one `.rep`,
 * so sharing analysis between conversations means copying the domain file. Both
 * projects are opened by this process for the duration of the copy, which is the
 * only way `DomainFile.copyTo` can see a destination folder.
 *
 * The source project is taken with its own channel lock, so a program cannot be
 * copied out of a project another process is using — the caller gets the same
 * "busy" answer as opening it would give.
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
        try {
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
            return CopyOutcome(
                source = sourceProject,
                program = programName,
                target = targetProject,
                targetFile = copied.name,
            )
        } finally {
            runCatching { target?.close() }
            runCatching { source?.close() }
        }
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

    private fun open(projectName: String, projectDirectory: Path): GhidraProject =
        try {
            GhidraProject.openProject(projectDirectory.toAbsolutePath().toString(), projectName)
        } catch (e: Exception) {
            // LockException / FileInUseException both mean "somebody else has it".
            throw ProjectBusyException(projectName, ProjectLock.owner(projectDirectory, projectName), e)
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
