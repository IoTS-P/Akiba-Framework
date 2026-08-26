package org.iotsplab.akiba.managers

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ghidra.program.model.lang.Language
import ghidra.program.model.lang.LanguageID
import ghidra.program.model.listing.Program
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.iotsplab.akiba.Main.Companion.importConfig
import org.iotsplab.akiba.data.database.DatabaseClient
import org.iotsplab.akiba.managers.ConfigManager.mainConf
import org.iotsplab.akiba.managers.ProgramManager.autoAnalyzeInTimeout
import org.iotsplab.akiba.managers.WorkspaceManager.globalLogger
import org.iotsplab.akiba.managers.WorkspaceManager.languageProvider
import org.iotsplab.akiba.managers.WorkspaceManager.project
import org.iotsplab.akiba.utils.ProgressReporter
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.*
import kotlin.math.min

object ImportManager {
    private val db: DatabaseClient get() = DatabaseClient.global!!
    @Serializable
    data class ImportConfig(
        val entries: List<Map<String, String?>>? = null
    )

    data class ImportProperty(
        var path: Path,         // Relative path of binary root
        var arch: Language? = null,
        var extraProperties: Map<String, String?>? = null,   // All properties must be string or null
        var fileSize: Int = -1
    )

    @Serializable
    data class FileSegment(
        val oldOffset: Long,
        val newOffset: Long,
        val length: Long
    )

    private lateinit var config: ImportConfig
    private val importList: MutableList<ImportProperty> = mutableListOf()

    @Throws(IllegalStateException::class)
    fun import() {
        if (mainConf.importRoot == null)
            throw IllegalArgumentException("Import root is not set")
        lockImport()

        readConfig()
        ProgressReporter.report("Starting import of ${importList.size} file(s)")

        importList.mapIndexed { idx, entry ->
            val originalPath = if (entry.path.startsWith("/")) entry.path.absolute()
                               else Path.of(mainConf.importRoot!!).resolve(entry.path).absolute()

            globalLogger.info("Importing [${idx + 1}/${importList.size}] $originalPath")
            ProgressReporter.report("Importing [${idx + 1}/${importList.size}] ${entry.path}")

            // Check if file exists
            if (originalPath.notExists() || !originalPath.isRegularFile()) {
                val errMsg = "File not found: $originalPath, skipped"
                globalLogger.error(errMsg)
                ProgressReporter.report(errMsg)
                return@mapIndexed
            }

            // We have removed all duplicated files in `readConfig`, no need to check it again here

            try {
                importSingleFile(originalPath, entry.arch)
                ProgressReporter.report("Imported ${entry.path} successfully")
            } catch (e: DuplicateChecksumException) {
                val msg = "Found duplicate checksum of ${entry.path}, skipped"
                globalLogger.warn(msg)
                ProgressReporter.report(msg)
            }
        }

        ProgressReporter.report("Import completed (${importList.size} file(s) processed)")
        unlockImport()
    }

    // Progress reporting is handled by the shared org.iotsplab.akiba.utils.ProgressReporter.
    // Any code can report progress with:
    //   ProgressReporter.report("message")
    //   ProgressReporter.status("completed")
    //   ProgressReporter.result("All done")
    // It gracefully no-ops when the env vars are not set.

    class DuplicateChecksumException(checksum: String) :
        IllegalStateException("Duplicate checksum: $checksum")

    /**
     * Re-materialize the Ghidra [Program] for a binary that is ALREADY
     * registered in the database ([binaryId]) but has no program file
     * in the CURRENT project.
     *
     * This happens when the duplicate-checksum path maps a file to an
     * existing database id whose program was created under a DIFFERENT
     * (since deleted/recreated) Ghidra project: the DB row persists,
     * but the current project's root folder has no `"<id>-*"` file, so
     * every `getProgram(binaryId)` prefix scan comes up empty.
     *
     * No database rows are written.  The program is saved under the
     * standard `"<binaryId>-<fileName>"` name so the usual prefix-scan
     * lookup finds it afterwards.  Auto-analysis runs BEFORE saving,
     * mirroring [importSingleFile].
     *
     * @return true when a program for [binaryId] exists in the current
     *         project afterwards (already present or newly created).
     */
    fun reimportProgramIntoProject(originalPath: Path, binaryId: Long): Boolean {
        require(originalPath.exists() && originalPath.isRegularFile()) {
            "File not found or not a regular file: $originalPath"
        }

        // Already present in this project — nothing to do.
        if (project.projectData.rootFolder.files.any { it.name.startsWith("$binaryId-") }) {
            return true
        }

        val program = ProgramManager.tryCreateProgramWithAutoDetect(project, originalPath)
            ?: ProgramManager.tryCreateProgramWithoutLang(project, originalPath)
            ?: return false
        return try {
            autoAnalyzeInTimeout(program, mainConf.autoAnalysisTimeout)
            val txId = program.startTransaction("rename")
            program.name = "$binaryId-${originalPath.fileName}"
            program.endTransaction(txId, true)
            project.saveAs(program, "/", program.name, false)
            globalLogger.info(
                "Re-imported $originalPath into the current project as ${program.name} " +
                    "(binary id=$binaryId already registered in the database)"
            )
            true
        } catch (e: Exception) {
            globalLogger.warn("Failed to re-import $originalPath into the current project: ${e.message}")
            false
        } finally {
            runCatching { project.close(program) }
        }
    }

