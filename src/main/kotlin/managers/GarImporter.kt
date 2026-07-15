package org.iotsplab.akiba.managers

import ghidra.base.project.GhidraProject
import ghidra.framework.model.DomainFile
import ghidra.framework.model.DomainFolder
import ghidra.program.database.mem.FileBytes
import ghidra.program.model.listing.Program
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.data.database.DatabaseClient
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.jar.JarFile
import kotlin.io.path.absolutePathString
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

// ============================================================
//  GarImporter — import a Ghidra Archive (.gar) as a standalone project
// ============================================================

/**
 * Imports a `.gar` (Ghidra Archive) file as a **standalone** project under
 * the caller's Ghidra project directory, and registers every program it
 * contains in the `binaries` table.
 *
 * The `.gar` layout (mirrored by [ProjectRoutes.archiveProject]) is:
 *
 * ```
 *   entry 0 : <origName>.gpr   # project marker (content ignored on restore)
 *   entry 1 : JAR_FORMAT        # magic entry, skipped
 *   entry 2+: <subdir>/...      # contents of the project's .rep/ directory
 * ```
 *
 * On restore we extract `<origName>.gpr` as `<newName>.gpr` and every other
 * non-magic, non-lock entry under `<newName>.rep/`, then open the project
 * with [GhidraProject.openProject].
 *
 * For each program found in the restored project:
 *
 *  1. Open it and read the original imported bytes via
 *     `Program.getMemory().getAllFileBytes()` (the same path used by Ghidra's
 *     own `ExportProgramScript` for "Original File" export). The MD5 of those
 *     bytes is the dedup key.
 *  2. If the MD5 already exists in `binaries`, look up the existing id via
 *     the `/get/id/sql` route (`WHERE u.checksum = '<md5>'`). No file import
 *     happens — the binary file is already on disk under the existing id.
 *  3. If the MD5 is new, export the original bytes to a temp file, call
 *     [DatabaseClient.insertBinary] with metadata extracted from the program
 *     (language / format / compiler), and copy the temp file to
 *     `<binaryRoot>/<id>.bin`.
 *  4. Rename the program's domain file to `<id>-<originalFileName>` so the
 *     existing `AkibaModule.getProgram(id)` lookup (`"$id-${file.name}"`)
 *     keeps working.
 *
 * Auto-analysis is intentionally skipped: the `.gar` already carries the
 * analysis state produced on the source machine, and re-running it would
 * discard that work.
 *
 * This class is self-contained: it opens its own [GhidraProject] handle
 * (via [WorkspaceManager.initializeGhidra], which is idempotent and does
 * not touch the active project) and does not mutate
 * [WorkspaceManager.project]. It is therefore safe to call from the server
 * JVM while another project is active, and equally usable from a CLI entry
 * point.
 *
 * @param garFile        Path to the uploaded `.gar` archive.
 * @param newProjectName Sanitized name for the restored project. Must match
 *                       `^[A-Za-z0-9._-]{1,64}$`.
 * @param projectRoot    Directory that holds the user's `.gpr` / `.rep`
 *                       pairs (typically `ghidra_projects/<username>/`).
 * @param dbClient       Connected [DatabaseClient] for the target instance.
 * @param binaryRoot     The `original/` directory under the instance's
 *                       binaries root — newly-imported binaries are copied
 *                       here as `<id>.bin`.
 */
object GarImporter {

    /** Per-program outcome of an import. */
    data class ProgramImportResult(
        val domainFileName: String,
        val renamedTo: String,
        val binaryId: Long,
        val checksum: String,
        /** `true` when a new binaries row was inserted; `false` when an existing id was reused. */
        val newlyImported: Boolean,
        /** Non-null when the program could not be processed (e.g. no FileBytes). */
        val error: String? = null,
    )

    /** Aggregate result. */
    data class ImportResult(
        val projectName: String,
        val projectPath: Path,
        val programs: List<ProgramImportResult>,
    )

    private val PROJECT_NAME_REGEX = Regex("^[A-Za-z0-9._-]{1,64}$")
    private const val JAR_FORMAT_TAG = "JAR_FORMAT"
    private const val LOCK_EXT = ".ulock"

