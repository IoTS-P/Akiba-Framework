package org.iotsplab.akiba.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.data.database.DatabaseClient

private val logger: Logger = LogManager.getLogger("InstanceRoutes")

data class InstanceRequest(val name: String)
data class InstanceActionRequest(val instanceName: String)
data class InstanceResponse(val message: String, val instanceName: String? = null)

data class InstanceSummary(
    val name: String,
    val port: Int = 0,
    val running: Boolean = false,
    val logicalPrior: String? = null,
)

data class BackupSummary(
    val label: String,
    val alias: String? = null,
    val description: String? = null,
    val backupType: String = "",
    val createdAt: String = "",
    val isExpired: Boolean = false,
    val parent: String? = null,
)

data class BackupRequest(
    val instanceName: String,
    val alias: String? = null,
    val description: String? = null,
    val isFull: Boolean = true,
)

/**
 * Restore request.
 *
 * [confirm] is not decoration: a restore replaces the instance's data with the
 * contents of the chosen backup and silently discards everything written since.
 * Requiring an explicit acknowledgement in the payload (plus the exact backup
 * name) keeps a mis-click in a list of backups from destroying data, and makes
 * it impossible to trigger a restore from a generic retry of an unrelated POST.
 */
data class RestoreRequest(
    val instanceName: String,
    val backup: String,
    val confirm: Boolean = false,
)