    /**
     * Import a single binary file into the project and register it in the database.
     *
     * This is the runtime/single-file counterpart of [import] (which iterates the import config
     * file). It is used both by the import-config code path and by [AkibaModule.importFile] for
     * binaries that are produced/discovered by a module while it is analyzing another binary.
     *
     * @param originalPath Absolute path to the source file. The file MUST already exist.
     * @param arch         Optional language hint. If null, Ghidra format auto-detection is
     *                     attempted first, then language guessing if that fails.
     * @param sourceId     Provenance: id of the binary being analyzed when this file is being
     *                     imported. `null` for top-level imports done by [import].
     * @param sourceModule Provenance: simple class name of the importing [AkibaModule]. `null`
     *                     for top-level imports done by [import].
     * @return The id assigned to the newly registered binary in the database.
     * @throws DuplicateChecksumException If a binary with the same MD5 checksum already exists.
     * @throws IllegalArgumentException   If [originalPath] does not exist / is not a regular file.
     */
    @Throws(
        DuplicateChecksumException::class,
        IllegalArgumentException::class,
        IllegalStateException::class,
    )
    fun importSingleFile(
        originalPath: Path,
        arch: Language? = null,
        sourceId: Int? = null,
        sourceModule: String? = null,
    ): Long {
        require(originalPath.exists() && originalPath.isRegularFile()) {
            "File not found or not a regular file: $originalPath"
        }

        val originalChecksum = ProgramManager.getFileMD5Checksum(originalPath)
        if (db.checkMD5Duplicate(originalChecksum))
            throw DuplicateChecksumException(originalChecksum)

        // Archive/container files (apk, zip, jar, gzip, 7z, rar, tar, …):
        // import the FILE ITSELF as a single Raw Binary program using the
        // DATA pseudo-language (no real architecture). Auto-detection or
        // arch guessing on such files either fails or misimports the
        // container's contents instead of the file.
        if (isArchiveFile(originalPath)) {
            return importArchiveAsRawBinary(originalPath, originalChecksum, sourceId, sourceModule)
        }

        // If Ghidra can automatically detect the file format, import directly and insert it to database
        ProgramManager.tryCreateProgramWithAutoDetect(project, originalPath)?.let {
            // Ghidra successfully identified the binary format, import directly.
            // Auto-analyze BEFORE saving: without analysis the program's
            // function manager is empty and every downstream function
            // lookup (JNI exports, decompile, xrefs, script_library runs)
            // comes up empty.  Symmetric with the arch-specified path below.
            autoAnalyzeInTimeout(it, mainConf.autoAnalysisTimeout)
            val id = db.insertBinary(
                DatabaseClient.InsertData(
                    originalPath = originalPath.absolutePathString(),
                    processedPath = null,
                    checksum = originalChecksum,
                    processedChecksum = null,
                    size = originalPath.fileSize(),
                    processedSize = -1,
                    loadProperties = null,
                    arch = it.languageID.idAsString,
                    format = it.executableFormat,
                    compilerSpec = it.compiler,
                    sourceId = sourceId,
                    sourceModule = sourceModule,
                )
            )
            originalPath.copyTo(WorkspaceManager.binaryPath.resolve("$id.bin"), overwrite = true)

            val txId = it.startTransaction("rename")
            it.name = "$id-${originalPath.fileName}"
            it.endTransaction(txId, true)
            project.saveAs(it, "/", it.name, false)
            project.close(it)
            return id
        }

        // Preprocess binaries, now only contains removing unnecessary continuous \x00 segments
        val preprocessed: Pair<Path, List<FileSegment>>? = preprocessFile(originalPath)
        val program: Program? = arch?.let {
            val prog = ProgramManager.loadProgram(
                originalPath,
                project,
                languageProvider.getLanguage(it.languageID)
            )
            prog?.let { p -> autoAnalyzeInTimeout(p, mainConf.autoAnalysisTimeout) }
            prog
        } ?: ProgramManager.tryCreateProgramWithoutLang(
            project = project,
            path = preprocessed?.first ?: originalPath,
            loadProperties = preprocessed?.second ?: listOf()
        ) ?: run {
            globalLogger.warn("Failed to guess language: $originalPath")
            null
        }

        // If no arch is specified, try to guess its arch, if no arch matched, use 'n/a'
        val resolvedArch = arch?.languageID?.toString() ?: program?.languageID?.toString() ?: "n/a"

        val id = db.insertBinary(DatabaseClient.InsertData(
            originalPath = originalPath.absolutePathString(),
            processedPath = preprocessed?.first?.absolutePathString(),
            checksum = originalChecksum,
            processedChecksum = preprocessed?.let { ProgramManager.getFileMD5Checksum(it.first) },
            size = originalPath.fileSize(),
            processedSize = preprocessed?.first?.fileSize() ?: -1,
            loadProperties = preprocessed?.let { jacksonObjectMapper().writeValueAsString(it.second) },
            arch = resolvedArch,
            format = program?.executableFormat ?: "n/a",
            compilerSpec = program?.compiler ?: "n/a",
            sourceId = sourceId,
            sourceModule = sourceModule,
        ))
        originalPath.copyTo(
            WorkspaceManager.binaryPath.resolve("$id.bin"), overwrite = true)
        preprocessed?.first?.moveTo(
            WorkspaceManager.processedBinaryPath.resolve("$id.bin"), overwrite = true)

        program?.let {
            val txId = it.startTransaction("rename")
            it.name = "$id-${originalPath.fileName}"
            it.endTransaction(txId, true)
            project.saveAs(it, "/", it.name, false)
            project.close(program)
        }

        return id
    }

