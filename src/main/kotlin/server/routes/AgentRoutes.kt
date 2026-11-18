package org.iotsplab.akiba.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Every agent route, assembled from one file per domain.
 *
 * Each domain keeps its own file; this function is the table of contents, and the one
 * place the server looks to know which routes exist.
 */
fun Route.agentRoutes(daemonHost: String, daemonPort: Int) {
    agentSessionRoutes(daemonHost, daemonPort)
    agentStreamRoutes(daemonHost, daemonPort)
    agentHumanRoutes(daemonHost, daemonPort)
    agentChatRoutes(daemonHost, daemonPort)

    // A resident worker asks for its next turn here and reports the outcome after.
    workerTurnRoutes()
}
