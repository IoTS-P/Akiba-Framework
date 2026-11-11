package org.iotsplab.akiba.server.routes

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import generic.io.JarWriter
import ghidra.app.decompiler.DecompInterface
import ghidra.framework.Application
import ghidra.framework.model.DomainFile
import ghidra.framework.model.DomainFolder
import ghidra.program.model.address.AddressSet
import ghidra.program.model.listing.CommentType
import ghidra.program.model.listing.Function
import ghidra.program.model.listing.Listing
import ghidra.program.model.listing.Program
import ghidra.util.task.ConsoleTaskMonitor
import ghidra.util.task.TaskMonitor
import io.ktor.http.*
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.*
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.data.database.DatabaseClient
import org.iotsplab.akiba.managers.ConfigManager
import org.iotsplab.akiba.managers.GarImporter
import org.iotsplab.akiba.managers.ProjectIndex
import org.iotsplab.akiba.managers.ProjectLock
import org.iotsplab.akiba.managers.ProjectLockOwner
import org.iotsplab.akiba.managers.ProjectPrograms
import org.iotsplab.akiba.managers.WorkspaceManager

data class GhidraProjectInfo(
    val name: String,
    val sessionCount: Int,
    val fileCount: Int,
    /** Who holds the project right now, or null when it is free. */
    val locked: ProjectLockOwner? = null,
)

private val projectTextExportLock = ReentrantLock()
private val projectTextExportMapper = com.fasterxml.jackson.databind.ObjectMapper()
    .registerKotlinModule()
    .enable(SerializationFeature.INDENT_OUTPUT)

/** Body of `POST /api/projects/{name}/export_text`. */
data class ProjectTextExportRequest(
    val format: String = "zip",
    val contents: List<String> = listOf("listing", "comments", "functions"),
    val includeComments: Boolean = true,
    val includeEolComment: Boolean = true,
    val includePlateComment: Boolean = true,
    val includePreComment: Boolean = true,
    val includePostComment: Boolean = false,
    val includeRepeatableComment: Boolean = false,
    val includeDecompile: Boolean = false,
    val decompileTimeoutSec: Int = 30,
    val includeData: Boolean = true,
    val includeUndefined: Boolean = false,
    val functionFilter: String? = null,
    val addressFilter: ProjectAddressFilter? = null,
    val maxFunctions: Int = 0,
    val maxFunctionSize: Int = 1 shl 20,
    val sortBy: String = "address",
)

data class ProjectAddressFilter(
    val start: String,
    val end: String,
)

data class ProjectTextExportReason(
    val code: String,
    val activeProject: String? = null,
    val workflows: List<Map<String, String?>> = emptyList(),
    val sessions: List<Map<String, String?>> = emptyList(),
)

data class ProjectTextExportStatus(
    val projectName: String,
    val projectExists: Boolean,
    val state: String,
    val activeProject: String? = null,
    val reasons: List<ProjectTextExportReason> = emptyList(),
    val hints: List<String> = emptyList(),
    val agentSessionsChecked: Boolean = false,
)

private data class ProjectTextExportOptions(
    val contents: Set<String>,
    val includeComments: Boolean,
    val includeEolComment: Boolean,
    val includePlateComment: Boolean,
    val includePreComment: Boolean,
    val includePostComment: Boolean,
    val includeRepeatableComment: Boolean,
    val includeDecompile: Boolean,
    val decompileTimeoutSec: Int,
    val includeData: Boolean,
    val includeUndefined: Boolean,
    val functionFilter: Regex?,
    val addressFilter: ProjectAddressFilter?,
    val maxFunctions: Int,
    val maxFunctionSize: Int,
    val sortBy: String,
)

/**
 * Routes for browsing & exporting Ghidra projects. These are pure disk-level
 * operations under `ghidra_projects/<username>/` and do **not** require a
 * running akiba_db_daemon instance.
 */
/** Body of `POST /projects/{name}/programs/copy`. */
data class CopyProgramRequest(
    val fromProject: String,
    val programName: String,
    val asName: String? = null,
)

/** Body of `POST /projects/{name}/programs/import`. */
data class ImportProgramRequest(
    val binaryId: String,
    val folder: String = "",
    val asName: String? = null,
)

/** Body of `POST /projects/{name}/folders` and `PATCH /projects/{name}/entries`. */
data class FolderRequest(val path: String = "", val name: String)
data class RenameRequest(val path: String, val newName: String)

/** Rename a whole Ghidra project (the .gpr/.rep pair on disk). */
data class ProjectRenameRequest(val newName: String)

