package org.iotsplab.akiba.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.apache.logging.log4j.LogManager
import java.nio.file.Files
import java.nio.file.Path

private val logger = LogManager.getLogger("UpdateRoutes")

/** Directory for update status and staged artifacts. */
private val updateDir: Path get() =
    Path.of(System.getProperty("user.home"), ".akiba", "updates")

private val statusFile: Path get() = updateDir.resolve("status.json")

/** Path to the auto-update script inside the container. */
private val autoUpdateScript: Path get() =
    Path.of(System.getProperty("user.home"), "binaries", "auto_update.sh")

/**
 * Routes for the auto-update system.
 *
 * - `GET  /update/status` — returns the last check result from
 *   `~/.akiba/updates/status.json` (written by `auto_update.sh`).
 * - `POST /update/check`  — runs `auto_update.sh --check` synchronously
 *   to query GitHub and download artifacts if a newer release exists,
 *   then returns the updated status.
 *
 * Updates are never applied live — they are staged to disk and applied
 * by the entrypoint's `--apply` on the next container restart.  The
 * frontend uses these endpoints to show a "restart to update" banner.
 */
fun Route.updateRoutes() {

    get("/update/status") {
        try {
            if (Files.exists(statusFile)) {
                val content = Files.readString(statusFile)
                call.respondText(content, ContentType.Application.Json)
            } else {
                // No status file — the checker has never run.
                call.respond(mapOf(
                    "lastCheck" to null,
                    "currentVersion" to null,
                    "latestVersion" to null,
                    "updateAvailable" to false,
                    "stagedArtifacts" to emptyList<String>(),
                    "error" to null
                ))
            }
        } catch (e: Exception) {
            logger.error("Failed to read update status", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
        }
    }

    post("/update/check") {
        try {
            if (!Files.exists(autoUpdateScript)) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Auto-update script not found"))
                return@post
            }

            logger.info("Manual update check triggered")

            // Run the check synchronously.  The script queries the GitHub
            // API and downloads artifacts if a newer release exists, then
            // writes status.json.  Timeout after 10 minutes to avoid
            // hanging on a slow download.
            val process = ProcessBuilder("bash", autoUpdateScript.toString(), "--check")
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)

            if (!finished) {
                process.destroyForcibly()
                logger.warn("Update check timed out")
                call.respond(HttpStatusCode.RequestTimeout, mapOf("error" to "Update check timed out"))
                return@post
            }

            val exitCode = process.exitValue()
            if (exitCode != 0) {
                logger.warn("Update check exited with code $exitCode: $output")
            }

            // Return the freshly-written status (or an error if the
            // script failed to write one).
            val status = if (Files.exists(statusFile)) {
                Files.readString(statusFile)
            } else {
                """{"error":"Check completed but no status file was written","output":"${output.replace("\"", "\\\"")}"}"""
            }
            call.respondText(status, ContentType.Application.Json)
        } catch (e: Exception) {
            logger.error("Failed to check for updates", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
        }
    }
}