    /**
     * Restore [garFile] as a project named [newProjectName] under
     * [projectRoot] and register every program in the database.
     *
     * @throws IllegalArgumentException on invalid project name or missing
     *                                  archive / output collision.
     */
    fun importGar(
        garFile: Path,
        newProjectName: String,
        projectRoot: Path,
        dbClient: DatabaseClient,
        binaryRoot: Path,
        logger: Logger,
    ): ImportResult {

        require(garFile.isRegularFile()) { "gar file not found or not a regular file: $garFile" }
        require(PROJECT_NAME_REGEX.matches(newProjectName)) {
            "Invalid project name '$newProjectName': must match ${PROJECT_NAME_REGEX.pattern}"
        }

        val gprTarget = projectRoot.resolve("$newProjectName.gpr")
        val repTarget = projectRoot.resolve("$newProjectName.rep")
        require(!Files.exists(gprTarget) && !Files.exists(repTarget)) {
            "A project named '$newProjectName' already exists under $projectRoot"
        }
        Files.createDirectories(projectRoot)
        Files.createDirectories(repTarget)

        logger.info("[gar-import] extracting '{}' → project='{}' root='{}'", garFile, newProjectName, projectRoot)
        extractGar(garFile, gprTarget, repTarget, logger)

        // Idempotent Ghidra Application init — does NOT open or disturb the
        // active project. Safe to call from the server JVM.
        if (!WorkspaceManager.initializeGhidra()) {
            throw IllegalStateException("Failed to initialize Ghidra Application for gar import")
        }

        val ghidraProject: GhidraProject = GhidraProject.openProject(
            projectRoot.absolutePathString(), newProjectName
        )
        val results = mutableListOf<ProgramImportResult>()
        try {
            val domainFiles = collectDomainFiles(ghidraProject.getProject().getProjectData().getRootFolder())
                .filter { it.domainObjectClass == Program::class.java }

            logger.info("[gar-import] project '{}' contains {} program domain file(s)", newProjectName, domainFiles.size)

            for (df in domainFiles) {
                val r = processDomainFile(df, ghidraProject, dbClient, binaryRoot, logger)
                results += r
            }
        } finally {
            try { ghidraProject.close() } catch (_: Exception) {}
        }

        logger.info(
            "[gar-import] done project='{}' programs={} newlyImported={}",
            newProjectName, results.size, results.count { it.newlyImported }
        )
        return ImportResult(
            projectName = newProjectName,
            projectPath = gprTarget,
            programs = results,
        )
    }

    // ---- .gar extraction ------------------------------------------------

