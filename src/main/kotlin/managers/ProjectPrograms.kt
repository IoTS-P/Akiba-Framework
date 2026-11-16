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

    data class CopyOutcome(
        val source: String,
        val program: String,
        val target: String,
        val targetFile: String,
        /** New identity of the copy, and the binary it inherited from its source. */
        val programId: String? = null,
        val binaryId: Long? = null,
    )

    /** @property programId identity written into the program's metadata. */
    data class ImportOutcome(val program: String, val folder: String, val programId: String? = null)

    /**
     * Import a binary file into a folder of a project.
     *
     * Missing parent folders are created on the way. The program is saved
     * under [asName], or under the source file name when no name is given.
     *
     * @param source absolute path of the binary to import
     * @param folder `/`-separated target folder, relative to the project root
     * @throws ProjectBusyException when the project is open elsewhere
     * @throws IllegalArgumentException when the file is missing or the name is taken
     */
    fun importProgram(
        projectName: String,
        projectDirectory: Path,
        source: Path,
        folder: String = "",
        asName: String? = null,
        binaryId: Long? = null,
    ): ImportOutcome {
        require(Files.isRegularFile(source)) { "Binary file '${source.fileName}' is missing" }
        requireExists(projectDirectory, projectName)

        var project: GhidraProject? = null
        var programId: String? = null
        val outcome = try {
            project = open(projectName, projectDirectory)
            val target = ensureFolder(project.projectData.rootFolder, folder)
            val name = asName?.takeIf { it.isNotBlank() } ?: source.fileName.toString()
            require(target.getFile(name) == null && target.getFolder(name) == null) {
                "'$name' already exists in ${target.pathname}"
            }

            val program = ProgramManager.loadProgram(source, project)
                ?: throw IllegalArgumentException("Ghidra could not import '${source.fileName}'")
            try {
                // The loader leaves the program unsaved; naming it first keeps
                // the program and its domain file in step.
                val txId = program.startTransaction("import")
                try {
                    program.name = name
                    // Identity is issued once, at import, and then travels with
                    // the artifact through every rename and move.
                    programId = ProgramIdentity.ensure(program)
                    // The binary this came from is inherited by copies: they are other
                    // views of the same file, not different files.
                    if (binaryId != null && binaryId > 0) ProgramIdentity.setBinaryId(program, binaryId)
                } finally {
                    program.endTransaction(txId, true)
                }
                project.saveAs(program, target.pathname, name, false)
            } finally {
                runCatching { project.close(program) }
            }
            logger.info(
                "Imported binary '{}' into project '{}' as '{}'",
                source.fileName, projectName, name
            )
            ImportOutcome(program = name, folder = target.pathname, programId = programId)
        } finally {
            runCatching { project?.close() }
        }

        // The handle is released, so the index can reopen the project itself.
        ProjectIndex.refresh(projectName, projectDirectory)
        if (binaryId != null && binaryId > 0) {
            val path = "${outcome.folder.trim('/')}/${outcome.program}".trimStart('/')
            ProjectIndex.setBinaryId(projectName, projectDirectory, path, binaryId)
        }
        return outcome
    }

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
            // `copied.name` keeps the old value after setName(), so the rest of this
            // function has to use the name the copy actually has.
            val finalName = if (asName != null && asName.isNotBlank() && asName != copied.name) {
                copied.setName(asName)
                asName
            } else copied.name
            val identity = reissueIdentity(target, target.projectData.rootFolder, finalName)
            logger.info(
                "Copied program '{}' from project '{}' to '{}' as '{}'",
                programName, sourceProject, targetProject, finalName
            )
            CopyOutcome(
                source = sourceProject,
                program = programName,
                target = targetProject,
                targetFile = finalName,
                programId = identity.programId,
                binaryId = identity.binaryId,
            )
        } finally {
            runCatching { target?.close() }
            runCatching { source?.close() }
        }
        // The copy may land in a project the index has never seen, so the row has to exist
        // before the ids can be stamped onto it.
        ProjectIndex.refresh(targetProject, projectDirectory, force = true)
        outcome.programId?.let {
            ProjectIndex.setProgramId(targetProject, projectDirectory, outcome.targetFile, it)
        }
        outcome.binaryId?.let {
            ProjectIndex.setBinaryId(targetProject, projectDirectory, outcome.targetFile, it)
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
        /** Filled by the index, not by the walk: reading it needs the program open. */
        val programId: String? = null,
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

    /**
     * Move [path] into [targetFolderPath], keeping its name unless [newName] is
     * given. Returns the entry's new path.
     */
    fun moveEntry(
        projectName: String,
        projectDirectory: Path,
        path: String,
        targetFolderPath: String,
        newName: String? = null,
    ): String = mutate(projectName, projectDirectory) { root ->
        val (parent, name) = resolveParent(root, path)
        val target = resolveFolder(root, targetFolderPath)
        require(path != targetFolderPath) { "Cannot move '$path' into itself" }
        require(!targetFolderPath.startsWith("$path/")) { "Cannot move '$path' into its own subtree" }
        val finalName = newName?.takeIf { it.isNotBlank() } ?: name
        if (parent.pathname != target.pathname || finalName != name) {
            require(target.getFolder(finalName) == null && target.getFile(finalName) == null) {
                "'$finalName' already exists in ${targetFolderPath.ifBlank { "/" }}"
            }
        }
        val folder = parent.getFolder(name)
        if (folder != null) {
            folder.moveTo(target)
        } else {
            val file = parent.getFile(name) ?: throw IllegalArgumentException("'$path' does not exist")
            file.moveTo(target)
        }
        if (finalName != name) {
            val movedFolder = target.getFolder(name)
            if (movedFolder != null) {
                movedFolder.setName(finalName)
            } else {
                (target.getFile(name)
                    ?: throw IllegalArgumentException("'$path' vanished during the move"))
                    .setName(finalName)
            }
        }
        target.pathname.trimEnd('/') + "/" + finalName
    }

    /**
     * Copy [path] into [targetFolderPath]. A name already used there gets a
     * `_1`, `_2`, … suffix, the way a file manager would.
     */
    fun copyEntry(
        projectName: String,
        projectDirectory: Path,
        path: String,
        targetFolderPath: String,
    ): String = mutateProject(projectName, projectDirectory) { project, root ->
        val (parent, name) = resolveParent(root, path)
        val target = resolveFolder(root, targetFolderPath)
        require(!targetFolderPath.startsWith("$path/")) { "Cannot copy '$path' into its own subtree" }
        val folder = parent.getFolder(name)
        if (folder != null) {
            val copyName = uniqueName(target, name)
            val created = target.createFolder(copyName)
            copyFolderInto(folder, created, project, target)
            created.pathname
        } else {
            val file = parent.getFile(name) ?: throw IllegalArgumentException("'$path' does not exist")
            val copyName = uniqueName(target, name)
            val copy = file.copyTo(target, TaskMonitor.DUMMY)
            if (copy.name != copyName) copy.setName(copyName)
            val identity = reissueIdentity(project, target, copyName)
            val copyPath = "$targetFolderPath/$copyName".trimStart('/')
            identity.programId?.let {
                ProjectIndex.setProgramId(projectName, projectDirectory, copyPath, it)
            }
            identity.binaryId?.let {
                ProjectIndex.setBinaryId(projectName, projectDirectory, copyPath, it)
            }
            "$targetFolderPath/$copyName".trimStart('/')
        }
    }

    /**
     * Copy or move an entry between two projects.
     *
     * Ghidra can only move inside one project, so a cross-project move is a
     * copy followed by a delete in the source. A name already used in the
     * target gets the same `_1`, `_2` suffix a local copy would.
     *
     * @return the entry's path in the target project.
     */
    fun transferAcross(
        sourceProject: String,
        sourceDirectory: Path,
        path: String,
        targetProject: String,
        targetDirectory: Path,
        targetFolder: String,
        move: Boolean,
    ): String {
        require(sourceProject != targetProject) {
            "Source and target project are the same ('$sourceProject')"
        }
        requireExists(sourceDirectory, sourceProject)
        requireExists(targetDirectory, targetProject)

        var source: GhidraProject? = null
        var target: GhidraProject? = null
        try {
            source = open(sourceProject, sourceDirectory)
            target = open(targetProject, targetDirectory)
            val sourceRoot = source.projectData.rootFolder
            val targetRoot = target.projectData.rootFolder
            val (parent, name) = resolveParent(sourceRoot, path)
            val targetParent = resolveFolder(targetRoot, targetFolder)
            val wanted = uniqueName(targetParent, name)

            val folder = parent.getFolder(name)
            val resultingPath = if (folder != null) {
                val created = targetParent.createFolder(wanted)
                copyFolderInto(folder, created, target, targetParent)
                if (move) folder.delete()
                created.pathname
            } else {
                val file = parent.getFile(name)
                    ?: throw IllegalArgumentException("'$path' does not exist in '$sourceProject'")
                val copied = file.copyTo(targetParent, TaskMonitor.DUMMY)
                if (copied.name != wanted) copied.setName(wanted)
                val identity = reissueIdentity(target, targetParent, wanted)
                val copyPath = targetFolder.trimEnd('/') + "/" + wanted
                identity.programId?.let {
                    ProjectIndex.setProgramId(targetProject, targetDirectory, copyPath, it)
                }
                identity.binaryId?.let {
                    ProjectIndex.setBinaryId(targetProject, targetDirectory, copyPath, it)
                }
                if (move) file.delete()
                targetParent.pathname.trimEnd('/') + "/" + wanted
            }
            logger.info(
                "{} '{}' from '{}' to '{}' as '{}'",
                if (move) "Moved" else "Copied", path, sourceProject, targetProject, wanted
            )
            return resultingPath
        } finally {
            runCatching { source?.close() }
            runCatching { target?.close() }
            runCatching { ProjectIndex.refresh(targetProject, targetDirectory) }
            runCatching { ProjectIndex.refresh(sourceProject, sourceDirectory) }
        }
    }

    /**
     * Read the identity of [path], assigning one if the program predates the
     * scheme, and record it in the index.
     *
     * Called when a conversation is about to use a program: the project is
     * opened and closed again before the worker takes it, so identity always
     * exists by the time the UI can ask for it.
     *
     * @return the id, or null when the program could not be opened.
     */
    fun ensureProgramIdentity(
        projectName: String,
        projectDirectory: Path,
        path: String,
    ): String? {
        val id = runCatching {
            mutateProject(projectName, projectDirectory) { project, root ->
                val (parent, name) = resolveParent(root, path)
                val program = project.openProgram(parent.pathname, name, false)
                try {
                    val tx = program.startTransaction("program-identity")
                    try {
                        ProgramIdentity.ensure(program)
                    } finally {
                        program.endTransaction(tx, true)
                    }
                    project.save(program)
                    // A copy inherits the binary it came from, so the index learns it the
                    // first time anyone opens the copy.
                    val knownBinary = ProgramIdentity.readBinaryId(program)
                    if (knownBinary != null)
                        ProjectIndex.setBinaryId(projectName, projectDirectory, path, knownBinary)
                    ProgramIdentity.read(program)
                } finally {
                    runCatching { project.close(program) }
                }
            }
        }.onFailure {
            logger.warn("Could not ensure an identity for '{}': {}", path, it.message)
        }.getOrNull()
        if (id != null) ProjectIndex.setProgramId(projectName, projectDirectory, path, id)
        return id
    }

    /**
     * Give a freshly copied program its own identity.
     *
     * A copy is a new artifact: sharing the source id would make two programs
     * indistinguishable to anything that tracks work by program.
     */
    private data class CopyIdentity(val programId: String?, val binaryId: Long?)

    private fun reissueIdentity(project: GhidraProject, folder: DomainFolder, fileName: String): CopyIdentity {
        return runCatching {
            val program = project.openProgram(folder.pathname, fileName, false) ?: return@runCatching null
            try {
                val tx = program.startTransaction("copy-identity")
                try {
                    ProgramIdentity.reissue(program)
                } finally {
                    program.endTransaction(tx, true)
                }
                project.save(program)
                // The program id is new; the binary id came along with the copy, because a
                // copy is another view of the same file rather than a different file.
                CopyIdentity(ProgramIdentity.read(program), ProgramIdentity.readBinaryId(program))
            } finally {
                runCatching { project.close(program) }
            }
        }.onFailure {
            logger.warn("Could not reissue the identity of '{}': {}", fileName, it.message)
        }.getOrNull() ?: CopyIdentity(null, null)
    }

    /** First free name in [folder] derived from [name] by adding `_1`, `_2`, … */
    private fun uniqueName(folder: DomainFolder, name: String): String {
        fun taken(candidate: String) =
            folder.getFolder(candidate) != null || folder.getFile(candidate) != null
        if (!taken(name)) return name
        var i = 1
        while (taken("${name}_$i")) i++
        return "${name}_$i"
    }

    private fun copyFolderInto(
        source: DomainFolder,
        target: DomainFolder,
        project: GhidraProject,
        targetParent: DomainFolder = target,
    ) {
        source.folders.forEach { child ->
            copyFolderInto(child, target.createFolder(uniqueName(target, child.name)), project, targetParent)
        }
        source.files.forEach { file ->
            val wanted = uniqueName(target, file.name)
            val copy = file.copyTo(target, TaskMonitor.DUMMY)
            if (copy.name != wanted) copy.setName(wanted)
            reissueIdentity(project, target, wanted)
        }
    }

    fun deleteEntry(projectName: String, projectDirectory: Path, path: String) {
        try {
            mutate(projectName, projectDirectory) { root ->
                val (parent, name) = resolveParent(root, path)
                val folder = parent.getFolder(name)
                if (folder != null) folder.delete() else
                    (parent.getFile(name) ?: throw IllegalArgumentException("'$path' does not exist"))
                        .delete()
                null
            }
        } catch (e: IllegalArgumentException) {
            // Already gone: a second click, or a row left behind by an older build. The
            // caller wanted it gone and it is, so the remaining index row is all to fix.
            if (!(e.message ?: "").contains("does not exist")) throw e
            logger.info("'{}' is already gone from '{}'; clearing its index row", path, projectName)
        }
        // The browser reads the index, not the project. Dropping the row is what the user
        // sees disappear; the refresh afterwards only keeps the rest of the tree honest —
        // and it refuses to write an empty tree, so it cannot be the only step here.
        ProjectIndex.removeEntry(projectName, projectDirectory, path)
        ProjectIndex.refresh(projectName, projectDirectory, force = true)
    }

    private fun <T> mutate(projectName: String, projectDirectory: Path, block: (DomainFolder) -> T): T =
        mutateProject(projectName, projectDirectory) { _, root -> block(root) }

    /** Like [mutate], but the caller also gets the project handle. */
    private fun <T> mutateProject(
        projectName: String,
        projectDirectory: Path,
        block: (GhidraProject, DomainFolder) -> T,
    ): T {
        requireExists(projectDirectory, projectName)
        var project: GhidraProject? = null
        val result = try {
            project = open(projectName, projectDirectory)
            block(project, project.projectData.rootFolder)
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

    /** Like [resolveFolder], but creates missing segments instead of failing. */
    private fun ensureFolder(root: DomainFolder, path: String): DomainFolder =
        path.trim('/').split('/').filter { it.isNotBlank() }.fold(root) { acc, segment ->
            acc.getFolder(segment) ?: run {
                require(acc.getFile(segment) == null) { "'$segment' is a program, not a folder" }
                acc.createFolder(segment)
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
