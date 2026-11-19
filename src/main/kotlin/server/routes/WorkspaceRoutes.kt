package org.iotsplab.akiba.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.jvm.javaio.toInputStream
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.managers.ConfigManager
import org.iotsplab.akiba.managers.WorkspacePaths
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Largest file the editor will open; beyond this it is a download, not a document. */
private const val MAX_EDIT_BYTES = 4L * 1024 * 1024

/**
 * Browsing and editing the two kinds of workspace Akiba keeps.
 *
 * A conversation owns `sessions/<id>/` and does not move when it switches programs; a program owns
 * `programs/<path>/` and keeps what was produced for it. The handlers are the same for both — only
 * the directory they are pointed at differs, so the scope is resolved once, up front.
 */
internal fun Route.workspaceRoutes(daemonHost: String, daemonPort: Int) {

    get("/agent/sessions/{id}/workspace/tree") { call.tree(daemonHost, daemonPort) }
    get("/agent/sessions/{id}/workspace/file") { call.readFile(daemonHost, daemonPort) }
    put("/agent/sessions/{id}/workspace/file") { call.writeFile(daemonHost, daemonPort) }
    post("/agent/sessions/{id}/workspace/upload") { call.upload(daemonHost, daemonPort) }
    delete("/agent/sessions/{id}/workspace/entry") { call.deleteEntry(daemonHost, daemonPort) }
    get("/agent/sessions/{id}/workspace/download") { call.download(daemonHost, daemonPort) }

    get("/skills/{skillId}/workspace/tree") { call.tree(daemonHost, daemonPort) }
    get("/skills/{skillId}/workspace/file") { call.readFile(daemonHost, daemonPort) }
    put("/skills/{skillId}/workspace/file") { call.writeFile(daemonHost, daemonPort) }
    post("/skills/{skillId}/workspace/upload") { call.upload(daemonHost, daemonPort) }
    delete("/skills/{skillId}/workspace/entry") { call.deleteEntry(daemonHost, daemonPort) }
    get("/skills/{skillId}/workspace/download") { call.download(daemonHost, daemonPort) }

    get("/projects/{name}/workspace/tree") { call.tree(daemonHost, daemonPort) }
    get("/projects/{name}/workspace/file") { call.readFile(daemonHost, daemonPort) }
    put("/projects/{name}/workspace/file") { call.writeFile(daemonHost, daemonPort) }
    post("/projects/{name}/workspace/upload") { call.upload(daemonHost, daemonPort) }
    delete("/projects/{name}/workspace/entry") { call.deleteEntry(daemonHost, daemonPort) }
    get("/projects/{name}/workspace/download") { call.download(daemonHost, daemonPort) }
}

// ---------------------------------------------------------------- handlers

private suspend fun ApplicationCall.tree(daemonHost: String, daemonPort: Int) {
    val root = scopeRoot(daemonHost, daemonPort) ?: return
    val target = WorkspacePaths.resolveInside(root, request.queryParameters["path"].orEmpty())
        ?: return respond(HttpStatusCode.BadRequest, mapOf("error" to "Path escapes the workspace"))
    if (!Files.exists(target)) return respond(HttpStatusCode.NotFound, mapOf("error" to "No such directory"))

    val entries = Files.list(target).use { it.toList() }
        // The binary is Akiba's, not the user's: it is not part of the file list.
        .filter { !WorkspacePaths.isReservedName(it.fileName.toString()) }
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
    respond(mapOf("path" to relative(root, target), "entries" to entries))
}

private suspend fun ApplicationCall.readFile(daemonHost: String, daemonPort: Int) {
    val root = scopeRoot(daemonHost, daemonPort) ?: return
    val target = WorkspacePaths.resolveInside(root, request.queryParameters["path"].orEmpty())
        ?: return respond(HttpStatusCode.BadRequest, mapOf("error" to "Path escapes the workspace"))
    if (!Files.isRegularFile(target)) return respond(HttpStatusCode.NotFound, mapOf("error" to "No such file"))

    val size = Files.size(target)
    if (size > MAX_EDIT_BYTES) {
        return respond(
            HttpStatusCode.PayloadTooLarge,
            mapOf("error" to "File is $size bytes; download it instead of opening it."),
        )
    }
    val bytes = Files.readAllBytes(target)
    val binary = bytes.any { it == 0.toByte() }
    respond(
        mapOf(
            "path" to relative(root, target),
            "size" to size,
            "binary" to binary,
            "content" to if (binary) "" else String(bytes, Charsets.UTF_8),
            "modified" to Files.getLastModifiedTime(target).toMillis(),
        ),
    )
}

private suspend fun ApplicationCall.writeFile(daemonHost: String, daemonPort: Int) {
    val root = scopeRoot(daemonHost, daemonPort) ?: return
    val target = WorkspacePaths.resolveInside(root, request.queryParameters["path"].orEmpty())
        ?: return respond(HttpStatusCode.BadRequest, mapOf("error" to "Path escapes the workspace"))
    if (isReservedRootTarget(root, target)) {
        return respond(HttpStatusCode.BadRequest, mapOf("error" to "'binary' is reserved by Akiba"))
    }
    val body = receiveText()
    Files.createDirectories(target.parent)
    Files.writeString(target, body)
    respond(mapOf("status" to "ok", "path" to relative(root, target), "size" to body.length))
}

