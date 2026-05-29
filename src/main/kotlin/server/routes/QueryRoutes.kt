package org.iotsplab.akiba.server.routes

import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.iotsplab.akiba.data.database.DatabaseClient

data class QueryRequest(val sql: String, val instanceName: String? = null)
data class QueryResponse(val columns: List<String>, val rows: List<List<Any?>>)

fun Route.queryRoutes() {
    post("/query") {
        val req = call.receive<QueryRequest>()
        if (req.sql.isBlank()) {
            call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "SQL query is empty"))
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
            val result = DatabaseClient.getIdInSQL(req.sql)
            call.respond(QueryResponse(
                columns = listOf("id"),
                rows = result.map { listOf(it as Any?) }
            ))
        } catch (e: Exception) {
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError,
                mapOf("error" to "Query failed: ${e.message}"))
        }
    }

    get("/query/history") {
        try {
            call.respond(mapOf("message" to "Query history not yet implemented"))
        } catch (e: Exception) {
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError,
                mapOf("error" to e.message))
        }
    }
}
