package org.iotsplab.akiba.server.routes

import io.ktor.http.*
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.managers.WorkspaceManager
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

private val logger: Logger = LogManager.getLogger("FileRoutes")

private val MAX_UPLOAD_SIZE = 100L * 1024 * 1024   // 100 MB

data class DeleteFileRequest(val instanceName: String = "", val fileIds: List<Long>)

data class FileEntry(
    val id: Int,
    val name: String,
    val type: String,
    val arch: String?,
    val checksum: String,
    val compilerSpec: String?,
    val originalPath: String?
)

fun Route.fileRoutes(daemonHost: String, daemonPort: Int) {

    // ------ Import files (multipart upload + subprocess) --------------------
    post("/files/import") {
        val instance = call.requireInstanceHeader() ?: return@post
        logger.info("File import request for instance '{}'", instance)

        val uploadedFiles = mutableListOf<Pair<Path, String>>()
        val taskId = UUID.randomUUID().toString().take(8)
        val uploadDir = Path.of("/tmp", "akiba_upload_$taskId")
        Files.createDirectories(uploadDir)

        try {
            // 1. Receive uploaded files.  Ktor's default form-field
            // limit is 50 MiB — smaller than our own 100 MB cap, so a
            // 57 MB upload died inside the multipart parser with an
            // opaque 400.  Pass our cap explicitly; the per-file
            // accounting below still enforces the total.
            var totalBytes = 0L
            call.receiveMultipart(MAX_UPLOAD_SIZE).forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        val originalName = part.originalFileName ?: "uploaded.bin"
                        val tempFile = uploadDir.resolve(originalName)
                        part.streamProvider().use { input ->
                            Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING)
                        }
                        val fileSize = Files.size(tempFile)
                        totalBytes += fileSize
                        if (totalBytes > MAX_UPLOAD_SIZE) {
                            // Exceeded limit — clean up and let the caller know
                            // before processing any further.
                            throw SizeLimitExceededException(
                                "Total upload size ${totalBytes / (1024 * 1024)} MB exceeds the 100 MB limit. " +
                                "Please upload files in smaller batches."
                            )
                        }
                        logger.debug("Received file '{}' ({} bytes)", originalName, fileSize)
                        uploadedFiles.add(tempFile to originalName)
                        part.dispose()
                    }
                    else -> part.dispose()
                }
            }

            if (uploadedFiles.isEmpty()) {
                Files.deleteIfExists(uploadDir)
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No files uploaded"))
                return@post
            }

            logger.info("Saving {} file(s) to {}", uploadedFiles.size, uploadDir)

            // 2. Create import.json — list each uploaded file
            val entries = uploadedFiles.map { (_, origName) ->
                mapOf("path" to origName)
            }
            val importJson = mapper.writeValueAsString(mapOf("entries" to entries))
            Files.writeString(uploadDir.resolve("import.json"), importJson)

            // 3. Create config.json (the -c file)
            val config = mapOf(
                "username" to (call.parameters["username"] ?: "akiba"),
                "password" to "akiba",
                "usingInstance" to instance,
                "general" to mapOf(
                    "importRoot" to "/tmp/akiba_upload_$taskId/"
                ),
                "withGhidraProject" to mapOf(
                    "projectRoot" to "/tmp/akiba_upload_$taskId",
                    "name" to "import",
                    "mode" to "new",
                    "saveProject" to false,
                    "noCreateProgram" to true
                )
            )
            val configJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config)
            Files.writeString(uploadDir.resolve("config.json"), configJson)

            // 4. Register task via ProgressManager (gets a unique token)
            val progressToken = ProgressManager.registerTask(taskId)

            // 5. Launch subprocess with HTTP-based progress reporting
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val serverPort = call.request.local.serverPort
                    runImportSubprocess(
                        taskId = taskId,
                        uploadDir = uploadDir,
                        serverPort = serverPort,
                        progressToken = progressToken
                    )
                    ProgressManager.updateStatus(progressToken, "completed")
                    ProgressManager.setResult(progressToken,
                        "Import completed successfully (${uploadedFiles.size} file(s))"
                    )
                } catch (e: Exception) {
                    logger.error("Import subprocess failed: {}", e.message)
                    ProgressManager.updateStatus(progressToken, "failed")
                    ProgressManager.setResult(progressToken, "Import failed: ${e.message}")
                } finally {
                    ProgressManager.finish(taskId)
                }
            }

            call.respond(mapOf(
                "taskId" to taskId,
                "message" to "Import started with ${uploadedFiles.size} file(s)"
            ))

        } catch (e: Exception) {
            logger.error("Import setup failed: {}", e.message, e)
            try { uploadDir.toFile().deleteRecursively() } catch (_: Exception) {}
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
        }
    }

    // ------ Receive progress reports from child process (HTTP POST) ---------
    post("/progress") {
        val body = try {
            call.receive<Map<String, String>>()
        } catch (_: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid JSON"))
            return@post
        }
        val token = body["token"] ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing token"))
            return@post
        }
        val type = body["type"] ?: ""
        val message = body["message"] ?: ""

        when (type) {
            "progress" -> ProgressManager.onProgress(token, message)
            "status" -> ProgressManager.updateStatus(token, message)
            "result" -> ProgressManager.setResult(token, message)
            else -> ProgressManager.onProgress(token, message)
        }

        call.respond(HttpStatusCode.OK, mapOf("ok" to true))
    }

    // ------ Stream import progress (SSE) -----------------------------------
    get("/files/import/stream/{taskId}") {
        val taskId = call.parameters["taskId"] ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing taskId"))
            return@get
        }
        val status = ProgressManager.getStatus(taskId)
        if (status == "unknown") {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Task not found"))
            return@get
        }

        // Set SSE headers via the raw response
        call.response.header(HttpHeaders.ContentType, "text/event-stream")
        call.response.header("Cache-Control", "no-cache")
        call.response.header("X-Accel-Buffering", "no")

        // Use respondTextWriter for streaming text output
        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            val flow = ProgressManager.getFlow(taskId)
            if (flow != null) {
                coroutineScope {
                    val collectJob = launch {
                        flow.collect { msg ->
                            write("data: $msg\n\n")
                            flush()
                        }
                    }
                    while (ProgressManager.getStatus(taskId) == "running") {
                        delay(100)
                    }
                    collectJob.cancel()
                }
            }
            val finalStatus = ProgressManager.getStatus(taskId)
            val finalResult = ProgressManager.getResult(taskId)
            write("data: [STATUS] $finalStatus\n\n")
            write("data: [RESULT] ${finalResult ?: ""}\n\n")
            write("data: [DONE]\n\n")
            flush()
        }
    }

    // ------ Search files by query -------------------------------------------
    get("/files/search") {
        val instance = call.parameters["instanceName"]
            ?: call.instanceHeader()
            ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "error" to "Missing instance selection. Please select an instance first."
                ))
                return@get
            }
        val query = call.parameters["q"] ?: ""
        logger.debug("File search requested: instance='{}', query='{}'", instance, query)
        try {
            val results = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                dbClient.searchBinaries(query)
            }
            call.respond(mapOf("files" to results, "query" to query))
        } catch (e: Exception) {
            logger.error("File search failed: {}", e.message, e)
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    // ------ List files with metadata (paginated) ---------------------------
    get("/files") {
        val instance = call.parameters["instanceName"]
            ?: call.instanceHeader()
            ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "error" to "Missing instance selection. Please select an instance first."
                ))
                return@get
            }
        val offset = call.parameters["offset"]?.toIntOrNull() ?: 0
        val limit = call.parameters["limit"]?.toIntOrNull() ?: 20
        logger.debug("File list requested: instance='{}', offset={}, limit={}", instance, offset, limit)
        try {
            val files = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val ids = dbClient.getIdPage(offset, limit)
                logger.debug("Got {} ids for page offset={} limit={}", ids.size, offset, limit)
                ids.map { id ->
                    try {
                        val meta = dbClient.getMetadata(id)
                        FileEntry(
                            id = meta.id,
                            name = meta.originalPath.substringAfterLast("/")
                                .ifBlank { meta.originalPath },
                            type = meta.format ?: "unknown",
                            arch = meta.arch,
                            checksum = meta.checksum,
                            compilerSpec = meta.compilerSpec,
                            originalPath = meta.originalPath
                        )
                    } catch (_: Exception) {
                        FileEntry(
                            id = id.toInt(),
                            name = "file-$id",
                            type = "unknown",
                            arch = null,
                            checksum = "",
                            compilerSpec = null,
                            originalPath = null
                        )
                    }
                }
            }
            val total = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                dbClient.getIdCount()
            }
            logger.debug("File list returned {}/{} items", files.size, total)
            call.respond(mapOf(
                "files" to files,
                "total" to total,
                "offset" to offset,
                "limit" to limit
            ))
        } catch (e: Exception) {
            logger.error("File list failed: {}", e.message, e)
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    // ------ Delete files ---------------------------------------------------
    delete("/files") {
        val instance = call.parameters["instanceName"]
            ?: call.instanceHeader()
            ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "error" to "Missing instance selection. Please select an instance first."
                ))
                return@delete
            }
        val req = try {
            call.receive<DeleteFileRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf(
                "error" to "Invalid request body: expected {\"fileIds\":[1,2,...]}"))
            return@delete
        }
        val ids = req.fileIds.filter { it > 0 }.distinct()
        if (ids.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No file ids provided"))
            return@delete
        }
        if (ids.size > 500) {
            call.respond(HttpStatusCode.BadRequest, mapOf(
                "error" to "Too many file ids (max 500 per call)"))
            return@delete
        }
        logger.info("File delete requested for instance '{}': {} id(s)", instance, ids.size)
        try {
            // 1. Delete the DB rows.  The daemon's DELETE cascades to
            //    processed_binaries and results via ON DELETE CASCADE.
            val deleted = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                dbClient.deleteBinaries(ids)
            }

            // 2. Best-effort physical cleanup of the stored copies
            //    ("<id>.bin" under the binaries root).  Filenames are
            //    server-generated and ids come from the database, so
            //    there is no path-traversal surface here.  Failures are
            //    logged but do not fail the request — the rows are
            //    already gone and a stale file is harmless.
            WorkspaceManager.initBinDirectories()
            deleted.forEach { id ->
                listOf(
                    WorkspaceManager.binaryPath.resolve("$id.bin"),
                    WorkspaceManager.processedBinaryPath.resolve("$id.bin")
                ).forEach { p ->
                    try {
                        if (Files.deleteIfExists(p))
                            logger.debug("Deleted stored copy {}", p)
                    } catch (e: Exception) {
                        logger.warn("Failed to delete stored copy {}: {}", p, e.message)
                    }
                }
            }

            logger.info("File delete completed for instance '{}': {} deleted", instance, deleted.size)
            call.respond(mapOf(
                "message" to "Deleted ${deleted.size} file(s)",
                "deletedIds" to deleted
            ))
        } catch (e: Exception) {
            logger.error("File delete failed: {}", e.message, e)
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }
}