    /**
     * Extract a `.gar` archive into the target `.gpr` marker + `.rep/`
     * directory. Entry 0 (`<origName>.gpr`) is written as [gprTarget];
     * the `JAR_FORMAT` magic entry is skipped; every other entry lands
     * under [repTarget] preserving its archive-relative path. `.ulock`
     * entries are dropped (they are meaningless outside an open project).
     *
     * Path-traversal is guarded: every resolved target must stay inside
     * [repTarget] (or be [gprTarget] itself).
     */
    private fun extractGar(
        garFile: Path,
        gprTarget: Path,
        repTarget: Path,
        logger: Logger,
    ) {
        JarFile(garFile.toFile()).use { jar ->
            val entries = jar.entries()
            var markerWritten = false
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (name == JAR_FORMAT_TAG) continue
                if (name.endsWith(LOCK_EXT)) continue

                // The first non-directory entry is always `<origName>.gpr`.
                // ArchiveTask emits it unconditionally (even when 0 bytes),
                // and RestoreTask re-creates a fresh marker from scratch
                // during restore, so we only need the file to exist.
                if (!markerWritten && name.endsWith(".gpr")) {
                    jar.getInputStream(entry).use { input ->
                        Files.copy(input, gprTarget, StandardCopyOption.REPLACE_EXISTING)
                    }
                    markerWritten = true
                    logger.debug("[gar-import] wrote marker '{}'", gprTarget)
                    continue
                }

                // Everything else is content that belongs under .rep/.
                val target = repTarget.resolve(name).normalize()
                require(target.startsWith(repTarget)) {
                    "Refusing .gar entry outside .rep/ target: $name"
                }
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    jar.getInputStream(entry).use { input ->
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
            if (!markerWritten) {
                // A .gar without a .gpr entry is structurally invalid —
                // Ghidra's RestoreTask refuses it. Emit an empty marker so
                // the project is at least openable for diagnostics.
                Files.createFile(gprTarget)
                logger.warn("[gar-import] no .gpr entry found in archive; created empty marker '{}'", gprTarget)
            }
        }
    }

    // ---- per-program processing ----------------------------------------

    /**
     * Open one domain file, compute its original-bytes MD5, register (or
     * reuse) a binary id, and rename the domain file to `<id>-<origName>`.
     */
    private fun processDomainFile(
        df: DomainFile,
        ghidraProject: GhidraProject,
        dbClient: DatabaseClient,
        binaryRoot: Path,
        logger: Logger,
    ): ProgramImportResult {
        val origName = df.name
        val folderPath = df.parent?.pathname ?: "/"
        var program: Program? = null
        try {
            program = ghidraProject.openProgram(folderPath, origName, true)
                ?: return ProgramImportResult(
                    domainFileName = origName, renamedTo = origName,
                    binaryId = -1, checksum = "", newlyImported = false,
                    error = "openProgram returned null",
                )

            // Extract original bytes + compute MD5 in one pass to a temp file.
            val fileBytes = program.memory.allFileBytes
            if (fileBytes.isEmpty()) {
                logger.warn("[gar-import] program '{}' has no FileBytes (imported before Ghidra saved originals, or not a direct import); skipping binary registration", origName)
                return ProgramImportResult(
                    domainFileName = origName, renamedTo = origName,
                    binaryId = -1, checksum = "", newlyImported = false,
                    error = "no FileBytes available; cannot compute original-file hash",
                )
            }
            val fb: FileBytes = fileBytes[0]
            val tempFile = Files.createTempFile("gar_prog_", ".bin")
            val checksum = try {
                dumpAndHash(fb, tempFile)
            } catch (e: Exception) {
                logger.warn("[gar-import] failed to export original bytes for '{}': {}", origName, e.message)
                return ProgramImportResult(
                    domainFileName = origName, renamedTo = origName,
                    binaryId = -1, checksum = "", newlyImported = false,
                    error = "original-bytes export failed: ${e.message}",
                )
            } finally {
                // tempFile is deleted below for the reuse path; for the
                // import path it is copied then deleted.
            }

            val (binaryId, newlyImported) = resolveBinaryId(
                checksum, program, tempFile, dbClient, binaryRoot, logger, origName,
            )

            // Close the program BEFORE renaming the domain file — Ghidra
            // refuses setName while the immutable consumer is held.
            program.release(RELEASE_CONSUMER)
            program = null

            val newName = "$binaryId-$origName"
            if (origName != newName) {
                try {
                    // setName returns a new DomainFile; the original `df`
                    // becomes invalid (DomainFile is immutable). We don't
                    // need the returned handle here. setName throws
                    // InvalidNameException if the new name contains illegal
                    // characters or IOException on access errors — log and
                    // keep the original name on failure.
                    df.setName(newName)
                } catch (e: Exception) {
                    logger.warn(
                        "[gar-import] setName('{}') failed for '{}' (binaryId={}): {}",
                        newName, origName, binaryId, e.message
                    )
                }
            }
            logger.info(
                "[gar-import] program '{}' → id={} checksum={} newlyImported={} renamedTo='{}'",
                origName, binaryId, checksum, newlyImported, newName
            )
            return ProgramImportResult(
                domainFileName = origName,
                renamedTo = newName,
                binaryId = binaryId,
                checksum = checksum,
                newlyImported = newlyImported,
            )
        } catch (e: Exception) {
            logger.warn("[gar-import] failed to process program '{}': {}", origName, e.message, e)
            return ProgramImportResult(
                domainFileName = origName, renamedTo = origName,
                binaryId = -1, checksum = "", newlyImported = false,
                error = "${e.javaClass.simpleName}: ${e.message}",
            )
        } finally {
            program?.release(RELEASE_CONSUMER)
        }
    }

    /** Trivial consumer object used for [Program.release]. */
    private object RELEASE_CONSUMER

    /**
     * Write the original imported bytes of [fb] to [out] and return their
     * MD5 hex string. Uses [FileBytes.getOriginalBytes] in 64 KiB chunks
     * for throughput; falls back to per-byte reads if the batch API is
     * unavailable on this Ghidra build.
     */
    private fun dumpAndHash(fb: FileBytes, out: Path): String {
        val digest = MessageDigest.getInstance("MD5")
        val size = fb.size
        val buf = ByteArray(64 * 1024)
        FileOutputStream(out.toFile()).use { fos ->
            var offset = 0L
            while (offset < size) {
                val want = minOf(buf.size.toLong(), size - offset).toInt()
                val read = try {
                    fb.getOriginalBytes(offset, buf, 0, want)
                } catch (_: NoSuchMethodError) {
                    // Extremely old Ghidra without the batch accessor —
                    // degrade to per-byte. Slow but correct.
                    for (i in 0 until want) buf[i] = fb.getOriginalByte(offset + i)
                    want
                }
                if (read <= 0) break
                fos.write(buf, 0, read)
                digest.update(buf, 0, read)
                offset += read
            }
        }
        return digest.digest().toHexString()
    }

    /**
     * Return `(binaryId, newlyImported)` for [checksum]. When the checksum
     * is new, insert a `binaries` row (metadata from [program]) and copy
     * the original-bytes temp file to `<binaryRoot>/<id>.bin`. When it
     * already exists, look the id up via the `/get/id/sql` route.
     *
     * The `/get/id/sql` route is globally enable/disable on the daemon,
     * so the lookup is serialised on this object to avoid racing with a
     * concurrent caller that toggles the route state.
     */
    @Synchronized
    private fun resolveBinaryId(
        checksum: String,
        program: Program,
        tempFile: Path,
        dbClient: DatabaseClient,
        binaryRoot: Path,
        logger: Logger,
        origName: String,
    ): Pair<Long, Boolean> {
        // MD5 hex strings are [0-9a-f]+ — safe to interpolate into the
        // SQL fragment consumed by /get/id/sql without parameter binding.
        // We still validate the shape defensively.
        require(checksum.matches(Regex("^[0-9a-fA-F]{32}$"))) { "bad md5 shape: $checksum" }

        if (dbClient.checkMD5Duplicate(checksum)) {
            val existingId = findIdByChecksum(dbClient, checksum, logger)
            if (existingId != null) {
                try { Files.deleteIfExists(tempFile) } catch (_: Exception) {}
                return existingId to false
            }
            // Duplicate flag was true but we could not resolve an id —
            // fall through and insert a new row so the program is at
            // least addressable. This is a rare race / cache drift.
            logger.warn("[gar-import] checkMD5Duplicate=true but no id found for checksum={}; inserting new row", checksum)
        }

        val size = Files.size(tempFile)
        val id = dbClient.insertBinary(
            DatabaseClient.InsertData(
                originalPath = origName,
                checksum = checksum,
                size = size,
                processedSize = -1,
                arch = program.languageID.idAsString,
                format = program.executableFormat ?: "n/a",
                compilerSpec = program.compiler ?: "n/a",
                sourceModule = "gar_import",
            )
        )
        Files.createDirectories(binaryRoot)
        val dest = binaryRoot.resolve("$id.bin")
        try {
            Files.move(tempFile, dest, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            // move may fail across filesystems; fall back to copy + delete.
            Files.copy(tempFile, dest, StandardCopyOption.REPLACE_EXISTING)
            try { Files.deleteIfExists(tempFile) } catch (_: Exception) {}
        }
        return id to true
    }

    /**
     * Look up a binary id by checksum via the `/get/id/sql` route. The route
     * is disabled by default, so we enable it for the duration of the query
     * and disable it again in a `finally`. The route is global on the
     * daemon, hence the `@Synchronized` on the caller.
     */
    private fun findIdByChecksum(dbClient: DatabaseClient, checksum: String, logger: Logger): Long? {
        val route = "/get/id/sql"
        return try {
            dbClient.enableRoute(route)
            val ids = dbClient.getIdInSQL("WHERE u.checksum = '$checksum'")
            ids.firstOrNull()?.also {
                if (ids.size > 1) {
                    logger.warn("[gar-import] checksum {} matched {} ids; using the first ({})", checksum, ids.size, it)
                }
            }
        } catch (e: Exception) {
            logger.warn("[gar-import] id-by-checksum lookup failed for {}: {}", checksum, e.message)
            null
        } finally {
            try { dbClient.disableRoute(route) } catch (_: Exception) {}
        }
    }

    // ---- domain file traversal -----------------------------------------

    /** Recursively collect every [DomainFile] under [folder]. */
    private fun collectDomainFiles(folder: DomainFolder): List<DomainFile> {
        val out = mutableListOf<DomainFile>()
        out += folder.files.toList()
        folder.folders.forEach { collectDomainFiles(it) }
        return out
    }
}
