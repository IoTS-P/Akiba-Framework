package org.iotsplab.akiba.server.routes

import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.iotsplab.akiba.data.database.DatabaseClient
import org.iotsplab.akiba.managers.ConfigManager
import org.iotsplab.akiba.utils.Configs
import org.iotsplab.akiba.utils.SqlSource

data class InstanceRequest(val name: String)
data class InstanceActionRequest(val instanceName: String)
data class InstanceResponse(val message: String, val instanceName: String? = null)

fun Route.instanceRoutes(daemonHost: String, daemonPort: Int) {

    suspend fun withDaemonConnection(block: suspend () -> Any): Any {
        ConfigManager.config = Configs(sqlSource = SqlSource(serverIP = daemonHost, serverPort = daemonPort))
        DatabaseClient.urlHeader = "http://$daemonHost:$daemonPort"
        return block()
    }

    get("/instances") {
        try {
            call.respond(mapOf("instances" to listOf<String>()))
        } catch (e: Exception) {
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError,
                mapOf("error" to e.message))
        }
    }

    post("/instances/create") {
        val req = call.receive<InstanceRequest>()
        try {
            withDaemonConnection {
                DatabaseClient.login("akiba", "akiba")
                DatabaseClient.createInstance(req.name)
                DatabaseClient.logout()
            }
            call.respond(InstanceResponse("Instance created", req.name))
        } catch (e: Exception) {
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError,
                InstanceResponse("Failed to create instance: ${e.message}"))
        }
    }

    post("/instances/delete") {
        val req = call.receive<InstanceActionRequest>()
        try {
            withDaemonConnection {
                DatabaseClient.login("akiba", "akiba")
                DatabaseClient.deleteInstance(req.instanceName)
                DatabaseClient.logout()
            }
            call.respond(InstanceResponse("Instance deleted", req.instanceName))
        } catch (e: Exception) {
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError,
                InstanceResponse("Failed to delete instance: ${e.message}"))
        }
    }

    post("/instances/start") {
        val req = call.receive<InstanceActionRequest>()
        try {
            withDaemonConnection {
                DatabaseClient.login("akiba", "akiba")
                DatabaseClient.connectToInstance(req.instanceName)
                DatabaseClient.disconnectToInstance(req.instanceName)
                DatabaseClient.logout()
            }
            call.respond(InstanceResponse("Instance started", req.instanceName))
        } catch (e: Exception) {
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError,
                InstanceResponse("Failed to start instance: ${e.message}"))
        }
    }

    post("/instances/shutdown") {
        val req = call.receive<InstanceActionRequest>()
        try {
            withDaemonConnection {
                DatabaseClient.login("akiba", "akiba")
                DatabaseClient.shutdownInstance(req.instanceName)
                DatabaseClient.logout()
            }
            call.respond(InstanceResponse("Instance shut down", req.instanceName))
        } catch (e: Exception) {
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError,
                InstanceResponse("Failed to shut down instance: ${e.message}"))
        }
    }

    post("/instances/backup") {
        val req = call.receive<InstanceActionRequest>()
        try {
            withDaemonConnection {
                DatabaseClient.login("akiba", "akiba")
                DatabaseClient.createBackup(true, req.instanceName, null, null)
                DatabaseClient.logout()
            }
            call.respond(mapOf("message" to "Backup created"))
        } catch (e: Exception) {
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to create backup: ${e.message}"))
        }
    }
}