private suspend fun ApplicationCall.upload(daemonHost: String, daemonPort: Int) {
    val root = scopeRoot(daemonHost, daemonPort) ?: return
    val dir = WorkspacePaths.resolveInside(root, request.queryParameters["path"].orEmpty())
        ?: return respond(HttpStatusCode.BadRequest, mapOf("error" to "Path escapes the workspace"))
    if (!Files.isDirectory(dir)) return respond(HttpStatusCode.BadRequest, mapOf("error" to "Not a directory"))

    val saved = mutableListOf<String>()
    receiveMultipart().forEachPart { part ->
        if (part is PartData.FileItem) {
            val name = part.originalFileName?.substringAfterLast('/')?.substringAfterLast('\\')
            val reserved = name != null && WorkspacePaths.isReservedName(name) &&
                dir.normalize() == root.normalize()
            if (!name.isNullOrBlank() && !reserved) {
                val dest = WorkspacePaths.resolveInside(root, relative(root, dir.resolve(name)))
                if (dest != null) {
                    Files.createDirectories(dest.parent)
                    Files.newOutputStream(dest).use { out -> part.provider().toInputStream().copyTo(out) }
                    saved += relative(root, dest)
                }
            }
        }
        part.dispose()
    }
    respond(mapOf("status" to "ok", "files" to saved))
}

private suspend fun ApplicationCall.deleteEntry(daemonHost: String, daemonPort: Int) {
    val root = scopeRoot(daemonHost, daemonPort) ?: return
    val target = WorkspacePaths.resolveInside(root, request.queryParameters["path"].orEmpty())
        ?: return respond(HttpStatusCode.BadRequest, mapOf("error" to "Path escapes the workspace"))
    if (isReservedRootTarget(root, target)) {
        return respond(HttpStatusCode.BadRequest, mapOf("error" to "'binary' is reserved by Akiba"))
    }
    if (target.normalize() == root.normalize()) {
        return respond(HttpStatusCode.BadRequest, mapOf("error" to "Refusing to delete the workspace root"))
    }
    if (!Files.exists(target)) return respond(HttpStatusCode.NotFound, mapOf("error" to "No such path"))

    Files.walk(target).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
    respond(mapOf("status" to "ok"))
}

private suspend fun ApplicationCall.download(daemonHost: String, daemonPort: Int) {
    val root = scopeRoot(daemonHost, daemonPort) ?: return
    val target = WorkspacePaths.resolveInside(root, request.queryParameters["path"].orEmpty())
        ?: return respond(HttpStatusCode.BadRequest, mapOf("error" to "Path escapes the workspace"))
    if (!Files.exists(target)) return respond(HttpStatusCode.NotFound, mapOf("error" to "No such path"))

    if (Files.isRegularFile(target)) return respondFile(target.toFile())

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
    respondFile(zip.toFile())
}

// ---------------------------------------------------------------- scope

/**
 * The directory this request is about.
 *
 * `{id}` means a conversation: its own project decides where it lives, and nothing about the
 * program it is bound to does. `{name}` means a program: the client names it with `program=`.
 */
private suspend fun ApplicationCall.scopeRoot(daemonHost: String, daemonPort: Int): Path? {
    val skillId = parameters["skillId"]
    if (skillId != null) {
        // A skill is a directory of documents, so it borrows the same handlers a workspace uses.
        // The resolver knows that built-in skills live under the service user, which is why a
        // name-based guess showed an empty folder for an installed skill.
        val root = runCatching {
            org.iotsplab.akiba.llm.skill.SkillManager
                .resolveSkillDir(currentUsernameOrDefault(), skillId)
        }.getOrNull()
        if (root == null) {
            respond(HttpStatusCode.NotFound, mapOf("error" to "No such skill"))
            return null
        }
        return root
    }

    val sessionId = parameters["id"]
    if (sessionId != null) {
        val instance = requireInstanceHeader() ?: return null
        val session = runCatching {
            withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                AgentDatabaseClient(dbClient).getSession(sessionId)
            }
        }.getOrNull()
        if (session == null) {
            respond(HttpStatusCode.NotFound, mapOf("error" to "No such session"))
            return null
        }
        val root = WorkspacePaths.sessionRoot(
            ConfigManager.mainConf.workspaceRoot, session.projectName ?: "default", sessionId,
        )
        Files.createDirectories(root)
        return root
    }

    val project = parameters["name"].orEmpty()
    val programPath = request.queryParameters["program"].orEmpty()
    if (project.isBlank() || programPath.isBlank()) {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing project or program"))
        return null
    }
    val root = WorkspacePaths.programRoot(ConfigManager.mainConf.workspaceRoot, project, programPath)
    Files.createDirectories(root)
    return root
}

/** A direct child of the workspace root may not be called `binary`. */
private fun isReservedRootTarget(root: Path, target: Path): Boolean =
    target.parent?.normalize() == root.normalize() &&
        WorkspacePaths.isReservedName(target.fileName.toString())

private fun relative(root: Path, target: Path): String =
    runCatching { root.relativize(target).toString() }.getOrDefault("")