    /**
     * Magic-byte check for archive/container files (zip family incl.
     * apk/jar, gzip, 7z, rar, xz, bzip2, tar).
     */
    private fun isArchiveFile(path: Path): Boolean {
        val head = ByteArray(8)
        val n = try {
            path.inputStream().use { it.read(head) }
        } catch (_: Exception) {
            return false
        }
        if (n >= 4) {
            // ZIP family: PK\x03\x04 / PK\x05\x06 / PK\x07\x08
            if (head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
                (head[2] == 0x03.toByte() || head[2] == 0x05.toByte() || head[2] == 0x07.toByte())
            ) return true
            // gzip
            if (head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte()) return true
            // rar: Rar!\x1A\x07
            if (head[0] == 0x52.toByte() && head[1] == 0x61.toByte() &&
                head[2] == 0x72.toByte() && head[3] == 0x21.toByte()
            ) return true
            // bzip2: BZh
            if (head[0] == 0x42.toByte() && head[1] == 0x5A.toByte() && head[2] == 0x68.toByte()) return true
        }
        if (n >= 6) {
            // 7z: 7z\xBC\xAF\x27\x1C
            if (head[0] == 0x37.toByte() && head[1] == 0x7A.toByte() && head[2] == 0xBC.toByte() &&
                head[3] == 0xAF.toByte() && head[4] == 0x27.toByte() && head[5] == 0x1C.toByte()
            ) return true
            // xz: \xFD7zXZ\x00
            if (head[0] == 0xFD.toByte() && head[1] == 0x37.toByte() && head[2] == 0x7A.toByte() &&
                head[3] == 0x58.toByte() && head[4] == 0x5A.toByte() && head[5] == 0x00.toByte()
            ) return true
        }
        // tar: "ustar" at offset 257
        try {
            path.inputStream().use { ins ->
                ins.skip(257)
                val magic = ByteArray(5)
                if (ins.read(magic) == 5 &&
                    magic[0] == 'u'.code.toByte() && magic[1] == 's'.code.toByte() &&
                    magic[2] == 't'.code.toByte() && magic[3] == 'a'.code.toByte() &&
                    magic[4] == 'r'.code.toByte()
                ) return true
            }
        } catch (_: Exception) { /* not a tar */ }
        return false
    }

