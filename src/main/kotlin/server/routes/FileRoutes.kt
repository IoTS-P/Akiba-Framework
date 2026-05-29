package org.iotsplab.akiba.server.routes

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.iotsplab.akiba.managers.ImportManager
import org.iotsplab.akiba.managers.ProgramManager
import org.iotsplab.akiba.managers.WorkspaceManager
import org.iotsplab.akiba.data.database.DatabaseClient
import org.iotsplab.akiba.utils.Configs
import org.iotsplab.akiba.utils.SqlSource
import kotlinx.coroutines.*
import java.io.File

data class ImportRequest(val instanceName: String, val files: List<String>)
data class FileResponse(val message: String, val fileIds: List<Long> = listOf())
data class DeleteFileRequest(val instanceName: String, val fileIds: List<Long>)

fun Route.fileRoutes() {
    post("/files/import") {
        val req = call.receive<ImportRequest>()
        try {
            // TODO: switch to correct instance
            ImportManager.import()
            call.respond(FileResponse("Files imported", listOf()))
        } catch (e: Exception) {
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError,
                mapOf("error" to "Import failed: ${e.message}"))
        }
    }

    get("/files") {
        try {
            val binaries = DatabaseClient.getIdInSQL("")
            call.respond(mapOf("files" to binaries))
        } catch (e: Exception) {
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError,
                mapOf("error" to e.message))
        }
    }

    delete("/files") {
        val req = call.receive<DeleteFileRequest>()
        try {
            call.respond(FileResponse("Files deleted"))
        } catch (e: Exception) {
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError,
                mapOf("error" to "Delete failed: ${e.message}"))
        }
    }
}
