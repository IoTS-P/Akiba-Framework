package org.iotsplab.akiba.server.routes

import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.data.database.DatabaseClient

private val logger: Logger = LogManager.getLogger("QueryRoutes")

data class QueryRequest(val sql: String, val instanceName: String? = null)
data class QueryResponse(val columns: List<String>, val rows: List<List<Any?>>)

fun Route.queryRoutes(daemonHost: String, daemonPort: Int) {
    post("/query") {
        val req = call.receive<QueryRequest>()
        if (req.sql.isBlank()) {
            call.respond(io.ktor.http.HttpStatusCode.BadRequest,
                mapOf("error" to "SQL query is empty"))
            return@post
        }

        val sqlLower = req.sql.lowercase().trim()
        if (sqlLower.startsWith("insert") || sqlLower.startsWith("update") ||
            sqlLower.startsWith("delete") || sqlLower.startsWith("drop") ||
            sqlLower.startsWith("create") || sqlLower.startsWith("alter")) {
            call.respond(io.ktor.http.HttpStatusCode.Forbidden,
                mapOf("error" to "Only SELECT queries are allowed"))
            return@post
        }

        try {
            // Allow the client to override the instance via the request body
            // (legacy field), otherwise use the global selection from the
            // X-Akiba-Instance header.
            val instance = req.instanceName ?: call.instanceHeader() ?: run {
                call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf(
                    "error" to "Missing instance selection. Please select an instance first."
                ))
                return@post
            }
            logger.info("Executing query on '{}': {}", instance, req.sql)
            val result = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                dbClient.getIdInSQL(req.sql)
            }
            logger.info("Query returned {} results", result.size)
            call.respond(QueryResponse(
                columns = listOf("id"),
                rows = result.map { listOf(it as Any?) }
            ))
        } catch (e: Exception) {
            logger.error("Query failed on '{}': {}", req.instanceName ?: call.instanceHeader(), e.message, e)
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    get("/query/history") {
        // History tracking is not yet implemented on the daemon side. Return
        // an empty list so the frontend can render without crashing.
        call.respond(mapOf("history" to emptyList<Any>()))
    }
}