    /**
     * Import an archive/container file as-is: a single Raw Binary
     * program backed by the DATA pseudo-language (no real
     * architecture), without auto-analysis.
     */
    private fun importArchiveAsRawBinary(
        originalPath: Path,
        originalChecksum: String,
        sourceId: Int?,
        sourceModule: String?,
    ): Long {
        val dataLanguage = languageProvider.getLanguage(LanguageID("DATA:LE:64:default"))
            ?: throw IllegalStateException("DATA pseudo-language is not available")
        val program = ProgramManager.loadProgram(originalPath, project, dataLanguage)
            ?: throw IllegalStateException("Failed to import $originalPath as Raw Binary")

        val id = db.insertBinary(
            DatabaseClient.InsertData(
                originalPath = originalPath.absolutePathString(),
                processedPath = null,
                checksum = originalChecksum,
                processedChecksum = null,
                size = originalPath.fileSize(),
                processedSize = -1,
                loadProperties = null,
                arch = dataLanguage.languageID.toString(),
                format = program.executableFormat,
                compilerSpec = program.compiler,
                sourceId = sourceId,
                sourceModule = sourceModule,
            )
        )
        originalPath.copyTo(WorkspaceManager.binaryPath.resolve("$id.bin"), overwrite = true)

        val txId = program.startTransaction("rename")
        program.name = "$id-${originalPath.fileName}"
        program.endTransaction(txId, true)
        project.saveAs(program, "/", program.name, false)
        project.close(program)
        globalLogger.info("Imported archive $originalPath as Raw Binary (id=$id)")
        return id
    }

    @Throws(IllegalStateException::class)
    private fun lockImport() {
        // Use the resolved binaries root from WorkspaceManager (auto-computed if not configured)
        val base = WorkspaceManager.binaryPath.parent
        val lockFile: Path = base.resolve("import.lock")
        if (lockFile.exists()) {
            throw IllegalStateException("An import task is already running")
        }
    }

    @Throws(IllegalStateException::class)
    private fun unlockImport() {
        val base = WorkspaceManager.binaryPath.parent
        val lockFile: Path = base.resolve("import.lock")
        if (lockFile.exists()) {
            lockFile.deleteIfExists()
        }
    }

    @Throws(IllegalStateException::class)
    private fun readConfig() {
        check (importConfig != null) { "Import config is not set" }
        val path = Path.of(importConfig!!)
        globalLogger.info("Import config path: $path")
        check (path.isRegularFile()) { "Import config file does not exist" }
        try {
            config = Json.decodeFromString(path.readText())
        } catch (e: Exception) {
            throw IllegalStateException("Failed to read import config file: ${e.message}")
        }
        parseList()
    }

    @Throws(IllegalArgumentException::class)
    private fun parseList() {
        config.entries?.forEach {
            importList.addAll(parseEntry(it))
        }
    }

    @Throws(IllegalArgumentException::class)
    private fun parseEntry(entry: Map<String, String?>): List<ImportProperty> {
        require(entry["path"] != null && entry["path"] is String) { "Path is not set" }
        val absolutePath = Path.of(mainConf.importRoot!!).resolve(Path.of(entry["path"]!!))
        require(absolutePath.exists()) { "Path $absolutePath does not exist" }
        val arch = entry["arch"] ?. let {
            val languageID = LanguageID(it)
            languageProvider.getLanguage(languageID) ?: run {
                throw IllegalArgumentException("Architecture invalid: $it")
            }
        }
        val properties: MutableMap<String, String?> = mutableMapOf()
        entry.forEach { (k, v) ->
            if (k != "path" && k != "arch") {
                require(v is String || v == null) { "Each property must be string or null" }
                properties[k] = v
            }
        }

        if (absolutePath.isRegularFile()) {
            globalLogger.info("Found ${Path.of(entry["path"]!!)}")
            return listOf(ImportProperty(Path.of(entry["path"]!!), arch, properties,
                absolutePath.toFile().length().toInt()))
        } else {
            val files = absolutePath.toFile().listFiles()
            return files ?.mapIndexed { idx, it ->
                globalLogger.info("Found ${it.absolutePath} in $absolutePath")
                if (it.length() < MIN_BINARIES_SIZE) return@mapIndexed null
                if (!it.toPath().isRegularFile()) return@mapIndexed null
                ImportProperty(Path.of(mainConf.importRoot!!).relativize(it.toPath()), arch, properties,
                    it.length().toInt())
            }
            ?.filterNotNull() ?: listOf()
        }
    }

