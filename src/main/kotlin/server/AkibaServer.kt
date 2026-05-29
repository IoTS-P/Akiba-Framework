package org.iotsplab.akiba.server

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.serialization.jackson.*
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.iotsplab.akiba.server.db.ServerDbConfig
import org.iotsplab.akiba.server.db.ServerDatabase
import org.iotsplab.akiba.server.security.JwtService
import org.iotsplab.akiba.server.routes.authRoutes
import org.iotsplab.akiba.server.routes.instanceRoutes
import org.iotsplab.akiba.server.routes.fileRoutes
import org.iotsplab.akiba.server.routes.workflowRoutes
import org.iotsplab.akiba.server.routes.scriptRoutes
import org.iotsplab.akiba.server.routes.queryRoutes

object AkibaServer {
    fun start(config: org.iotsplab.akiba.server.ServerConfig) {
        val dbConfig = ServerDbConfig(
            config.dbHost, config.dbPort, config.dbName, config.dbUser, config.dbPassword
        )
        ServerDatabase.init(dbConfig)
        JwtService.init(config.jwtSecret)

        embeddedServer(Netty, config.port, config.host) {
            install(ContentNegotiation) {
                jackson {
                    registerKotlinModule()
                    disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                }
            }

            install(WebSockets)

            routing {
                get("/") {
                    call.respondText("Akiba Server is running")
                }

                get("/api/health") {
                    call.respond(mapOf("status" to "ok"))
                }

                authRoutes()

                route("/api") {
                    instanceRoutes(config.daemonHost, config.daemonPort)
                    fileRoutes()
                    workflowRoutes()
                    scriptRoutes()
                    queryRoutes()
                }
            }
        }.start(wait = true)
    }

    fun stop() {
        ServerDatabase.close()
    }
}