// ---- Find the akiba jar path ------------------------------------------------
// ---- Find the akiba start script -------------------------------------------
//
// Distribution layout:
//   akiba_framework/
//     bin/akiba              ← start script (handles classpath with all lib/*.jar)
//     lib/akiba_framework-*.jar
//     configs/
//     scripts/
//
// We find the start script by resolving bin/akiba relative to the jar location.

private fun findAkibaScript(): String {
    // 1. From jar location: lib/akiba_framework-*.jar → ../bin/akiba
    try {
        val source = org.iotsplab.akiba.Main::class.java.protectionDomain.codeSource
        val loc = source.location.toURI()
        val jarFile = File(loc)
        if (jarFile.name.endsWith(".jar")) {
            val distRoot = jarFile.parentFile.parentFile  // lib/ → ../
            val script = File(distRoot, "bin/akiba")
            if (script.isFile) return script.absolutePath
        }
    } catch (_: Exception) { /* fall through */ }

    // 2. Try common locations relative to the working directory
    val cwd = System.getProperty("user.dir", ".")
    val candidates = listOf(
        File(cwd, "bin/akiba"),
        File(cwd, "../bin/akiba"),
        File(cwd, "akiba_framework/bin/akiba"),
    )
    for (candidate in candidates) {
        if (candidate.isFile) return candidate.absolutePath
    }

    // 3. Last resort — assume bin/akiba is on PATH
    return "akiba"
}