fun Route.instanceRoutes(daemonHost: String, daemonPort: Int) {

    /**
     * List the instances owned by the signed-in user.
     *
     * This is the authoritative answer to "does this user have an instance yet?".
     * It used to be impossible — the daemon had no listing endpoint, so the
     * frontend remembered names in `localStorage` and probed them one by one;
     * with per-user ownership the question can finally be answered properly.
     */
    get("/instances") {
        val username = call.currentUsernameOrNull()
        if (username == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
            return@get
        }
        try {
            val (instances, busy) = withDaemonSessionForUser(
                daemonHost, daemonPort, username, instanceName = null
            ) { client ->
                // One round trip for both: the UI needs to know which instances
                // already have a backup running so it can disable "Back up".
                client.listInstances() to client.backupStatus().busyInstances
            }
            DaemonIdentities.registerInstances(username, instances.map { it.name })
            call.respond(mapOf(
                "instances" to instances.map {
                    InstanceSummary(it.name, it.port, it.running, it.logicalPrior)
                },
                "username" to username,
                "backupInProgress" to busy,
            ))
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    /**
     * Create an instance owned by the signed-in user.
     *
     * No instance is created by the deployment any more; the UI asks for a name
     * the first time a user signs in and has none. The name becomes the daemon's
     * (and PostgreSQL's) instance identifier, hence the conservative charset.
     */
    post("/instances/create") {
        val username = call.currentUsernameOrNull()
        if (username == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
            return@post
        }
        val req = call.receive<InstanceRequest>()
        val name = req.name.trim()
        if (!name.matches(Regex("^[A-Za-z0-9._-]{1,64}$"))) {
            call.respond(HttpStatusCode.BadRequest, mapOf(
                "error" to "Invalid instance name: use 1-64 characters " +
                    "(letters, digits, dot, underscore, hyphen)"
            ))
            return@post
        }
        logger.info("Creating instance '{}' for user '{}'", name, username)
        try {
            withDaemonSessionForUser(daemonHost, daemonPort, username, instanceName = null) { client ->
                client.createInstance(name)
            }
            DaemonIdentities.registerInstances(username, listOf(name))
            logger.info("Instance '{}' created for '{}'", name, username)
            call.respond(InstanceResponse("Instance created", name))
        } catch (e: Exception) {
            logger.error("Failed to create instance '{}' for '{}': {}", name, username, e.message, e)
            val (status, body) = errorPayload(e)
            call.respond(status, InstanceResponse(
                body["error"] ?: "Failed to create instance", name
            ))
        }
    }

    post("/instances/delete") {
        val username = call.currentUsernameOrNull()
        if (username == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
            return@post
        }
        val req = call.receive<InstanceActionRequest>()
        try {
            withDaemonSessionForUser(daemonHost, daemonPort, username, instanceName = null) { client ->
                client.deleteInstance(req.instanceName)
            }
            DaemonIdentities.forgetInstance(req.instanceName)
            call.respond(InstanceResponse("Instance deleted", req.instanceName))
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, InstanceResponse(
                body["error"] ?: "Failed to delete instance", req.instanceName
            ))
        }
    }

    /**
     * Start / shutdown / backup all act on the request's `instanceName`.
     * Connecting + disconnecting is what we use to ensure the instance is
     * reachable; we let `withDaemonSession` do the connect via its
     * `instanceName` parameter so any error during connect surfaces here.
     */
    post("/instances/start") {
        val username = call.currentUsernameOrNull()
        if (username == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
            return@post
        }
        val req = call.receive<InstanceActionRequest>()
        logger.info("Starting instance '{}'", req.instanceName)
        try {
            withDaemonSessionForUser(
                daemonHost, daemonPort, username, instanceName = req.instanceName
            ) { _ ->
                // Just connecting + (auto) disconnecting is the start-probe.
            }
            logger.info("Instance '{}' started successfully", req.instanceName)
            call.respond(InstanceResponse("Instance started", req.instanceName))
        } catch (e: Exception) {
            logger.error("Failed to start instance '{}': {}", req.instanceName, e.message, e)
            val (status, body) = errorPayload(e)
            call.respond(status, InstanceResponse(
                body["error"] ?: "Failed to start instance", req.instanceName
            ))
        }
    }

    post("/instances/shutdown") {
        val username = call.currentUsernameOrNull()
        if (username == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
            return@post
        }
        val req = call.receive<InstanceActionRequest>()
        logger.info("Shutting down instance '{}'", req.instanceName)
        try {
            // shutdownInstance does not require an active session — it is
            // an admin-level call.
            withDaemonSessionForUser(daemonHost, daemonPort, username, instanceName = null) { client ->
                client.shutdownInstance(req.instanceName)
            }
            logger.info("Instance '{}' shut down successfully", req.instanceName)
            call.respond(InstanceResponse("Instance shut down", req.instanceName))
        } catch (e: Exception) {
            logger.error("Failed to shut down instance '{}': {}", req.instanceName, e.message, e)
            val (status, body) = errorPayload(e)
            call.respond(status, InstanceResponse(
                body["error"] ?: "Failed to shut down instance", req.instanceName
            ))
        }
    }

    post("/instances/backup") {
        val username = call.currentUsernameOrNull()
        if (username == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
            return@post
        }
        val req = call.receive<BackupRequest>()
        instanceAccessProblem(call, req.instanceName)?.let { (status, message) ->
            call.respond(status, mapOf("error" to message))
            return@post
        }
        try {
            // Deliberately *not* connected to the instance: the daemon refuses to
            // back up an instance that has an open session (including the
            // caller's own), and it authorizes the operation from the token's
            // owner instead.
            val label = withDaemonSessionForUser(
                daemonHost, daemonPort, username, instanceName = null
            ) { client ->
                client.createBackup(req.isFull, req.instanceName, req.alias, req.description)
            }
            call.respond(mapOf("message" to "Backup created", "label" to label))
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    /**
     * List the backups available for an instance so the user can pick one
     * explicitly. Selecting a backup is a deliberate action: nothing in the
     * configuration or the instance start-up path decides it any more.
     */
    get("/instances/backups") {
        val instance = call.request.queryParameters["instance"]?.takeIf { it.isNotBlank() }
        if (instance == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf(
                "error" to "Missing 'instance' query parameter"))
            return@get
        }
        val username = call.currentUsernameOrNull()
        if (username == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
            return@get
        }
        instanceAccessProblem(call, instance)?.let { (status, message) ->
            call.respond(status, mapOf("error" to message))
            return@get
        }
        try {
            val (backups, busy) = withDaemonSessionForUser(
                daemonHost, daemonPort, username, instanceName = null
            ) { client ->
                client.peekBackups(instance) to client.backupStatus(instance).busy
            }
            call.respond(mapOf(
                "instance" to instance,
                // True while a backup/restore of this instance is running, so the
                // UI can keep "Back up" disabled instead of offering an action the
                // daemon will refuse.
                "busy" to (busy ?: false),
                "backups" to backups.map {
                    BackupSummary(
                        label = it.label,
                        alias = it.alias,
                        description = it.description,
                        backupType = it.backupType.name,
                        createdAt = it.createdAt.toString(),
                        isExpired = it.isExpired,
                        parent = it.parent,
                    )
                },
            ))
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    /**
     * Restore an instance to a named backup.
     *
     * This is the *only* way to switch the data an instance points at, and it
     * has to be requested explicitly with [RestoreRequest.confirm] set. The
     * configuration files no longer carry any "which snapshot should be used"
     * setting, because a setting like that turns into silent data loss: the next
     * start of an instance would roll back to whatever the file said.
     */
    post("/instances/restore") {
        val username = call.currentUsernameOrNull()
        if (username == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
            return@post
        }
        val req = call.receive<RestoreRequest>()
        if (req.backup.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing backup name"))
            return@post
        }
        if (!req.confirm) {
            call.respond(HttpStatusCode.PreconditionFailed, mapOf(
                "error" to "Restore not confirmed. Restoring replaces the current data of " +
                    "instance '${req.instanceName}' with backup '${req.backup}' and discards " +
                    "everything written since. Re-send with confirm=true to proceed."
            ))
            return@post
        }
        instanceAccessProblem(call, req.instanceName)?.let { (status, message) ->
            call.respond(status, mapOf("error" to message))
            return@post
        }
        logger.warn(
            "User '{}' is restoring instance '{}' to backup '{}'",
            username, req.instanceName, req.backup
        )
        try {
            // As with backups, no instance session is opened: the daemon refuses
            // to restore an instance that is in use.
            withDaemonSessionForUser(
                daemonHost, daemonPort, username, instanceName = null
            ) { client ->
                client.restoreBackup(req.instanceName, req.backup)
            }
            call.respond(mapOf(
                "message" to "Restored instance '${req.instanceName}' to backup '${req.backup}'"
            ))
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }
}
