package org.iotsplab.akiba.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.serialization.jackson.*
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.RollingFileAppender
import org.apache.logging.log4j.core.appender.rolling.DefaultRolloverStrategy
import org.apache.logging.log4j.core.appender.rolling.SizeBasedTriggeringPolicy
import org.apache.logging.log4j.core.layout.PatternLayout
import org.iotsplab.akiba.llm.agent.ModelContextLengthService
import org.iotsplab.akiba.managers.WorkspaceManager
import org.iotsplab.akiba.server.db.ServerDbConfig
import org.iotsplab.akiba.server.db.ServerDatabase
import org.iotsplab.akiba.server.security.JwtService
import org.iotsplab.akiba.server.routes.authRoutes
import org.iotsplab.akiba.server.routes.instanceRoutes
import org.iotsplab.akiba.server.routes.fileRoutes
import org.iotsplab.akiba.server.routes.workflowRoutes
import org.iotsplab.akiba.server.routes.scriptRoutes
import org.iotsplab.akiba.server.routes.queryRoutes
import org.iotsplab.akiba.server.routes.agentRoutes
import org.iotsplab.akiba.server.routes.llmConfigRoutes
import org.iotsplab.akiba.server.routes.runtimeConfigRoutes

object AkibaServer {
    private val logger: Logger = LogManager.getLogger("AkibaServer")

    fun start(config: org.iotsplab.akiba.server.ServerConfig) {
        val dbConfig = ServerDbConfig(
            config.dbHost, config.dbPort, config.dbName, config.dbUser, config.dbPassword
        )
        ServerDatabase.init(dbConfig)
        JwtService.init(config.jwtSecret)
        ModelContextLengthService.start()

        initServerLogger(config)

        embeddedServer(Netty, config.port, config.host) {
            install(ContentNegotiation) {
                jackson {
                    registerKotlinModule()
                    disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                }
            }

            install(WebSockets)

            // Catch 413 (Request Entity Too Large) and return a JSON error body
            // so the frontend gets a structured response instead of a raw Netty
            // hang-up.
            install(StatusPages) {
                exception<PayloadTooLargeException> { call, cause ->
                    logger.warn("413: {}", cause.message ?: "Request entity too large")
                    call.respond(
                        HttpStatusCode.PayloadTooLarge,
                        mapOf("error" to "Upload too large. Please upload fewer or smaller files per request.")
                    )
                }
            }

            routing {
                get("/") {
                    call.respondText("Akiba Server is running")
                }

                route("/api") {
                    get("/health") {
                        call.respond(mapOf("status" to "ok"))
                    }

                    authRoutes()
                    instanceRoutes(config.daemonHost, config.daemonPort)
                    fileRoutes(config.daemonHost, config.daemonPort)
                    workflowRoutes()
                    scriptRoutes(config.daemonHost, config.daemonPort)
                    queryRoutes(config.daemonHost, config.daemonPort)
                    agentRoutes(config.daemonHost, config.daemonPort)
                    llmConfigRoutes()
                    runtimeConfigRoutes()
                }
            }
        }.start(wait = true)
    }

    private fun initServerLogger(config: ServerConfig) {
        val context = LogManager.getContext(false) as LoggerContext
        val log4jConfig = context.configuration

        val logDir = WorkspaceManager.logRootDir
        val logFile = logDir.resolve("server.log").toAbsolutePath().toString()
        val logFilePattern = logDir.resolve("server-%i.log").toAbsolutePath().toString()

        val layout = PatternLayout.newBuilder()
            .withPattern("%d %-5level [%t] %c{1.} - %msg%n")
            .withConfiguration(log4jConfig)
            .build()

        val triggerPolicy = SizeBasedTriggeringPolicy.createPolicy("300KB")
        val rolloverStrategy = DefaultRolloverStrategy.newBuilder()
            .withMax(config.serverLogMaxFiles.toString())
            .withFileIndex("min")
            .build()

        val appender = RollingFileAppender.newBuilder()
            .setName("ServerRollingFile")
            .withFileName(logFile)
            .withFilePattern(logFilePattern)
            .setLayout(layout)
            .withPolicy(triggerPolicy)
            .withStrategy(rolloverStrategy)
            .setConfiguration(log4jConfig)
            .build()

        val logLevel = Level.getLevel(config.serverLogLevel)
        if (logLevel != Level.OFF) {
            appender.start()
            log4jConfig.addAppender(appender)

            val rootLogger = log4jConfig.rootLogger
            rootLogger.addAppender(appender, logLevel, null)
            rootLogger.level = Level.ALL

            context.updateLoggers()
        }
    }

    fun stop() {
        ServerDatabase.close()
    }
}