    // Process files to remove long zero segments

    fun preprocessFile(path: Path): Pair<Path, List<FileSegment>>? {
        val zeroRegions = detectZeroRegions(path)
        if (zeroRegions.isEmpty())
            return null

        val nonZeroSegments = calculateNonZeroSegments(path.fileSize(), zeroRegions)
        if (nonZeroSegments.isNotEmpty())
            return createTrimmedFile(path, nonZeroSegments) to nonZeroSegments
        else {
            globalLogger.warn("$path is an all-0 file, ignored")
            return null
        }
    }

    private fun detectZeroRegions(path: Path): List<Pair<Long, Long>> {
        val zeroRegions = mutableListOf<Pair<Long, Long>>()
        var currentStart = -1L
        var currentLength = 0L

        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            val buffer = ByteBuffer.allocate(8192)
            var filePosition = 0L

            while (channel.read(buffer) != -1) {
                buffer.flip()
                for (i in 0 until buffer.limit()) {
                    if (buffer.get(i) == 0.toByte()) {
                        if (currentStart == -1L) currentStart = filePosition + i
                        currentLength++
                    } else {
                        checkAndRecordRegion(currentStart, currentLength, zeroRegions)
                        currentStart = -1L
                        currentLength = 0L
                    }
                }
                filePosition += buffer.limit()
                buffer.clear()
            }

            checkAndRecordRegion(currentStart, currentLength, zeroRegions)
        }

        return mergeAdjacentRegions(zeroRegions)
    }

    private fun checkAndRecordRegion(start: Long, length: Long, regions: MutableList<Pair<Long, Long>>) {
        if (length >= 0x10000) {
            regions.add(start to (start + length))
        }
    }

    private fun mergeAdjacentRegions(regions: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        return regions.sortedBy { it.first }.fold(mutableListOf()) { acc, region ->
            acc.lastOrNull()?.let { last ->
                if (region.first <= last.second) {
                    acc[acc.lastIndex] = last.first to maxOf(last.second, region.second)
                    return@fold acc
                }
            }
            acc.add(region)
            acc
        }
    }

    private fun calculateNonZeroSegments(fileSize: Long, zeroRegions: List<Pair<Long, Long>>): List<FileSegment> {
        val segments = mutableListOf<FileSegment>()
        var prevEnd = 0L
        var newOffset = 0L
        var segStart: Long
        var segEnd: Long

        for ((start, end) in zeroRegions.sortedBy { it.first }) {
            if (start > prevEnd) {
                segStart = prevEnd - prevEnd % ZERO_SPACE_ALIGNMENT
                segEnd = if (start % ZERO_SPACE_ALIGNMENT == 0L) start
                         else start + ZERO_SPACE_ALIGNMENT - start % ZERO_SPACE_ALIGNMENT
                segments.add(FileSegment(segStart, newOffset, segEnd - segStart))
                newOffset += segEnd - segStart
            }
            prevEnd = end
        }

        if (prevEnd < fileSize) {
            segStart = prevEnd - prevEnd % ZERO_SPACE_ALIGNMENT
            segments.add(FileSegment(segStart, newOffset, fileSize - segStart))
        }

        return segments
    }

    private fun createTrimmedFile(source: Path, segments: List<FileSegment>): Path {
        val target = Path.of("/tmp").resolve("trimmed-" + source.fileName)

        FileChannel.open(target, StandardOpenOption.WRITE, StandardOpenOption.CREATE).use { targetChannel ->
            FileChannel.open(source, StandardOpenOption.READ).use { sourceChannel ->
                for ((offset, _, length) in segments) {
                    var remaining = length
                    var position = offset

                    while (remaining > 0) {
                        val transferSize = min(remaining, 8 * 1024 * 1024) // 8MB buffer
                        targetChannel.transferFrom(
                            sourceChannel.position(position),
                            targetChannel.size(),
                            transferSize
                        )
                        remaining -= transferSize
                        position += transferSize
                    }
                }
                sourceChannel.force(true)
                sourceChannel.close()
            }
            targetChannel.force(true)
            targetChannel.close()
        }

        return target
    }

    private const val MIN_BINARIES_SIZE: Int = 0x1000
    private const val ZERO_SPACE_ALIGNMENT: Int = 0x10
}