// ---- Run the import subprocess with HTTP-based progress reporting -----------
private suspend fun runImportSubprocess(
    taskId: String,
    uploadDir: Path,
    serverPort: Int,
    progressToken: String
) {
    val scriptPath = findAkibaScript()
    val configFile = uploadDir.resolve("config.json").toAbsolutePath().toString()
    val importFile = uploadDir.resolve("import.json").toAbsolutePath().toString()
    val progressUrl = "http://127.0.0.1:$serverPort/api/progress"

    val pb = ProcessBuilder(
        scriptPath,
        "-c", configFile,
        "-i", importFile
    )
    pb.directory(File(System.getProperty("user.dir", ".")))
    pb.environment()["AKIBA_PROGRESS_URL"] = progressUrl
    pb.environment()["AKIBA_PROGRESS_TOKEN"] = progressToken
    pb.environment()["AKIBA_LLM_API_KEY"] = System.getenv("AKIBA_LLM_API_KEY") ?: ""

    // Keep stderr merged so we still see errors in server log
    pb.redirectErrorStream(true)
    val process = pb.start()

    // Still consume stdout to avoid buffer deadlock, but just log it instead of sending to channel
    process.inputStream.bufferedReader().use { reader ->
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            logger.info("[import $taskId] $line")
        }
    }

    val exitCode = process.waitFor()
    if (exitCode != 0) {
        throw RuntimeException("Import subprocess failed with exit code $exitCode")
    }
}

private val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()

/** Thrown when the total size of uploaded files exceeds [FileRoutes.MAX_UPLOAD_SIZE]. */
private class SizeLimitExceededException(message: String) : Exception(message)
