package org.iotsplab.akiba.server.routes

import generic.io.JarWriter
import ghidra.framework.Application
import ghidra.util.task.TaskMonitor
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.managers.WorkspaceManager
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

data class GhidraProjectInfo(
    val name: String,
    val sessionCount: Int,
    val fileCount: Int
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
            val seenProjects = (sessionCounts.keys + diskProjects).toSet()

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
     * Export a Ghidra project as a `.gar` archive.
     *
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
