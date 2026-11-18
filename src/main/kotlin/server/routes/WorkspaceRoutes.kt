package org.iotsplab.akiba.server.routes

import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.managers.ConfigManager
import org.iotsplab.akiba.managers.WorkspacePaths
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Largest file the editor will open; beyond this it is a download, not a document. */
private const val MAX_EDIT_BYTES = 4L * 1024 * 1024

/**
 * Browsing and editing a conversation's workspace.
 *
 * The directory belongs to the conversation and nothing else: it does not move when the
 * conversation switches programs, and it is not tied to any program's directory. That is why the
 * only thing looked up here is the session's own project, which never changes.
 */
internal fun Route.workspaceRoutes(daemonHost: String, daemonPort: Int) {

    get("/agent/sessions/{id}/workspace/tree") {
        val session = sessionWorkspace(call, daemonHost, daemonPort) ?: return@get
        val (root, instance) = session
        val target = resolveInside(root, call.request.queryParameters["path"].orEmpty()) ?: return@get call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Path escapes the workspace"))
        if (!Files.exists(target)) {
            return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "No such directory"))
        }
        val entries = Files.list(target).use { stream -> stream.toList() }
            .sortedBy { it.fileName.toString().lowercase() }
            .map { p ->
                mapOf<String, Any>(
                    "name" to p.fileName.toString(),
                    "path" to relative(root, p),
                    "directory" to Files.isDirectory(p),
                    "size" to if (Files.isDirectory(p)) 0L
                              else runCatching { Files.size(p) }.getOrDefault(0L),
                    "modified" to runCatching { Files.getLastModifiedTime(p).toMillis() }.getOrDefault(0L),
                )
            }
        call.respond(mapOf("path" to relative(root, target), "entries" to entries, "instance" to instance))
    }

    get("/agent/sessions/{id}/workspace/file") {
        val (root, _) = sessionWorkspace(call, daemonHost, daemonPort) ?: return@get
        val target = resolveInside(root, call.request.queryParameters["path"].orEmpty())
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Path escapes the workspace"))
        if (!Files.isRegularFile(target)) {
            return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "No such file"))
        }
        val size = Files.size(target)
        if (size > MAX_EDIT_BYTES) {
            return@get call.respond(
                HttpStatusCode.PayloadTooLarge,
                mapOf("error" to "File is ${size} bytes; download it instead of opening it."),
            )
        }
        val bytes = Files.readAllBytes(target)
        val binary = bytes.any { it == 0.toByte() }
        call.respond(
            mapOf(
                "path" to relative(root, target),
                "size" to size,
                "binary" to binary,
                "content" to if (binary) "" else String(bytes, Charsets.UTF_8),
                "modified" to Files.getLastModifiedTime(target).toMillis(),
            ),
        )
    }

    put("/agent/sessions/{id}/workspace/file") {
        val (root, _) = sessionWorkspace(call, daemonHost, daemonPort) ?: return@put
        val target = resolveInside(root, call.request.queryParameters["path"].orEmpty())
            ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Path escapes the workspace"))
        val body = call.receiveText()
        Files.createDirectories(target.parent)
        Files.writeString(target, body)
        call.respond(mapOf("status" to "ok", "path" to relative(root, target), "size" to body.length))
    }

    post("/agent/sessions/{id}/workspace/upload") {
        val (root, _) = sessionWorkspace(call, daemonHost, daemonPort) ?: return@post
        val dir = resolveInside(root, call.request.queryParameters["path"].orEmpty())
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Path escapes the workspace"))
        if (!Files.isDirectory(dir)) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Not a directory"))
        }
        val saved = mutableListOf<String>()
        call.receiveMultipart().forEachPart { part ->
            if (part is PartData.FileItem) {
                val name = part.originalFileName?.substringAfterLast('/')?.substringAfterLast('\\')
                if (!name.isNullOrBlank()) {
                    val dest = resolveInside(root, relative(root, dir.resolve(name)))
                    if (dest != null) {
                        Files.createDirectories(dest.parent)
                        Files.newOutputStream(dest).use { out -> part.provider().toInputStream().copyTo(out) }
                        saved += relative(root, dest)
                    }
                }
            }
            part.dispose()
        }
        call.respond(mapOf("status" to "ok", "files" to saved))
    }

    get("/agent/sessions/{id}/workspace/download") {
        val (root, _) = sessionWorkspace(call, daemonHost, daemonPort) ?: return@get
        val target = resolveInside(root, call.request.queryParameters["path"].orEmpty())
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Path escapes the workspace"))
        if (!Files.exists(target)) {
            return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "No such path"))
        }
        if (Files.isRegularFile(target)) {
            return@get call.respondFile(target.toFile())
        }
        // A directory is handed over as one archive: one request, one file on the user's disk.
        val zip = Files.createTempFile("akiba-workspace-", ".zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { out ->
            Files.walk(target).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { file ->
                    out.putNextEntry(ZipEntry(target.relativize(file).toString()))
                    Files.copy(file, out)
                    out.closeEntry()
                }
            }
        }
        call.respondFile(zip.toFile())
    }
}

/** Resolve a session's workspace directory, or answer 404 and return null. */
private suspend fun sessionWorkspace(
    call: io.ktor.server.application.ApplicationCall,
    daemonHost: String,
    daemonPort: Int,
): Pair<Path, String>? {
    val id = call.parameters["id"].orEmpty()
    val instance = call.requireInstanceHeader() ?: return null
    val session = runCatching {
        withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
            AgentDatabaseClient(dbClient).getSession(id)
        }
    }.getOrNull()
    if (session == null) {
        call.respond(HttpStatusCode.NotFound, mapOf("error" to "No such session"))
        return null
    }
    val project = session.projectName ?: "default"
    val root = WorkspacePaths.sessionRoot(ConfigManager.mainConf.workspaceRoot, project, id)
    Files.createDirectories(root)
    return root to instance
}

/**
 * Turn a request path into a file inside [root], or null if it would leave.
 *
 * `..`, absolute paths and symlinks pointing elsewhere are all refused. The one link that is
 * allowed to leave is `binary`, which points at the program's copy by design.
 */
private fun resolveInside(root: Path, requested: String): Path? {
    val clean = requested.trim().removePrefix("/")
    if (clean.contains('\u0000')) return null
    if (clean.split('/', '\\').any { it == ".." }) return null

    val candidate = root.resolve(clean).normalize()
    if (!candidate.startsWith(root)) return null
    if (Files.exists(candidate)) {
        val real = runCatching { candidate.toRealPath() }.getOrNull() ?: return null
        val realRoot = runCatching { root.toRealPath() }.getOrNull() ?: return candidate
        if (!real.startsWith(realRoot) && candidate.fileName?.toString() != "binary") return null
    }
    return candidate
}

private fun relative(root: Path, target: Path): String =
    runCatching { root.relativize(target).toString() }.getOrDefault("")
