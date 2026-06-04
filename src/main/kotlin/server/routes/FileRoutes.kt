package org.iotsplab.akiba.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.iotsplab.akiba.data.database.DatabaseClient
import org.iotsplab.akiba.managers.ImportManager
import java.nio.file.Path

data class ImportRequest(val instanceName: String = "", val files: List<String>)
data class ImportResponse(val message: String, val fileIds: List<Long> = listOf())
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

    // ------ Import files ----------------------------------------------------
    post("/files/import") {
        val instance = call.requireInstanceHeader() ?: return@post
        val req = call.receive<ImportRequest>()
        if (req.files.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No files specified"))
            return@post
        }
        try {
            val results = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                // Temporarily set global so ImportManager (which reads DatabaseClient.global)
                // can insert binary records through this session
                val previous = DatabaseClient.global
                DatabaseClient.global = dbClient
                try {
                    req.files.map { pathStr ->
                        val path = Path.of(pathStr)
                        require(path.toFile().exists()) {
                            "File not found: $pathStr"
                        }
                        require(path.toFile().isFile) {
                            "Not a regular file: $pathStr"
                        }
                        ImportManager.importSingleFile(path)
                    }
                } finally {
                    DatabaseClient.global = previous
                }
            }
            call.respond(ImportResponse("Import successful", results))
        } catch (e: Exception) {
            val msg = e.message ?: "Import failed"
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to msg))
        }
    }

    // ------ List files with metadata ---------------------------------------
    get("/files") {
        val instance = call.parameters["instanceName"]
            ?: call.instanceHeader()
            ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "error" to "Missing instance selection. Please select an instance first."
                ))
                return@get
            }
        try {
            val files = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val ids = dbClient.getIdInSQL("")
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
            call.respond(mapOf("files" to files))
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    // ------ Delete files ---------------------------------------------------
    delete("/files") {
        @Suppress("UNUSED_VARIABLE")
        val req = call.receive<DeleteFileRequest>()
        call.respond(HttpStatusCode.NotImplemented, mapOf(
            "error" to "File deletion via HTTP is not yet implemented"
        ))
    }
}
