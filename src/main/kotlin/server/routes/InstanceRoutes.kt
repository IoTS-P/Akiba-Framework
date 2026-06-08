package org.iotsplab.akiba.server.routes

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

fun Route.instanceRoutes(daemonHost: String, daemonPort: Int) {

    /**
     * List instances visible to the daemon.
     *
     * The akiba_db_daemon does not currently expose a "list all instances"
     * endpoint, so this route can only confirm whether a *specific* named
     * instance is reachable. Behavior:
     *
     *   - With `?probe=<name>`: try to connect+disconnect to `<name>`. If
     *     it succeeds the response includes the name, otherwise an empty
     *     list. Used by the frontend to verify that a remembered
     *     selection is still valid.
     *   - Without `?probe=`: respond with an empty list and a hint asking
     *     the caller to either probe a known name or create a new
     *     instance via `POST /instances/create`.
     *
     * No instance name is ever hard-coded here.
     */
    get("/instances") {
        val probe = call.parameters["probe"]?.takeIf { it.isNotBlank() }
        val visible = mutableListOf<String>()
        try {
            if (probe != null) {
                withDaemonSession(daemonHost, daemonPort, instanceName = null) { dbClient ->
                    runCatching {
                        dbClient.connectToInstance(probe)
                        dbClient.disconnectToInstance(probe)
                        visible.add(probe)
                    }
                }
                call.respond(mapOf("instances" to visible))
            } else {
                call.respond(mapOf(
                    "instances" to visible,
                    "hint" to "Pass ?probe=<name> to verify a specific instance, " +
                        "or POST /api/instances/create to create one."
                ))
            }
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    /**
     * Create / delete operate on an instance that may not exist yet (or is
     * about to disappear), so we cannot connect to it. Login-only session.
     */
    post("/instances/create") {
        val req = call.receive<InstanceRequest>()
        logger.info("Creating instance '{}'", req.name)
        try {
            withDaemonSession(daemonHost, daemonPort, instanceName = null) { dbClient ->
                dbClient.createInstance(req.name)
            }
            logger.info("Instance '{}' created successfully", req.name)
            call.respond(InstanceResponse("Instance created", req.name))
        } catch (e: Exception) {
            logger.error("Failed to create instance '{}': {}", req.name, e.message, e)
            val (status, body) = errorPayload(e)
            call.respond(status, InstanceResponse(
                body["error"] ?: "Failed to create instance", req.name
            ))
        }
    }

    post("/instances/delete") {
        val req = call.receive<InstanceActionRequest>()
        try {
            withDaemonSession(daemonHost, daemonPort, instanceName = null) { dbClient ->
                dbClient.deleteInstance(req.instanceName)
            }
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
        val req = call.receive<InstanceActionRequest>()
        logger.info("Starting instance '{}'", req.instanceName)
        try {
            withDaemonSession(daemonHost, daemonPort, instanceName = req.instanceName) { _ ->
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
        val req = call.receive<InstanceActionRequest>()
        logger.info("Shutting down instance '{}'", req.instanceName)
        try {
            // shutdownInstance does not require an active session — it is
            // an admin-level call.
            withDaemonSession(daemonHost, daemonPort, instanceName = null) { dbClient ->
                dbClient.shutdownInstance(req.instanceName)
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
        val req = call.receive<InstanceActionRequest>()
        try {
            withDaemonSession(daemonHost, daemonPort, instanceName = req.instanceName) { dbClient ->
                dbClient.createBackup(true, req.instanceName, null, null)
            }
            call.respond(mapOf("message" to "Backup created"))
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }
}