fun Route.projectRoutes(daemonHost: String, daemonPort: Int) {

    val logger: Logger = LogManager.getLogger("ProjectRoutes")

    // Projects are operator data, and every one of these handlers used to answer
    // anonymous callers because the root path needs no username. The browser
    // always sends a JWT, so anything below must be signed in.
    get("/projects") {
        if (!call.requireAuthenticated()) return@get
        try {
            val projectDirectory = call.currentUserGhidraProjectsRoot()
            Files.createDirectories(projectDirectory)

            // Best-effort session counts. The Projects page works without a daemon —
            // any DB error is logged and surfaced as zeros rather than breaking the
            // page. This keeps the page usable when no instance has been selected.
            val sessionCounts = mutableMapOf<String, Int>()
            val instance = call.instanceHeader()
            // The instance must belong to the caller: the server authenticates
            // to the daemon as the instance owner.
            if (instance != null && !call.requireInstanceOwnership(instance))
                return@get
            if (instance != null) {
                try {
                    withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                        org.iotsplab.akiba.data.database.AgentDatabaseClient(dbClient)
                            .listSessions(status = "closed", limit = 500)
                            .mapNotNull { it.projectName?.takeIf { p -> p.isNotBlank() } }
                            .forEach { sessionCounts[it] = (sessionCounts[it] ?: 0) + 1 }
                    }
                } catch (_: Exception) {
                    // DB unavailable / not selected — keep zero counts and proceed.
                }
            }

            val diskProjects = WorkspaceManager.listGhidraProjects(projectDirectory)
            val diskProjectsSet = diskProjects.toSet()
            // Only show projects that still exist on disk.  A project may
            // have been deleted via the DELETE endpoint but still have
            // closed session rows in the DB — those should not appear.
            val seenProjects = (sessionCounts.keys + diskProjects).toSet()
                .filter { it in diskProjectsSet }

            val result = seenProjects.map { name ->
                // Fast file count: number of packed `.gzf` files in the project's
                // .rep/ directory. Avoids the cost of opening each project with
                // Ghidra just to count contents.
                var fileCount = 0
                val repDir = projectDirectory.resolve("$name.rep")
                if (repDir.isDirectory()) {
                    Files.list(repDir).use { stream ->
                        fileCount = stream
                            .filter { it.isRegularFile() && it.name.endsWith(".gzf") }
                            .count()
                            .toInt()
                    }
                }

                GhidraProjectInfo(
                    name = name,
                    sessionCount = sessionCounts[name] ?: 0,
                    fileCount = fileCount,
                    locked = ProjectLock.owner(projectDirectory, name)
                )
            }.sortedWith(compareByDescending<GhidraProjectInfo> { it.sessionCount }.thenBy { it.name })

            call.respond(mapOf("projects" to result))
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    /**
     * Delete a Ghidra project from disk. Optionally also delete associated
     * log directories by scanning each log directory's `config.json` for a
     * matching project `name` (or `forkTo` in fork mode), the project's
     * agent session trees, and/or its workspace directory.
     *
     * Query parameters (all optional, default false):
     *  - `deleteLogs`           delete associated log directories
     *  - `deleteAgentSessions`  delete all agent session trees of the project
     *  - `deleteWorkspace`      delete `<workspaceRoot>/<projectName>/`
     */
    /**
     * Rename a Ghidra project: the `.gpr` and `.rep` move together, so the
     * name stays consistent and Ghidra keeps opening it by the new name.
     */
    patch("/projects/{name}") {
        if (!call.requireAuthenticated()) return@patch
        val projectName = call.parameters["name"]
            ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing project name"))
        if (!isValidProjectName(projectName)) {
            return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid project name: $projectName"))
        }
        val req = call.receive<ProjectRenameRequest>()
        val newName = req.newName.trim()
        if (!isValidProjectName(newName)) {
            return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid project name: $newName"))
        }
        if (newName == projectName) {
            return@patch call.respond(mapOf("name" to newName, "renamed" to false))
        }

        val projectDirectory = call.currentUserGhidraProjectsRoot()
        val gprFile = projectDirectory.resolve("$projectName.gpr")
        val repDir = projectDirectory.resolve("$projectName.rep")
        if (!gprFile.isRegularFile() && !repDir.isDirectory()) {
            return@patch call.respond(HttpStatusCode.NotFound, mapOf("error" to "Project '$projectName' not found"))
        }
        if (Files.exists(projectDirectory.resolve("$newName.gpr")) ||
            Files.exists(projectDirectory.resolve("$newName.rep"))
        ) {
            return@patch call.respond(
                HttpStatusCode.Conflict,
                mapOf("error" to "A project named '$newName' already exists")
            )
        }
        ProjectLock.owner(projectDirectory, projectName)?.let { holder ->
            return@patch call.respond(
                HttpStatusCode.Conflict,
                mapOf(
                    "error" to "Project '$projectName' is in use by session " +
                        "${holder.sessionId ?: "?"} (pid ${holder.pid})",
                    "locked" to holder
                )
            )
        }

        try {
            if (WorkspaceManager.isProjectInitialized &&
                WorkspaceManager.activeProjectName == projectName
            ) {
                WorkspaceManager.releaseActiveProject()
            }
            if (Files.exists(gprFile)) Files.move(gprFile, projectDirectory.resolve("$newName.gpr"))
            if (Files.exists(repDir)) Files.move(repDir, projectDirectory.resolve("$newName.rep"))
            // The index is keyed by project name, so the old rows have to go.
            ProjectIndex.forget(projectName, projectDirectory)
            ProjectIndex.refresh(newName, projectDirectory)
            logger.info("[projects] renamed '$projectName' to '$newName'")
            call.respond(mapOf("name" to newName, "renamed" to true))
        } catch (e: Exception) {
            logger.error("[projects] rename '$projectName' -> '$newName' failed: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (e.message ?: "Rename failed"))
            )
        }
    }

    delete("/projects/{name}") {
        if (!call.requireAuthenticated()) return@delete
        val projectName = call.parameters["name"]
            ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing project name"))
        if (!isValidProjectName(projectName)) {
            return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid project name: $projectName"))
        }

        val deleteLogs = call.request.queryParameters["deleteLogs"]?.equals("true", ignoreCase = true) == true
        val deleteAgentSessions = call.request.queryParameters["deleteAgentSessions"]?.equals("true", ignoreCase = true) == true
        val deleteWorkspace = call.request.queryParameters["deleteWorkspace"]?.equals("true", ignoreCase = true) == true
        val projectDirectory = call.currentUserGhidraProjectsRoot()

        val grpFile = projectDirectory.resolve("$projectName.gpr")
        val repDir = projectDirectory.resolve("$projectName.rep")
        val lockFile = projectDirectory.resolve("$projectName.lock")
        val lockTildeFile = projectDirectory.resolve("$projectName.lock~")

        if (!grpFile.isRegularFile() && !repDir.isDirectory()) {
            return@delete call.respond(HttpStatusCode.NotFound, mapOf("error" to "Project '$projectName' not found"))
        }

        // Deleting under a live session would pull the .rep out from under it;
        // the operator stops the conversation first.
        ProjectLock.owner(projectDirectory, projectName)?.let { holder ->
            return@delete call.respond(
                HttpStatusCode.Conflict,
                mapOf(
                    "error" to "Project '$projectName' is in use by session " +
                        "${holder.sessionId ?: "?"} (pid ${holder.pid})",
                    "locked" to holder
                )
            )
        }

        // Release the active project if it's the one being deleted.
        try {
            if (WorkspaceManager.isProjectInitialized &&
                WorkspaceManager.activeProjectName == projectName) {
                WorkspaceManager.releaseActiveProject()
            }
        } catch (_: Exception) { /* best-effort */ }

        val deletedFiles = mutableListOf<String>()
        val errors = mutableListOf<String>()

        // Delete project files on disk.
        for (f in listOf(grpFile, repDir, lockFile, lockTildeFile)) {
            try {
                if (Files.exists(f)) {
                    if (f.isDirectory()) f.toFile().deleteRecursively()
                    else Files.deleteIfExists(f)
                    deletedFiles.add(f.name)
                }
            } catch (e: Exception) {
                errors.add("${f.name}: ${e.message}")
            }
        }

        // Optionally scan and delete associated log directories.
        val deletedLogDirs = mutableListOf<String>()
        if (deleteLogs) {
            try {
                val userLogsRoot = call.currentUserLogsRoot()
                // Some run types (import / manual-agent / workflow) write
                // their log dirs at the TOP level of ~/.akiba/logs without
                // the username suffix, so scan both levels. The user dir
                // itself is skipped at the top level (already covered by
                // the user-root scan).
                val roots = listOfNotNull(userLogsRoot, userLogsRoot.parent).distinct()
                // Well-known system log dirs that must never be deleted as
                // "project logs", even if a project shares their name.
                val systemLogDirs = setOf("server", "workflows")
                for (root in roots) {
                    if (!root.isDirectory()) continue
                    Files.list(root).use { stream ->
                        stream.filter {
                            it.isDirectory() && it != userLogsRoot && it.name !in systemLogDirs
                        }.forEach { logDir ->
                            val configFile = logDir.resolve("config.json")
                            if (configFile.isRegularFile()) {
                                try {
                                    val configText = Files.readString(configFile)
                                    val mapper = com.fasterxml.jackson.databind.ObjectMapper()
                                        .registerKotlinModule()
                                    val rootNode = mapper.readTree(configText)
                                    // mergeConfigs() writes the main config
                                    // under the "main" key — unwrap it.
                                    val mainNode = rootNode.get("main") ?: rootNode
                                    val withGhidraProject = mainNode.get("withGhidraProject")
                                    if (withGhidraProject != null) {
                                        val mode = withGhidraProject.get("mode")?.asText("") ?: ""
                                        val name = withGhidraProject.get("name")?.asText("") ?: ""
                                        val forkTo = withGhidraProject.get("forkTo")?.asText("") ?: ""
                                        val continueLog = withGhidraProject.get("continueLog")?.asText("") ?: ""

                                        // `name` is the project association
                                        // in every mode; forkTo/continueLog
                                        // are additional hints when the run
                                        // dir is named differently.
                                        val isAssociated =
                                            name == projectName || logDir.name == projectName ||
                                                (mode == "fork" && forkTo.isNotBlank() &&
                                                    (forkTo == projectName || forkTo.endsWith("/$projectName"))) ||
                                                (mode == "base" && continueLog.isNotBlank() &&
                                                    (continueLog == projectName || continueLog.endsWith("/$projectName")))

                                        if (isAssociated) {
                                            logDir.toFile().deleteRecursively()
                                            deletedLogDirs.add(logDir.name)
                                        }
                                    }
                                } catch (_: Exception) { /* skip unreadable config */ }
                            } else if (logDir.name == projectName) {
                                // No config.json but directory name matches — delete it.
                                logDir.toFile().deleteRecursively()
                                deletedLogDirs.add(logDir.name)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                errors.add("log scan: ${e.message}")
            }
        }

        // Optionally delete the project's workspace directory
        // (<workspaceRoot>/<projectName>/).  The root comes from
        // server-side per-user config (never from the request) and
        // projectName already passed isValidProjectName, so the
        // resolved path cannot escape the workspace root.
        var deletedWorkspace = false
        if (deleteWorkspace) {
            try {
                val projectWorkspace = call.currentUserWorkspaceRoot().resolve(projectName)
                if (projectWorkspace.isDirectory()) {
                    projectWorkspace.toFile().deleteRecursively()
                    deletedWorkspace = true
                }
            } catch (e: Exception) {
                errors.add("workspace: ${e.message}")
            }
        }

        // Optionally delete every agent session TREE associated with
        // this project: the daemon recursively walks parent_session_id
        // from every project-matching root (children don't carry the
        // project_name themselves), and dependent rows go away via
        // ON DELETE CASCADE.
        var deletedAgentSessions = 0
        if (deleteAgentSessions) {
            val instance = call.instanceHeader()
            // The instance must belong to the caller: the server authenticates
            // to the daemon as the instance owner.
            if (instance != null && !call.requireInstanceOwnership(instance))
                return@delete
            if (instance == null) {
                errors.add("agent sessions: no instance selected — skipped")
            } else {
                try {
                    withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                        deletedAgentSessions = AgentDatabaseClient(dbClient)
                            .deleteSessionsByProject(projectName)
                    }
                } catch (e: Exception) {
                    errors.add("agent sessions: ${e.message}")
                }
            }
        }

        // The tree rows are keyed by the project's directory and would outlive the files.
        ProjectIndex.forget(projectName, projectDirectory)

        call.respond(mapOf(
            "message" to "Project '$projectName' deleted",
            "deletedFiles" to deletedFiles,
            "deletedLogDirs" to deletedLogDirs,
            "deletedAgentSessions" to deletedAgentSessions,
            "deletedWorkspace" to deletedWorkspace,
            "errors" to errors
        ))
    }

    /**
     * List the domain files (programs) inside a Ghidra project.
     *
     * Opens the project via `WorkspaceManager.openOrCreateInteractiveProject`,
     * refreshes the project-data index from disk, and returns every domain
     * file whose stored class is a `Program` implementation (e.g. `ProgramDB`).
     *
     * Used by the New Session modal so the user can pick which program to
     * interact with — a single binary may exist in multiple projects with
     * different analysis states.
     */
    get("/projects/{name}/programs") {
        if (!call.requireAuthenticated()) return@get
        val projectName = call.parameters["name"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing project name"))
        if (!isValidProjectName(projectName)) {
            return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid project name: $projectName"))
        }

        val projectDirectory = call.currentUserGhidraProjectsRoot()
        val grpFile = projectDirectory.resolve("$projectName.gpr")
        val repFile = projectDirectory.resolve("$projectName.rep")
        if (!grpFile.isRegularFile() || !repFile.isDirectory()) {
            return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Project '$projectName' not found"))
        }

        try {
            WorkspaceManager.openOrCreateInteractiveProject(projectName, false, projectDirectory)
            val project = WorkspaceManager.project

            try {
                project.projectData.refresh(true)
            } catch (_: Exception) { /* best-effort */ }

            val programs = mutableListOf<Map<String, Any?>>()
            collectDomainFilesForListing(project.projectData.rootFolder).forEach { domainFile: DomainFile ->
                val doc = domainFile.domainObjectClass
                if (doc != null && Program::class.java.isAssignableFrom(doc)) {
                    programs.add(mapOf(
                        "name" to domainFile.name,
                        "path" to domainFile.pathname,
                    ))
                }
            }

            WorkspaceManager.releaseActiveProject()

            call.respond(mapOf("programs" to programs))
        } catch (e: Exception) {
            logger.error("[programs] failed to list programs in '$projectName': ${e.message}", e)
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    /**
     * Export a project's workspace directory as a ZIP archive.
     *
     * The workspace is organised as `<workspaceRoot>/<projectName>/<id>/<ModuleClassName>/`.
     * This endpoint zips only the subdirectory matching the requested project name.
     */
    get("/projects/{name}/export_workspace") {
        if (!call.requireAuthenticated()) return@get
        val projectName = call.parameters["name"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing project name"))
        if (!isValidProjectName(projectName)) {
            return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid project name: $projectName"))
        }

        val workspaceRoot = call.currentUserWorkspaceRoot()
        val projectWorkspace = workspaceRoot.resolve(projectName)
        if (!Files.exists(projectWorkspace) || !projectWorkspace.isDirectory()) {
            return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "No workspace found for project '$projectName'"))
        }

        try {
            val baos = ByteArrayOutputStream()
            ZipOutputStream(baos).use { zos ->
                Files.walk(projectWorkspace).use { stream ->
                    stream.filter { it.isRegularFile() }.forEach { path ->
                        val relative = projectWorkspace.relativize(path).toString().replace('\\', '/')
                        zos.putNextEntry(ZipEntry(relative))
                        Files.copy(path, zos)
                        zos.closeEntry()
                    }
                }
            }
            val zipBytes = baos.toByteArray()
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName, "$projectName-workspace.zip"
                ).toString()
            )
            call.respondBytes(zipBytes, ContentType.Application.Zip)
        } catch (e: Exception) {
            logger.error("[export_workspace] failed for '$projectName': ${e.message}", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to export workspace: ${e.message}"))
        }
    }

    /**
     * List the agent sessions that worked on a project. Top-level
     * sessions only — sub-agents are reachable through their parent's
     * tree export. Requires an instance selection (sessions live in the
     * daemon DB, not on disk).
     */
    get("/projects/{name}/sessions") {
        if (!call.requireAuthenticated()) return@get
        val projectName = call.parameters["name"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing project name"))
        if (!isValidProjectName(projectName)) {
            return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid project name: $projectName"))
        }
        val instance = call.requireInstanceHeader() ?: return@get
        try {
            val sessions = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                AgentDatabaseClient(dbClient)
                    .listSessions(limit = 1000)
                    .filter { it.projectName == projectName }
                    .map {
                        mapOf(
                            "sessionId" to it.sessionId,
                            "sessionName" to it.sessionName,
                            "status" to it.status,
                            "modelName" to it.modelName,
                            "createdAt" to it.createdAt,
                        )
                    }
            }
            call.respond(mapOf("sessions" to sessions))
        } catch (e: Exception) {
            logger.error("[project sessions] failed for '$projectName': ${e.message}", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to list sessions: ${e.message}"))
        }
    }

    /**
     * The project's file tree, served from the database index.
     *
     * Never opens the project: an agent session holding it would turn this page
     * into a 409. A project that has no rows yet is indexed once (best effort),
     * and if even that fails the answer is an empty tree flagged `stale`.
     */
    get("/projects/{name}/tree") {
        if (!call.requireAuthenticated()) return@get
        val name = call.parameters["name"].orEmpty()
        if (!isValidProjectName(name)) {
            return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid project name"))
        }
        val projectDirectory = call.currentUserGhidraProjectsRoot()
        if (!projectDirectory.resolve("$name.gpr").isRegularFile() ||
            !projectDirectory.resolve("$name.rep").isDirectory()
        ) {
            return@get call.respond(
                HttpStatusCode.NotFound,
                mapOf("error" to "Project '$name' does not exist on disk")
            )
        }

        val indexed = runCatching { ProjectIndex.entries(name, projectDirectory) }
            .getOrElse {
                logger.warn("[tree] index unavailable for '$name': ${it.message}")
                null
            }
        if (indexed == null) {
            return@get call.respond(mapOf("entries" to emptyList<Any>(), "stale" to true))
        }
        if (indexed.isNotEmpty()) {
            // Refresh in the background when the copy is old and the project is
            // free, then answer with what is on disk now rather than last week.
            ProjectIndex.refreshIfStale(name, projectDirectory)
            val fresh = runCatching { ProjectIndex.entries(name, projectDirectory) }
                .getOrDefault(indexed)
            return@get call.respond(mapOf("entries" to fresh.toWireEntries(), "stale" to false))
        }

        // Nothing indexed yet: this is the one moment the project is opened,
        // and a busy project simply leaves the browser with an empty, stale tree.
        val refreshed = ProjectIndex.refresh(name, projectDirectory)
        val rows = if (refreshed) {
            runCatching { ProjectIndex.entries(name, projectDirectory) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        call.respond(mapOf("entries" to rows.toWireEntries(), "stale" to !refreshed))
    }

    post("/projects/{name}/folders") {
        if (!call.requireAuthenticated()) return@post
        val name = call.parameters["name"].orEmpty()
        if (!isValidProjectName(name)) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid project name"))
        }
        val req = call.receive<FolderRequest>()
        try {
            ProjectPrograms.createFolder(name, call.currentUserGhidraProjectsRoot(), req.path, req.name)
            call.respond(mapOf("status" to "ok"))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Cannot create folder")))
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    patch("/projects/{name}/entries") {
        if (!call.requireAuthenticated()) return@patch
        val name = call.parameters["name"].orEmpty()
        if (!isValidProjectName(name)) {
            return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid project name"))
        }
        val req = call.receive<RenameRequest>()
        try {
            ProjectPrograms.renameEntry(name, call.currentUserGhidraProjectsRoot(), req.path, req.newName)
            call.respond(mapOf("status" to "ok"))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Cannot rename")))
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    delete("/projects/{name}/entries") {
        if (!call.requireAuthenticated()) return@delete
        val name = call.parameters["name"].orEmpty()
        if (!isValidProjectName(name)) {
            return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid project name"))
        }
        val path = call.request.queryParameters["path"].orEmpty()
        if (path.isBlank()) {
            return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing path"))
        }
        try {
            ProjectPrograms.deleteEntry(name, call.currentUserGhidraProjectsRoot(), path)
            call.respond(mapOf("status" to "ok"))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Cannot delete")))
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    /**
     * Copy a program from another project into this one.
     *
     * Copying duplicates the Ghidra domain file: the two projects diverge from
     * that moment on, which is why this is an explicit operator action rather
     * than something a background task does.
     */
    post("/projects/{name}/programs/copy") {
        if (!call.requireAuthenticated()) return@post
        val targetProject = call.parameters["name"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing project name"))
        if (!isValidProjectName(targetProject)) {
            return@post call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "Invalid project name: $targetProject"))
        }
        val req = call.receive<CopyProgramRequest>()
        if (!isValidProjectName(req.fromProject)) {
            return@post call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "Invalid source project: ${req.fromProject}"))
        }
        if (req.programName.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing program name"))
        }

        try {
            val outcome = ProjectPrograms.copy(
                sourceProject = req.fromProject,
                programName = req.programName,
                targetProject = targetProject,
                projectDirectory = call.currentUserGhidraProjectsRoot(),
                asName = req.asName,
            )
            call.respond(mapOf(
                "source" to outcome.source,
                "target" to outcome.target,
                "program" to outcome.program,
                "targetFile" to outcome.targetFile,
            ))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Copy failed")))
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    /**
     * Import a binary registered in the selected instance into this project.
     *
     * The binary's stored copy (`<binariesRoot>/original/<id>.bin`) is loaded
     * through Ghidra, saved into the requested folder and recorded in the
     * project index. The instance is required: only its database knows which
     * binary ids exist.
     */
    post("/projects/{name}/programs/import") {
        if (!call.requireAuthenticated()) return@post
        val targetProject = call.parameters["name"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing project name"))
        if (!isValidProjectName(targetProject)) {
            return@post call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "Invalid project name: $targetProject"))
        }
        val instance = call.requireInstanceHeader() ?: return@post
        val req = call.receive<ImportProgramRequest>()

        try {
            val binaryId = req.binaryId.trim().toLongOrNull()
            require(binaryId != null && binaryId > 0) { "Invalid binary id: '${req.binaryId}'" }

            // An unknown id comes back from the daemon as an empty object, which
            // fails to deserialize — that is the "not in this instance" case.
            val binary = try {
                withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                    dbClient.getMetadata(binaryId)
                }
            } catch (e: DatabaseClient.DatabaseDaemonException) {
                throw e
            } catch (_: JacksonException) {
                null
            }
            require(binary != null) { "Binary '$binaryId' is not in instance '$instance'" }

            // Mirrors WorkspaceManager.initBinDirectories' auto-computed root.
            val binaryFile = Path.of(
                System.getProperty("user.home"), ".akiba", "binaries", instance, "original", "$binaryId.bin"
            )
            require(Files.isRegularFile(binaryFile)) {
                "Binary '$binaryId' has no stored file in instance '$instance'"
            }

            val originalName = binary.originalPath.substringAfterLast('/')
                .ifBlank { binaryFile.fileName.toString() }
            val outcome = ProjectPrograms.importProgram(
                projectName = targetProject,
                projectDirectory = call.currentUserGhidraProjectsRoot(),
                source = binaryFile,
                folder = req.folder,
                asName = req.asName?.takeIf { it.isNotBlank() } ?: originalName,
            )
            call.respond(mapOf(
                "target" to targetProject,
                "program" to outcome.program,
                "folder" to outcome.folder,
            ))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Import failed")))
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    get("/projects/{name}/export") {
        if (!call.requireAuthenticated()) return@get
        val projectName = call.parameters["name"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing project name"))

        // Sanitize projectName to the same character set Ghidra allows in
        // project names so the on-disk paths we resolve are well-formed.
        if (!projectName.matches(Regex("^[A-Za-z0-9._-]{1,64}$"))) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid project name: $projectName")
            )
        }

        val projectDirectory = call.currentUserGhidraProjectsRoot()
        Files.createDirectories(projectDirectory)

        val markerFile = projectDirectory.resolve("$projectName.gpr").toFile()
        val repDir = projectDirectory.resolve("$projectName.rep")

        // Pre-flight visibility into the export. We log every step at INFO
        // level so a 500 can be diagnosed from the server log alone, even
        // if the client only sees an opaque error message.
        logger.info("[gar-export] start user='${call.currentUsernameOrDefault()}' project='$projectName'")
        logger.info("[gar-export] projectRoot='$projectDirectory'")
        logger.info("[gar-export] markerFile='${markerFile.absolutePath}' exists=${markerFile.isFile} size=${markerFile.length()}")
        logger.info("[gar-export] repDir='${repDir.toAbsolutePath()}' exists=${repDir.isDirectory()}")

        if (!repDir.isDirectory()) {
            logger.warn("[gar-export] missing .rep directory for project '$projectName'")
            return@get call.respond(
                HttpStatusCode.NotFound,
                mapOf("error" to "Project data directory missing for '$projectName'")
            )
        }

        // Best-effort Ghidra Application warm-up. `WorkspaceManager.initializeGhidra()`
        // is idempotent (it short-circuits on `Application.isInitialized()`) so
        // we call it here primarily to surface any framework-misconfiguration
        // early. The export itself does not depend on the Application being up
        // because we only touch files on disk.
        if (!Application.isInitialized()) {
            logger.info("[gar-export] Ghidra Application not initialized; attempting lazy init")
            val ok = try {
                WorkspaceManager.initializeGhidra()
            } catch (e: Exception) {
                logger.warn("[gar-export] WorkspaceManager.initializeGhidra() threw: ${e.message}")
                false
            }
            logger.info("[gar-export] lazy Ghidra init result=$ok initialized=${Application.isInitialized()}")
        }

        val garBytes: ByteArray
        val tempDir: Path = try {
            Files.createTempDirectory("akiba_gar_")
        } catch (e: Exception) {
            logger.error("[gar-export] failed to create temp dir: ${e.message}", e)
            val (status, body) = errorPayload(e)
            call.respond(status, body)
            return@get
        }
        try {
            val garPath = tempDir.resolve("$projectName.gar")
            val entryCount = archiveProject(markerFile, repDir, garPath.toFile(), projectName, logger)
            garBytes = Files.readAllBytes(garPath)
            logger.info(
                "[gar-export] success project='$projectName' entries=$entryCount " +
                    "garBytes=${garBytes.size} tmpDir='$tempDir'"
            )
        } catch (e: Exception) {
            // Log full stack so the 500 is diagnosable from the server log
            // even when the wire response is just `{"error": "..."}`.
            logger.error("[gar-export] failed for project '$projectName': ${e.message}", e)
            val (status, body) = errorPayload(e)
            call.respond(status, body)
            return@get
        } finally {
            try {
                Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            } catch (e: Exception) {
                logger.warn("[gar-export] temp cleanup failed for $tempDir: ${e.message}")
            }
        }

        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(
                ContentDisposition.Parameters.FileName, "$projectName.gar"
            ).toString()
        )
        call.respondBytes(garBytes, ContentType.Application.Zip)
    }

    post("/projects/import-gar") {
        if (!call.requireAuthenticated()) return@post
        val instance = call.instanceHeader()
            ?: return@post call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Missing X-Akiba-Instance header")
            )
        // The instance must belong to the caller: the server authenticates to
        // the daemon as the instance owner.
        if (!call.requireInstanceOwnership(instance)) return@post
        val projectDirectory = call.currentUserGhidraProjectsRoot()
        Files.createDirectories(projectDirectory)

        var garTemp: Path? = null
        var projectName: String? = null
        val uploadDir = Files.createTempDirectory("akiba_gar_upload_")
        try {
            call.receiveMultipart().forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        val origName = part.originalFileName ?: "project.gar"
                        val dest = uploadDir.resolve(origName)
                        // streamProvider is deprecated in ktor 3.x in favour of
                        // provider() (ByteReadChannel), but the InputStream form
                        // is simpler for Files.copy and matches FileRoutes.
                        // Suppressed locally rather than migrating the whole
                        // upload path to ByteReadChannel.
                        @Suppress("DEPRECATION")
                        val input = part.streamProvider()
                        input.use { ins ->
                            Files.copy(ins, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        }
                        if (garTemp == null) garTemp = dest
                    }
                    is PartData.FormItem -> {
                        if (part.name == "projectName") {
                            projectName = part.value.takeIf { it.isNotBlank() }
                        }
                    }
                    else -> {}
                }
                part.dispose()
            }
            val garFile = garTemp
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No .gar file uploaded"))
            if (!garFile.fileName.toString().endsWith(".gar")) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Uploaded file must have a .gar extension")
                )
            }

            val resolvedName = projectName ?: garFile.fileName.toString().removeSuffix(".gar")
            if (!isValidProjectName(resolvedName)) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid project name: $resolvedName")
                )
            }

            // binaryRoot mirrors WorkspaceManager.initBinaryPaths' auto-compute
            // default so newly-imported binaries land next to those imported
            // via the regular /files/import subprocess.
            val binaryRoot = Path.of(
                System.getProperty("user.home"), ".akiba", "binaries", instance, "original"
            )

            val result = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                GarImporter.importGar(
                    garFile = garFile,
                    newProjectName = resolvedName,
                    projectRoot = projectDirectory,
                    dbClient = dbClient,
                    binaryRoot = binaryRoot,
                    logger = logger,
                )
            }

            call.respond(mapOf(
                "projectName" to result.projectName,
                "projectPath" to result.projectPath.toString(),
                "programs" to result.programs.map { p ->
                    mapOf(
                        "name" to p.domainFileName,
                        "renamedTo" to p.renamedTo,
                        "binaryId" to p.binaryId,
                        "checksum" to p.checksum,
                        "newlyImported" to p.newlyImported,
                        "error" to p.error,
                    )
                },
            ))
        } catch (e: Exception) {
            logger.error("[gar-import] failed: {}", e.message, e)
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        } finally {
            try { uploadDir.toFile().deleteRecursively() } catch (_: Exception) {}
        }
    }
}

/**
 * Constants that match Ghidra's `ArchivePlugin`. They live as `static final`
 * fields there and are referenced by `ArchiveTask` and `RestoreTask`. We keep
 * them in this file because we don't depend on the `ArchivePlugin` class
 * (which is GUI-only).
 */
private object GarFormat {
    /** File extension of Ghidra's per-project lock file — excluded from archives. */
    const val DB_LOCK_EXT = ".ulock"

    /** Name of the magic entry that marks a `.gar` archive as JAR-format. */
    const val JAR_VERSION_TAG = "JAR_FORMAT"
}

/**
 * Build a `.gar` archive that mirrors the on-disk layout of a Ghidra project.
 *
 * This is a faithful re-implementation of `ArchiveTask.writeProject` /
 * `ArchiveTask.writeProjectDirs` from upstream Ghidra, adapted for headless
 * (server) invocation. Keeping the layout identical ensures the resulting
 * archive can be opened with `File -> Restore Project...` in the Ghidra GUI.
 *
 * @return number of entries written into the archive (used for diagnostic
 *         logging at the call site).
 */
private fun archiveProject(
    markerFile: java.io.File,
    repDir: Path,
    outFile: java.io.File,
    projectName: String,
    logger: Logger? = null,
): Int {
    var entries = 0
    JarOutputStream(FileOutputStream(outFile)).use { jarOut ->
        // Mirror ArchiveTask's "Ghidra archive file for <name> project." comment.
        jarOut.setComment("Ghidra archive file for $projectName project.")

        // The JarWriter filters out lock files via its `excludedExtensions`
        // argument; .ulock files are created while a project is open and are
        // meaningless in an archive.
        val writer = JarWriter(jarOut, arrayOf(GarFormat.DB_LOCK_EXT))

        // 1. Write the `<projectName>.gpr` marker file as the FIRST entry, even
        //    when it is empty: `ArchivePlugin.isJarFormat` validates an archive
        //    by reading the SECOND entry and comparing its name with
        //    `JAR_VERSION_TAG = "JAR_FORMAT"`, so omitting `.gpr` shifts
        //    `JAR_FORMAT` to index 0 and Ghidra rejects the file.
        //    The content is ignored (`RestoreTask.processFile` filters `.gpr`
        //    and `createProjectMarkerFile()` recreates it), so a marker missing
        //    on disk still gets a 0-byte placeholder to keep indices correct.
        val markerBytes = if (markerFile.isFile) {
            try { Files.readAllBytes(markerFile.toPath()) } catch (_: Exception) { ByteArray(0) }
        } else {
            ByteArray(0)
        }
        logger?.info(
            "[gar-export] writing .gpr entry name='$projectName.gpr' " +
                "size=${markerBytes.size} onDiskExists=${markerFile.isFile}"
        )
        val markerEntry = ZipEntry("$projectName.gpr")
        jarOut.putNextEntry(markerEntry)
        if (markerBytes.isNotEmpty()) {
            jarOut.write(markerBytes)
        }
        jarOut.closeEntry()
        entries++

        // 2. Write the magic `JAR_FORMAT` entry. `RestoreTask.verifyArchive`
        //    looks up this entry by name (`/JAR_FORMAT`) — if it's missing
        //    or appears in the wrong position Ghidra refuses to restore.
        val magicEntry = ZipEntry(GarFormat.JAR_VERSION_TAG)
        jarOut.putNextEntry(magicEntry)
        jarOut.closeEntry()
        entries++

        // 3. Recursively pack the contents of the project's `.rep/`
        //    directory. Each top-level subdirectory is written with an empty
        //    jarPath so its contents land at the archive root (e.g.
        //    `idata/foo.gzf`) — matching the layout that
        //    `RestoreTask.processFile` expects when re-importing.
        val subDirs = Files.list(repDir).use { stream ->
            stream.filter { it.isDirectory() }.toList()
        }
        for (sub in subDirs) {
            val subFile = sub.toFile()
            if (writer.outputRecursively(subFile, "", TaskMonitor.DUMMY)) {
                // JarWriter doesn't tell us how many entries it wrote, but
                // we can at least attribute the success to the right
                // subdirectory for the post-mortem log.
                val subEntryCount = Files.walk(sub).use { walk ->
                    walk.filter { it.isRegularFile() && !it.name.endsWith(GarFormat.DB_LOCK_EXT) }.count()
                }
                entries += subEntryCount.toInt()
            } else {
                throw java.io.IOException(
                    "Failed to pack directory into archive: $sub"
                )
            }
        }
        // Files at the root of `.rep/` are intentionally skipped, mirroring
        // `ArchiveTask.writeProjectDirs` which only processes directories.
    }
    return entries
}

private val TEXT_EXPORT_CONTENTS = setOf("listing", "decompile", "comments", "functions", "data")
private val TEXT_EXPORT_SORTS = setOf("address", "name", "size")
private val FINISHED_SESSION_STATES = setOf("closed", "completed", "cancelled", "error", "failed")

private fun isValidProjectName(name: String): Boolean =
    Regex("^[A-Za-z0-9._-]{1,64}$").matches(name) && !name.contains("..")

/** Rows of the tree route, in the shape the Projects browser consumes. */
private fun List<ProjectIndex.Entry>.toWireEntries(): List<Map<String, Any>> =
    map { mapOf("path" to it.path, "name" to it.name, "folder" to it.folder, "size" to it.size) }

/** Find the `bin/akiba` launch script (same logic as FileRoutes / WorkflowRoutes). */
/** Recursively collect all files under [root] into [zip], preserving relative paths. */
private fun collectExportFiles(root: Path, current: Path, zip: ZipOutputStream) {
    if (!Files.exists(current)) return
    Files.list(current).use { stream ->
        stream.forEach { path ->
            if (Files.isDirectory(path)) {
                collectExportFiles(root, path, zip)
            } else {
                val relative = root.relativize(path).toString().replace('\\', '/')
                zip.putNextEntry(ZipEntry(relative))
                Files.copy(path, zip)
                zip.closeEntry()
            }
        }
    }
}

/** Recursively collect all domain files under a folder (for the programs listing endpoint). */
private fun collectDomainFilesForListing(folder: DomainFolder): List<DomainFile> {
    val out = mutableListOf<DomainFile>()
    out += folder.files.toList()
    folder.folders.forEach { out += collectDomainFilesForListing(it) }
    return out
}

private fun findAkibaScript(): String {
    try {
        val source = org.iotsplab.akiba.Main::class.java.protectionDomain.codeSource
        val loc = source.location.toURI()
        val jarFile = File(loc)
        if (jarFile.name.endsWith(".jar")) {
            val distRoot = jarFile.parentFile.parentFile
            val script = File(distRoot, "bin/akiba")
            if (script.isFile) return script.absolutePath
        }
    } catch (_: Exception) { }
    val cwd = System.getProperty("user.dir", ".")
    for (candidate in listOf(File(cwd, "bin/akiba"), File(cwd, "../bin/akiba"))) {
        if (candidate.isFile) return candidate.absolutePath
    }
    return "akiba"
}

private fun buildProjectTextExportOptions(req: ProjectTextExportRequest): ProjectTextExportOptions {
    require(req.format.lowercase() == "zip") { "format must be 'zip'" }
    val contents = req.contents.map { it.lowercase() }.toSet()
    val unknown = contents - TEXT_EXPORT_CONTENTS
    require(unknown.isEmpty()) { "Unknown contents: ${unknown.sorted()}; allowed: ${TEXT_EXPORT_CONTENTS.sorted()}" }
    require(req.decompileTimeoutSec in 1..600) { "decompileTimeoutSec must be in 1..600" }
    require(req.maxFunctions >= 0) { "maxFunctions must be >= 0 (0 means unlimited)" }
    require(req.maxFunctionSize > 0) { "maxFunctionSize must be > 0" }
    require(req.sortBy.lowercase() in TEXT_EXPORT_SORTS) { "sortBy must be one of ${TEXT_EXPORT_SORTS.sorted()}" }
    val filter = req.functionFilter?.takeIf { it.isNotBlank() }?.let {
        require(it.length <= 256) { "functionFilter is too long (max 256 chars)" }
        try {
            Regex(it)
        } catch (e: Exception) {
            throw IllegalArgumentException("functionFilter is not valid regex: ${e.message}")
        }
    }
    return ProjectTextExportOptions(
        contents = contents,
        includeComments = req.includeComments,
        includeEolComment = req.includeEolComment,
        includePlateComment = req.includePlateComment,
        includePreComment = req.includePreComment,
        includePostComment = req.includePostComment,
        includeRepeatableComment = req.includeRepeatableComment,
        includeDecompile = req.includeDecompile || "decompile" in contents,
        decompileTimeoutSec = req.decompileTimeoutSec,
        includeData = req.includeData,
        includeUndefined = req.includeUndefined,
        functionFilter = filter,
        addressFilter = req.addressFilter,
        maxFunctions = req.maxFunctions,
        maxFunctionSize = req.maxFunctionSize,
        sortBy = req.sortBy.lowercase(),
    )
}

private fun findProjectLockFiles(projectDirectory: Path, projectName: String): List<String> {
    val lockNames = mutableListOf<String>()
    val rootLock = projectDirectory.resolve("$projectName.lock")
    val rootLockBackup = projectDirectory.resolve("$projectName.lock~")
    if (Files.exists(rootLock)) lockNames += rootLock.fileName.toString()
    if (Files.exists(rootLockBackup)) lockNames += rootLockBackup.fileName.toString()
    val repDir = projectDirectory.resolve("$projectName.rep")
    if (repDir.isDirectory()) {
        try {
            Files.walk(repDir).use { walk ->
                walk.filter { it.isRegularFile() && it.fileName.toString().endsWith(GarFormat.DB_LOCK_EXT) }
                    .limit(8)
                    .forEach { lockNames += repDir.relativize(it).toString() }
            }
        } catch (_: Exception) {
            // Best-effort only: failing to scan lock files must not break the
            // status endpoint; openProject will still fail safely if locked.
        }
    }
    return lockNames
}

private fun projectTextExportStatus(
    projectName: String,
    projectDirectory: Path,
    daemonHost: String,
    daemonPort: Int,
    instance: String?,
): ProjectTextExportStatus {
    // A Ghidra project on disk is a PAIR: `<name>.gpr` (the project
    // marker / index file) and `<name>.rep/` (the actual content
    // directory).  Both must be present for `GhidraProject.openProject`
    // to succeed.  Checking only `.rep` (as the previous version did)
    // lets a stale / half-deleted project pass the status gate, and
    // `openOrCreateInteractiveProject` then falls through to
    // `GhidraProject.createProject(...)` which creates a BRAND-NEW
    // EMPTY project at the same path — producing an export zip that
    // contains only manifest/index/README with zero programs.
    val repDir = projectDirectory.resolve("$projectName.rep")
    val grpFile = projectDirectory.resolve("$projectName.gpr")
    if (!repDir.isDirectory() || !Files.exists(grpFile)) {
        return ProjectTextExportStatus(
            projectName = projectName,
            projectExists = false,
            state = "project_not_found",
            hints = if (repDir.isDirectory() && !Files.exists(grpFile)) {
                listOf(
                    "Project directory '$projectName.rep' exists but the marker file " +
                        "'$projectName.gpr' is missing. Ghidra cannot open a project without " +
                        "its .gpr file. Restore $projectName.gpr from backup or re-import the " +
                        "binary into a new project."
                )
            } else emptyList(),
        )
    }

    val reasons = mutableListOf<ProjectTextExportReason>()
    val hints = mutableListOf<String>()

    val lockFiles = findProjectLockFiles(projectDirectory, projectName)
    if (lockFiles.isNotEmpty()) {
        reasons += ProjectTextExportReason(
            code = "project_lock_file_present",
            activeProject = projectName,
        )
        hints += "Project lock files exist (${lockFiles.joinToString()}); an external Ghidra/Akiba process may still hold the project. Stop the owning task or wait until it releases the lock."
    }

    val activeProject = WorkspaceManager.activeProjectName
    if (activeProject != null) {
        reasons += ProjectTextExportReason(
            code = if (activeProject == projectName) "project_open_in_server" else "server_holds_other_project",
            activeProject = activeProject,
        )
        hints += "The project is open in the server JVM. If a task is still running, stop it via POST /workflow/stop/{workflowId}; otherwise retry after the owner releases the project."
    }

    val runningWorkflows = WorkflowManager.getAllWorkflowStatuses().filter { it.status == "running" }
    if (runningWorkflows.isNotEmpty()) {
        reasons += ProjectTextExportReason(
            code = "workflow_running",
            workflows = runningWorkflows.map { wf -> mapOf("id" to wf.id, "status" to wf.status) },
        )
        hints += "POST /workflow/stop/{workflowId} — terminate a running workflow."
    }

    var sessionsChecked = false
    if (instance != null) {
        try {
            withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val activeSessions = AgentDatabaseClient(dbClient)
                    .listSessions(limit = 500, parentSessionId = "ALL")
                    .filter { it.projectName == projectName }
                    .filter { (it.runtimeState ?: it.status).lowercase() !in FINISHED_SESSION_STATES }
                sessionsChecked = true
                if (activeSessions.isNotEmpty()) {
                    reasons += ProjectTextExportReason(
                        code = "agent_sessions_active",
                        sessions = activeSessions.map { s ->
                            mapOf(
                                "sessionId" to s.sessionId,
                                "status" to s.status,
                                "runtimeState" to s.runtimeState,
                                "agentName" to (s.moduleName ?: s.sessionName),
                            )
                        },
                    )
                    hints += "DELETE /agents/manual/session/{sessionId} — cancel a manual agent session if appropriate."
                }
            }
        } catch (e: Exception) {
            LogManager.getLogger("ProjectRoutes").warn(
                "[text-export-status] failed to inspect agent sessions for project '$projectName': ${e.message}"
            )
        }
    } else {
        hints += "Pass header X-Akiba-Instance to include active agent-session checks."
    }

    return ProjectTextExportStatus(
        projectName = projectName,
        projectExists = true,
        state = if (reasons.isEmpty()) "available" else "in_use",
        activeProject = activeProject,
        reasons = reasons,
        hints = hints.distinct(),
        agentSessionsChecked = sessionsChecked,
    )
}

private object ProjectRoutesConsumer

private fun collectDomainFiles(folder: DomainFolder): List<DomainFile> {
    val out = mutableListOf<DomainFile>()
    out += folder.files.toList()
    folder.folders.forEach { out += collectDomainFiles(it) }
    return out
}

private fun tryOpenProgram(domainFile: DomainFile, logger: Logger): Program? = try {
    if (domainFile.domainObjectClass != Program::class.java) return null
    // okToUpgrade=true: the export is READ-ONLY, so it is always safe
    // to auto-upgrade an older-format program into memory.  The
    // previous `okToUpgrade=false` caused every program created by a
    // different Ghidra build to throw VersionException, which was then
    // silently swallowed by the catch below — producing an empty zip
    // even when the project had plenty of programs on disk.
    WorkspaceManager.project.openProgram(domainFile.parent.pathname, domainFile.name, true)
} catch (e: Exception) {
    // Log the actual failure so the user can tell from the manifest /
    // server logs WHY a program was skipped, instead of just seeing
    // "Skipped non-program or unreadable domain file" with no reason.
    logger.warn(
        "[text-export] failed to open domain file '${domainFile.pathname}' " +
            "(class=${domainFile.domainObjectClass?.simpleName}): " +
            "${e.javaClass.simpleName}: ${e.message}"
    )
    null
}

private fun selectFunctions(program: Program, opts: ProjectTextExportOptions): List<Function> {
    var functions = program.functionManager.getFunctions(true).toList()
    opts.functionFilter?.let { rx -> functions = functions.filter { rx.containsMatchIn(it.name) } }
    opts.addressFilter?.let { af ->
        val start = program.addressFactory.getAddress(af.start)
            ?: throw IllegalArgumentException("Invalid addressFilter.start: ${af.start}")
        val end = program.addressFactory.getAddress(af.end)
            ?: throw IllegalArgumentException("Invalid addressFilter.end: ${af.end}")
        require(start <= end) { "addressFilter.start must be <= addressFilter.end" }
        val range = AddressSet(start, end)
        functions = functions.filter { range.contains(it.entryPoint) }
    }
    functions = when (opts.sortBy) {
        "name" -> functions.sortedBy { it.name }
        "size" -> functions.sortedByDescending { it.body.numAddresses }
        else -> functions.sortedBy { it.entryPoint.offset }
    }
    return if (opts.maxFunctions > 0) functions.take(opts.maxFunctions) else functions
}

private fun renderListing(program: Program, functions: List<Function>, opts: ProjectTextExportOptions): String {
    val listing = program.listing
    return buildString {
        appendLine("# Listing — ${program.name}")
        appendLine()
        for (fn in functions) {
            appendLine("## ${fn.name} @ ${hex(fn.entryPoint.offset)} (${fn.body.numAddresses} bytes)")
            appendLine()
            appendFunctionHeaderComments(this, listing, fn, opts)
            if (fn.body.numAddresses > opts.maxFunctionSize) {
                appendLine("_Skipped: function body exceeds maxFunctionSize=${opts.maxFunctionSize}_")
                appendLine()
                continue
            }
            appendLine("```asm")
            val it = listing.getInstructions(fn.body, true)
            while (it.hasNext()) {
                val insn = it.next()
                append(hex(insn.minAddress.offset)).append("  ").append(insn.toString())
                if (opts.includeComments && opts.includeEolComment) {
                    val c = insn.getComment(CommentType.EOL)
                    if (!c.isNullOrBlank()) append("  ; ").append(c.replace('\n', ' '))
                }
                appendLine()
            }
            appendLine("```")
            appendLine()
        }
    }
}

private fun appendFunctionHeaderComments(
    sb: StringBuilder,
    listing: Listing,
    fn: Function,
    opts: ProjectTextExportOptions,
) {
    if (!opts.includeComments) return
    val comments = listOfNotNull(
        if (opts.includePlateComment) "Plate" to listing.getComment(CommentType.PLATE, fn.entryPoint) else null,
        if (opts.includePreComment) "Pre" to listing.getComment(CommentType.PRE, fn.entryPoint) else null,
        if (opts.includePostComment) "Post" to listing.getComment(CommentType.POST, fn.entryPoint) else null,
        if (opts.includeRepeatableComment) "Repeatable" to listing.getComment(CommentType.REPEATABLE, fn.entryPoint) else null,
    ).filter { !it.second.isNullOrBlank() }
    for ((label, comment) in comments) {
        sb.appendLine("**$label comment:**")
        sb.appendLine()
        sb.appendLine("```")
        sb.appendLine(comment!!.trim())
        sb.appendLine("```")
        sb.appendLine()
    }
}

private fun renderComments(program: Program, functions: List<Function>, opts: ProjectTextExportOptions): String {
    val listing = program.listing
    return buildString {
        for (fn in functions) {
            val before = length
            appendLine("# ${fn.name} @ ${hex(fn.entryPoint.offset)}")
            appendFunctionHeaderComments(this, listing, fn, opts)
            if (opts.includeEolComment) {
                val it = listing.getInstructions(fn.body, true)
                while (it.hasNext()) {
                    val insn = it.next()
                    val c = insn.getComment(CommentType.EOL)
                    if (!c.isNullOrBlank()) {
                        appendLine("${hex(insn.minAddress.offset)}: ${c.replace('\n', ' ')}")
                    }
                }
            }
            if (length == before + "# ${fn.name} @ ${hex(fn.entryPoint.offset)}\n".length) {
                setLength(before)
            } else {
                appendLine()
            }
        }
    }
}

private fun renderData(program: Program, functions: List<Function>, opts: ProjectTextExportOptions): String {
    val listing = program.listing
    return buildString {
        for (fn in functions) {
            if (fn.body.numAddresses > opts.maxFunctionSize) continue
            val it = listing.getDefinedData(fn.body, true)
            var wroteHeader = false
            while (it.hasNext()) {
                val data = it.next()
                val type = data.dataType.name
                if (!opts.includeUndefined && type == "undefined") continue
                if (!wroteHeader) {
                    appendLine("# ${fn.name} @ ${hex(fn.entryPoint.offset)}")
                    wroteHeader = true
                }
                appendLine("${hex(data.minAddress.offset)}  $type")
            }
            if (wroteHeader) appendLine()
        }
    }
}

private fun renderDecompile(
    program: Program,
    functions: List<Function>,
    opts: ProjectTextExportOptions,
    warnings: MutableList<String>,
): String {
    val monitor = ConsoleTaskMonitor()
    val decompiler = DecompInterface()
    return try {
        decompiler.openProgram(program)
        buildString {
            for (fn in functions) {
                if (fn.body.numAddresses > opts.maxFunctionSize) continue
                val result = decompiler.decompileFunction(fn, opts.decompileTimeoutSec, monitor)
                if (result != null && result.decompileCompleted()) {
                    val c = result.getDecompiledFunction()?.getC()
                    if (!c.isNullOrBlank()) {
                        appendLine("/* ---- ${fn.name} @ ${hex(fn.entryPoint.offset)} ---- */")
                        appendLine(c.trim())
                        appendLine()
                    }
                } else {
                    warnings += "Decompile failed for ${program.name}/${fn.name}: ${result?.errorMessage ?: "unknown"}"
                }
            }
        }
    } finally {
        decompiler.dispose()
    }
}

private fun writeZipText(zip: ZipOutputStream, name: String, text: String) {
    zip.putNextEntry(ZipEntry(name))
    zip.write(text.toByteArray(Charsets.UTF_8))
    zip.closeEntry()
}

private fun writeZipJson(zip: ZipOutputStream, name: String, payload: Any) {
    writeZipText(zip, name, projectTextExportMapper.writeValueAsString(payload))
}

private fun safeZipSegment(value: String): String =
    value.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(64).ifBlank { "unnamed" }

private fun hex(offset: Long): String = "0x${offset.toString(16)}"
