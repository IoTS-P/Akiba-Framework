package org.iotsplab.akiba.server.routes

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
import io.ktor.server.application.*
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.managers.ConfigManager
import org.iotsplab.akiba.managers.GarImporter
import org.iotsplab.akiba.managers.WorkspaceManager
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

data class GhidraProjectInfo(
    val name: String,
    val sessionCount: Int,
    val fileCount: Int
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
fun Route.projectRoutes(daemonHost: String, daemonPort: Int) {

    val logger: Logger = LogManager.getLogger("ProjectRoutes")

    get("/projects") {
        try {
            val projectDirectory = call.currentUserGhidraProjectsRoot()
            Files.createDirectories(projectDirectory)

            // Best-effort session counts. The Projects page works without a daemon —
            // any DB error is logged and surfaced as zeros rather than breaking the
            // page. This keeps the page usable when no instance has been selected.
            val sessionCounts = mutableMapOf<String, Int>()
            val instance = call.instanceHeader()
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
                    fileCount = fileCount
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
    delete("/projects/{name}") {
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
     * Batch-export agent sessions of a project as a ZIP of Markdown
     * documents. Body: `{ "sessionIds": ["<uuid>", ...] }`. Each entry
     * is the full agent-TREE export (root session + sub-agents) for one
     * selected session. Sessions whose project does not match the path
     * parameter are skipped (ownership check), and sessions that fail
     * mid-export produce a small `.error.txt` entry instead of aborting
     * the whole archive.
     */
    post("/projects/{name}/export_sessions") {
        val projectName = call.parameters["name"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing project name"))
        if (!isValidProjectName(projectName)) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid project name: $projectName"))
        }
        val instance = call.requireInstanceHeader() ?: return@post

        val body = try {
            val text = call.receiveText()
            if (text.isBlank()) null
            else projectTextExportMapper.readValue(text, Map::class.java)
        } catch (e: Exception) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: expected {\"sessionIds\": [...]}"))
        }
        val sessionIds = (body?.get("sessionIds") as? List<*>)?.filterIsInstance<String>().orEmpty()
            .map { it.trim() }
            .filter { it.matches(Regex("[0-9a-fA-F-]{36}")) }
            .distinct()
        if (sessionIds.isEmpty()) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No valid session ids supplied"))
        }
        if (sessionIds.size > 200) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Too many sessions: ${sessionIds.size} (max 200)"))
        }

        try {
            val baos = ByteArrayOutputStream()
            var exported = 0
            withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                // Ownership set from session/list (which reliably carries
                // projectName): never export a session belonging to a
                // different project through this endpoint.
                val ownedIds = agentDbClient.listSessions(limit = 1000)
                    .filter { it.projectName == projectName }
                    .map { it.sessionId }
                    .toSet()
                ZipOutputStream(baos).use { zos ->
                    val usedNames = mutableSetOf<String>()
                    for (id in sessionIds) {
                        if (id !in ownedIds) continue
                        val info = try {
                            agentDbClient.getSession(id)
                        } catch (e: Exception) {
                            logger.warn("[export_sessions] session $id not readable: ${e.message}")
                            null
                        } ?: continue

                        val base = safeZipSegment(info.sessionName ?: "session").take(40)
                        var entryName = "${base}_${id.take(8)}.md"
                        var n = 2
                        while (!usedNames.add(entryName)) entryName = "${base}_${id.take(8)}_$n.md".also { n++ }

                        val md = try {
                            renderTreeExport(agentDbClient, info)
                        } catch (e: Exception) {
                            logger.warn("[export_sessions] tree export failed for $id: ${e.message}")
                            entryName = entryName.removeSuffix(".md") + ".error.txt"
                            "Failed to export session $id: ${e.message}\n"
                        }
                        zos.putNextEntry(ZipEntry(entryName))
                        zos.write(md.toByteArray(Charsets.UTF_8))
                        zos.closeEntry()
                        exported++
                    }
                }
            }
            if (exported == 0) {
                return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "None of the supplied sessions belong to project '$projectName'"))
            }
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName, "$projectName-sessions.zip"
                ).toString()
            )
            call.respondBytes(baos.toByteArray(), ContentType.Application.Zip)
        } catch (e: Exception) {
            logger.error("[export_sessions] failed for '$projectName': ${e.message}", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to export sessions: ${e.message}"))
        }
    }
     /*
     * The implementation mirrors Ghidra's own `ArchiveTask.writeProject`
     * (see `ghidra/app/plugin/core/archive/ArchiveTask.java` in the upstream
     * repo). The resulting layout is:
     *
     *   <projectName>.gpr      # project marker file (must be the first entry)
     *   JAR_FORMAT             # magic entry consumed by `isJarFormat()`
     *   <subdir-of-.rep>/...   # every sub-directory under the project's .rep/
     *
     * Earlier versions of this route called `GhidraProject.openProject(...)` —
     * which is what `ArchiveTask` does upstream too — but that path requires
     * the Ghidra `Application` to be initialized. In server mode the
     * application is only initialized lazily inside `initWorkspace()`, so
     * the export HTTP handler blew up with `AssertException` (500) when
     * invoked from the Projects UI without a prior agent run.
     *
     * To make the export reliable and headless-friendly we therefore operate
     * **directly on the filesystem**: we never call `GhidraProject.openProject`.
     * This matches what `ArchiveTask.writeProject` does once it has a
     * `ProjectLocator`; we just compute the `ProjectLocator` ourselves from
     * the per-user project directory. As a bonus, projects with malformed
     * or empty `.gpr` marker files (which previously made `openProject`
     * throw `NotFoundException`) can now still be exported as long as the
     * underlying `.rep/` directory tree is intact.
     */
    get("/projects/{name}/export") {
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

    /**
     * Probe whether the text/listing export can run right now.
     *
     * This endpoint deliberately does not wait. Some akiba tasks run for days
     * or weeks, so if a project is open / in-use the caller should stop the
     * owning task via the existing workflow/agent endpoints, or retry later.
     */
    get("/projects/{name}/export_text/status") {
        val projectName = call.parameters["name"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing project name"))
        if (!isValidProjectName(projectName)) {
            return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid project name: $projectName"))
        }
        val status = projectTextExportStatus(
            projectName = projectName,
            projectDirectory = call.currentUserGhidraProjectsRoot(),
            daemonHost = daemonHost,
            daemonPort = daemonPort,
            instance = call.instanceHeader(),
        )
        call.respond(status)
    }

    /**
     * Export assembly/decompile/comments/functions into a ZIP archive.
     *
     * Unlike `/projects/{name}/export` (GAR, pure filesystem), this route must
     * open the Ghidra project to materialize Listing/Function/Decompiler APIs.
     * It therefore refuses if the project is already open, and always closes
     * the project in `finally` after it has opened it itself, so the long-lived
     * server JVM doesn't keep `.ulock` and block child-process workflows.
     */
    post("/projects/{name}/export_text") {
        val projectName = call.parameters["name"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing project name"))
        if (!isValidProjectName(projectName)) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid project name: $projectName"))
        }

        val req = try {
            val body = call.receiveText()
            if (body.isBlank()) ProjectTextExportRequest()
            else projectTextExportMapper.readValue(body, ProjectTextExportRequest::class.java)
        } catch (e: Exception) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid export_text request body: ${e.message ?: e.javaClass.simpleName}")
            )
        }
        try {
            buildProjectTextExportOptions(req) // validate early
        } catch (e: IllegalArgumentException) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid export option")))
        }

        val projectDirectory = call.currentUserGhidraProjectsRoot()
        val status = projectTextExportStatus(
            projectName = projectName,
            projectDirectory = projectDirectory,
            daemonHost = daemonHost,
            daemonPort = daemonPort,
            instance = call.instanceHeader(),
        )
        if (status.state != "available") {
            val http = if (status.state == "project_not_found") HttpStatusCode.NotFound else HttpStatusCode.Conflict
            return@post call.respond(http, status)
        }
        if (!projectTextExportLock.tryLock()) {
            return@post call.respond(
                HttpStatusCode.Conflict,
                status.copy(
                    state = "in_use",
                    reasons = listOf(ProjectTextExportReason(code = "another_export_text_in_progress")) + status.reasons
                )
            )
        }

        try {
            // Spawn a child process to do the export.  The server JVM
            // cannot reliably open projects created/modified by child
            // processes (stale ProjectData index), so we delegate the
            // entire export to a fresh `akiba --export` subprocess
            // which opens the project the same way normal module
            // execution does.
            //
            // Export options are passed via the config file's
            // "textExport" section, which AkibaUtils parses as its
            // module config (via @WithConfigClass).  This reuses the
            // standard module-config pipeline — no environment
            // variables needed.
            val scriptPath = findAkibaScript()
            val tempDir = Files.createTempDirectory("akiba_text_export_")
            val configPath = tempDir.resolve("config.json")
            val exportDir = tempDir.resolve("AkibaUtils").resolve("-1").resolve("export")

            // Build the config.  sqlSource.constraint = "server"
            // triggers invokeServerMode() in ProgramManager, which
            // creates AkibaUtils with the TextExportConfig from the
            // "textExport" key and calls startProcess().  The subprocess
            // runs as a normal workflow (no --export flag); after it
            // finishes, this route collects the output files from
            // exportDir and zips them.
            val textExportSection = linkedMapOf<String, Any>(
                "contents" to req.contents,
                "includeComments" to req.includeComments,
                "includeEolComment" to req.includeEolComment,
                "includePlateComment" to req.includePlateComment,
                "includePreComment" to req.includePreComment,
                "includePostComment" to req.includePostComment,
                "includeRepeatableComment" to req.includeRepeatableComment,
                "includeDecompile" to req.includeDecompile,
                "decompileTimeoutSec" to req.decompileTimeoutSec,
                "includeData" to req.includeData,
                "includeUndefined" to req.includeUndefined,
                "maxFunctions" to req.maxFunctions,
                "maxFunctionSize" to req.maxFunctionSize,
                "sortBy" to req.sortBy,
            )
            req.functionFilter?.let { textExportSection["functionFilter"] = it }
            req.addressFilter?.let {
                textExportSection["addressFilter"] = mapOf(
                    "start" to it.start,
                    "end" to it.end,
                )
            }

            val config = linkedMapOf<String, Any>(
                "main" to linkedMapOf<String, Any>(
                    "username" to call.currentUsernameOrDefault(),
                    "password" to DAEMON_PASSWORD,
                    "usingInstance" to (call.instanceHeader() ?: "akiba-instance"),
                    "general" to linkedMapOf<String, Any>(
                        "workspaceRoot" to tempDir.toString()
                    ),
                    "withGhidraProject" to linkedMapOf<String, Any>(
                        "projectRoot" to projectDirectory.toString(),
                        "name" to projectName,
                        "mode" to "base",
                        "continueLog" to "text-export-${projectName}",
                        "saveProject" to false,
                        "noCreateProgram" to true
                    ),
                    "sqlSource" to linkedMapOf<String, Any>(
                        "constraint" to "server",
                        "serverIP" to daemonHost,
                        "serverPort" to daemonPort
                    ),
                    "tasks" to listOf(
                        linkedMapOf<String, Any>(
                            "mainClassName" to "org.iotsplab.akiba.module.AkibaUtils",
                            "configKey" to "@@/textExport"
                        )
                    ),
                ),
                "textExport" to textExportSection,
            )
            Files.writeString(configPath, projectTextExportMapper.writeValueAsString(config))

            val env = mutableMapOf<String, String>()
            env["AKIBA_LLM_API_KEY"] = System.getenv("AKIBA_LLM_API_KEY") ?: ""

            logger.info("[text-export] spawning subprocess: $scriptPath -c ${configPath.fileName}@/main")

            val pb = ProcessBuilder(
                scriptPath,
                "-c", configPath.toAbsolutePath().toString() + "@/main"
            )
            pb.directory(java.io.File(System.getProperty("user.dir", ".")))
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.redirectErrorStream(true)
            val process = pb.start()

            // Consume stdout to avoid buffer deadlock and log for debugging.
            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    logger.info("[text-export subprocess] $line")
                }
            }

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                logger.error("[text-export] subprocess failed with exit code $exitCode")
                val (http, body) = errorPayload(IllegalStateException("Export subprocess failed (exit code $exitCode)"))
                call.respond(http, body)
                return@post
            }

            // Collect export files produced by the subprocess and zip them.
            if (!Files.exists(exportDir) ||
                Files.list(exportDir).use { !it.findAny().isPresent }) {
                logger.error("[text-export] subprocess exited 0 but no output in $exportDir")
                val (http, body) = errorPayload(IllegalStateException(
                    "Export subprocess succeeded but produced no output. " +
                    "Check server logs for [text-export subprocess] lines."))
                call.respond(http, body)
                return@post
            }

            val zipBytes = ByteArrayOutputStream().use { baos ->
                ZipOutputStream(baos).use { zip ->
                    collectExportFiles(exportDir, exportDir, zip)
                }
                baos.toByteArray()
            }
            logger.info("[text-export] subprocess complete, zip size=${zipBytes.size} bytes")

            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName,
                    "$projectName-export_text.zip"
                ).toString()
            )
            call.respondBytes(zipBytes, ContentType.Application.Zip)
        } catch (e: Exception) {
            logger.error("[text-export] failed for project '$projectName': ${e.message}", e)
            val (http, body) = errorPayload(e)
            call.respond(http, body)
        } finally {
            projectTextExportLock.unlock()
        }
    }

    /**
     * Import a `.gar` (Ghidra Archive) as a standalone project.
     *
     * Multipart form: `file` = the .gar archive, `projectName` = optional
     * name (defaults to the archive's base name). The archive is extracted
     * under the caller's project directory, every program is registered in
     * the `binaries` table (hash-deduped), and programs are renamed to
     * `<id>-<originalName>` so existing `getProgram(id)` lookups keep
     * working.
     *
     * Requires the `X-Akiba-Instance` header so a database session can be
     * opened for binary registration. Auto-analysis is skipped — the `.gar`
     * already carries the analysis state from the source machine.
     */
    post("/projects/import-gar") {
        val instance = call.instanceHeader()
            ?: return@post call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Missing X-Akiba-Instance header")
            )
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
            val username = call.currentSafeUsername()
            val binaryRoot = Path.of(
                System.getProperty("user.home"), ".akiba", "binaries", username, instance, "original"
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

        // 1. Write the `<projectName>.gpr` marker file as the FIRST entry.
        //    ArchiveTask unconditionally writes this entry — even if the
        //    on-disk `.gpr` is empty (0 bytes). The reason is strict:
        //    `ArchivePlugin.isJarFormat` validates the archive by reading
        //    the **second** entry and comparing its name to
        //    `ArchivePlugin.JAR_VERSION_TAG = "JAR_FORMAT"`. Therefore:
        //
        //      - Entry 0 must be `<name>.gpr` (its content is ignored —
        //        `RestoreTask.processFile` actively filters `.gpr` files
        //        and `createProjectMarkerFile()` creates a fresh empty
        //        one from scratch during restore).
        //      - Entry 1 must be `JAR_FORMAT` (see step 2 below).
        //
        //    Skipping the `.gpr` entry when it is empty (a common test
        //    scaffolding case) shifts `JAR_FORMAT` to entry 0, so the
        //    validator reads a `.rep/...` filename into `format` and
        //    Ghidra rejects the file with "File Format Error" /
        //    "Can't read the file".
        //
        //    If the marker file does not exist at all on disk (e.g. the
        //    project tree is partially initialized), we still must
        //    emit a 0-byte placeholder so the entry-count arithmetic
        //    above stays correct; otherwise the archive is structurally
        //    invalid even though everything else is fine.
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

private fun writeProjectTextExportZip(
    zip: ZipOutputStream,
    projectName: String,
    opts: ProjectTextExportOptions,
    logger: Logger,
) {
    val exportedAt = Instant.now().toString()
    val warnings = mutableListOf<String>()

    // Force-refresh the project data index from disk.  Programs may
    // have been added or modified by child processes (module
    // subprocesses) after the server JVM last had the project open.
    // Without a refresh, the in-memory DomainFolder index can be stale
    // and report 0 domain files even though the .rep/ directory on
    // disk contains program files — which is why .gar export (which
    // reads .rep/ directly) works while text export produces an empty
    // zip.  The boolean parameter requests a deep refresh (all
    // subfolders recursively).
    try {
        WorkspaceManager.project.projectData.refresh(true)
    } catch (e: Exception) {
        logger.warn("[text-export] projectData.refresh() failed: ${e.message} (continuing with cached index)")
    }

    val programs = collectDomainFiles(WorkspaceManager.project.projectData.rootFolder)

    // Diagnostic: log how many domain files were found so the user can
    // tell from the server logs whether the empty-export problem is
    // "no files in project" (0 domain files) vs "files exist but all
    // failed to open" (>0 domain files, 0 programs rendered).  The
    // manifest.json also carries this count via programMeta.size, but
    // the server log is what the user checks first when debugging.
    logger.info(
        "[text-export] project='$projectName' rootFolder='{}' domainFiles=${programs.size}"
    )
    if (programs.isEmpty()) {
        logger.warn(
            "[text-export] project '$projectName' has 0 domain files in its root folder. " +
                "This means the opened Ghidra project is empty — either it was just created " +
                "(no programs imported yet) or the .gpr file was missing and " +
                "openOrCreateInteractiveProject created a new empty project. " +
                "Check that the project at the expected directory actually contains programs."
        )
        warnings += "Project root folder contains 0 domain files — the opened project appears to be empty."
    }

    val programMeta = mutableListOf<Map<String, Any?>>()

    for (domainFile in programs) {
        val program = tryOpenProgram(domainFile, logger) ?: run {
            warnings += "Skipped non-program or unreadable domain file: ${domainFile.pathname}"
            continue
        }
        try {
            val safeProgramName = safeZipSegment(program.name)
            val prefix = "programs/$safeProgramName"
            val functions = selectFunctions(program, opts)
            programMeta += mapOf(
                "name" to program.name,
                "path" to domainFile.pathname,
                "language" to program.languageID.toString(),
                "compiler" to program.compilerSpec.compilerSpecID.toString(),
                "imageBase" to hex(program.imageBase.offset),
                "functions" to functions.size,
            )
            writeZipJson(zip, "$prefix/meta.json", mapOf(
                "name" to program.name,
                "path" to domainFile.pathname,
                "language" to program.languageID.toString(),
                "compiler" to program.compilerSpec.compilerSpecID.toString(),
                "imageBase" to hex(program.imageBase.offset),
            ))
            if ("functions" in opts.contents) {
                writeZipJson(zip, "$prefix/functions.json", mapOf(
                    "program" to program.name,
                    "functions" to functions.map { fn ->
                        mapOf(
                            "name" to fn.name,
                            "entryPoint" to hex(fn.entryPoint.offset),
                            "size" to fn.body.numAddresses,
                            "skippedBySize" to (fn.body.numAddresses > opts.maxFunctionSize),
                        )
                    }
                ))
            }
            if ("listing" in opts.contents) {
                writeZipText(zip, "$prefix/listing.md", renderListing(program, functions, opts))
            }
            if ("comments" in opts.contents && opts.includeComments) {
                writeZipText(zip, "$prefix/comments.txt", renderComments(program, functions, opts))
            }
            if ("data" in opts.contents && opts.includeData) {
                writeZipText(zip, "$prefix/data.txt", renderData(program, functions, opts))
            }
            if (opts.includeDecompile && "decompile" in opts.contents) {
                writeZipText(zip, "$prefix/decompiled.c", renderDecompile(program, functions, opts, warnings))
            }
        } catch (e: Exception) {
            logger.warn("[text-export] failed to render '${domainFile.pathname}': ${e.message}", e)
            warnings += "Render failed for ${domainFile.pathname}: ${e.message}"
        } finally {
            program.release(ProjectRoutesConsumer)
        }
    }

    writeZipText(zip, "index.md", buildString {
        appendLine("# $projectName — Akiba text export")
        appendLine()
        appendLine("- Exported: $exportedAt")
        appendLine("- Programs: ${programMeta.size}")
        appendLine("- Contents: ${opts.contents.sorted()}")
        appendLine()
        appendLine("## Programs")
        appendLine()
        programMeta.forEach { p ->
            appendLine("- `${p["path"]}` — `${p["language"]}` (${p["functions"]} functions)")
        }
    })
    writeZipJson(zip, "manifest.json", mapOf(
        "project" to projectName,
        "exportedAt" to exportedAt,
        "releasedAfterExport" to true,
        "contents" to opts.contents.sorted(),
        "programs" to programMeta,
        "warnings" to warnings,
    ))
    writeZipText(zip, "README.md", "Generated by Akiba Server export_text. Files are organized under programs/<program>/.")
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
