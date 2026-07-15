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
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.data.database.DatabaseClient
import org.iotsplab.akiba.llm.agent.AgentSessionReconciler
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
import org.iotsplab.akiba.server.routes.projectRoutes
import org.iotsplab.akiba.server.routes.skillRoutes
import org.iotsplab.akiba.server.routes.moduleRoutes
import org.iotsplab.akiba.server.routes.updateRoutes
import org.iotsplab.akiba.server.routes.DAEMON_USER
import org.iotsplab.akiba.server.routes.DAEMON_PASSWORD
import java.util.concurrent.atomic.AtomicBoolean

object AkibaServer {
    private val logger: Logger = LogManager.getLogger("AkibaServer")

    /**
     * Comma-separated list of daemon instances to reconcile at startup
     * and on JVM shutdown. Override via the `AKIBA_AGENT_RECONCILE_INSTANCES`
     * environment variable when the deployment uses non-default instance
     * names; the default covers the standard Docker deployment (the
     * `akiba-instance` instance created by `dockerfile_needed/entrypoint.sh`).
     *
     * Set to the literal string "off" (or empty) to skip reconciliation
     * entirely — useful for tests / debugging.
     */
    private val reconcileInstances: List<String> by lazy {
        val raw = System.getenv("AKIBA_AGENT_RECONCILE_INSTANCES")
            ?.takeIf { it.isNotBlank() }
            ?: "akiba-instance"
        when (raw.trim().lowercase()) {
            "off", "false", "no", "0" -> emptyList()
            else -> raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    /**
     * Per-JVM guard so we don't install the shutdown hook twice if
     * `start` is somehow re-entered (defensive — `start` is supposed
     * to be called exactly once per JVM, but the cost of the guard
     * is one atomic boolean so it's cheap insurance).
     */
    private val shutdownHookInstalled = AtomicBoolean(false)

    fun start(config: org.iotsplab.akiba.server.ServerConfig) {
        val dbConfig = ServerDbConfig(
            config.dbHost, config.dbPort, config.dbName, config.dbUser, config.dbPassword
        )
        ServerDatabase.init(dbConfig)
        JwtService.init(config.jwtSecret)

        // ── Startup reconciliation ─────────────────────────────────────
        // Mark any session rows left in a non-terminal state by a
        // previous (possibly crashed) process as "closed" with a
        // "reconciled:startup" reason. Without this step the frontend's
        // status pill stays on "Running" / "Cancelling" forever when
        // the server was taken down by SIGKILL, OOM, or container
        // restart — the per-agent terminationHook never ran, so the
        // DB still shows the pre-kill state.
        //
        // Best-effort: DB / daemon may be slow to start, so a failure
        // here is logged and the server still starts. The
        // [AgentWatchdog] + [OrphanReaper] running inside this JVM
        // will catch whatever the startup pass missed.
        runStartupReconciliation(config)

        // ── JVM shutdown hook ─────────────────────────────────────────
        // Catches the SIGTERM case (entrypoint.sh's `cleanup` sends
        // SIGTERM and waits). Ktor's Netty engine does NOT run
        // user-supplied cleanup on SIGTERM by default, so a normally-
        // stopped container would also leave stale rows without this
        // hook. The hook is idempotent: a second invocation within
        // the same JVM (or after a successful first pass) is a no-op
        // thanks to the reconciler's in-process guard.
        if (shutdownHookInstalled.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(Thread({
                runShutdownReconciliation()
            }, "akiba-agent-reconciler-shutdown"))
        }


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
                    workflowRoutes(config.daemonHost, config.daemonPort)
                    scriptRoutes(config.daemonHost, config.daemonPort)
                    queryRoutes(config.daemonHost, config.daemonPort)
                    agentRoutes(config.daemonHost, config.daemonPort)
                    skillRoutes()
                    moduleRoutes()
                    projectRoutes(config.daemonHost, config.daemonPort)
                    llmConfigRoutes()
                    runtimeConfigRoutes()
                    updateRoutes()
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

    // ============================================================
    //  Startup-time / shutdown-time agent session reconciliation
    // ============================================================
    //
    // See the long comment on [AgentSessionReconciler] for the
    // design rationale.  In short: when the JVM is taken down
    // ungracefully (SIGKILL, OOM, container restart), the per-
    // agent terminationHook never runs, so the DB rows for the
    // in-flight sessions stay on `runtime_state='running' / 'cancelling'
    // / 'standby' / 'msghandle'` forever and the frontend's status
    // pill freezes on the wrong state.  These two helpers run the
    // startup pass (catches ungraceful exits) and the shutdown
    // pass (catches graceful SIGTERM exits that Ktor doesn't run
    // our cleanup for) respectively.
    //
    // Both helpers are best-effort: errors are caught and logged
    // at WARN.  The server starts (resp. stops) regardless of the
    // reconciler outcome.

    /**
     * Run the [AgentSessionReconciler] once per configured
     * instance, as early as possible in the startup sequence.
     *
     * Connects to each instance via the daemon, runs the
     * reconciler against every non-terminal session row, and
     * disconnects.  Connection failures are logged at WARN and
     * the remaining instances are still attempted — one bad
     * instance must not block startup.
     *
     * The first successful pass flips the reconciler's
     * in-process guard; subsequent calls in the same JVM
     * (e.g. from [AgentModule.startProcess]) are no-ops.
     */
    private fun runStartupReconciliation(config: ServerConfig) {
        val instances = reconcileInstances
        if (instances.isEmpty()) {
            logger.info("AkibaServer: agent session reconciliation disabled (AKIBA_AGENT_RECONCILE_INSTANCES=$instances)")
            return
        }
        logger.info(
            "AkibaServer: starting agent session reconciliation for instances=$instances " +
                "(daemon=${config.daemonHost}:${config.daemonPort})"
        )
        for (instance in instances) {
            try {
                reconcileOneInstance(
                    instance = instance,
                    daemonHost = config.daemonHost,
                    daemonPort = config.daemonPort,
                    reasonTag = "startup",
                )
            } catch (e: Exception) {
                logger.warn(
                    "AkibaServer: startup reconciliation for instance '$instance' " +
                        "failed: ${e.message}"
                )
            }
        }
    }

    /**
     * JVM-shutdown counterpart to [runStartupReconciliation].
     *
     * Intentionally does not use [ServerConfig] — at the time the
     * shutdown hook fires, the original `config` reference is
     * still alive (the hook is installed as a closure) but it is
     * safer to read the daemon address straight from the env-var
     * defaults the [ServerCommand] uses, so a refactor of
     * [ServerConfig] cannot accidentally drop the daemon address
     * from the hook's reach.
     *
     * Idempotent: the reconciler's in-process guard makes the
     * second invocation (startup followed by shutdown) a no-op.
     */
    private fun runShutdownReconciliation() {
        val instances = reconcileInstances
        if (instances.isEmpty()) return
        val host = System.getenv("AKIBA_DAEMON_HOST")
            ?: "127.0.0.1"
        val port = System.getenv("AKIBA_DAEMON_PORT")
            ?.toIntOrNull() ?: 31777
        logger.info(
            "AkibaServer: shutdown reconciliation pass for instances=$instances " +
                "(daemon=$host:$port)"
        )
        for (instance in instances) {
            try {
                reconcileOneInstance(
                    instance = instance,
                    daemonHost = host,
                    daemonPort = port,
                    reasonTag = "shutdown_hook",
                )
            } catch (e: Exception) {
                logger.warn(
                    "AkibaServer: shutdown reconciliation for instance '$instance' " +
                        "failed: ${e.message}"
                )
            }
        }
    }

    /**
     * Open a fresh [DatabaseClient] session, log in as the
     * daemon user, connect to [instance], run the
     * [AgentSessionReconciler], then disconnect + logout.  The
     * session is held only for the duration of the call so we
     * don't keep a daemon slot reserved for the lifetime of the
     * server.
     */
    private fun reconcileOneInstance(
        instance: String,
        daemonHost: String,
        daemonPort: Int,
        reasonTag: String,
    ) {
        val client = DatabaseClient(daemonHost, daemonPort)
        var connected = false
        try {
            client.login(DAEMON_USER, DAEMON_PASSWORD)
            client.connectToInstance(instance)
            connected = true
            val report = AgentSessionReconciler(
                agentDbClient = AgentDatabaseClient(client),
                reasonTag = reasonTag,
            ).reconcile()
            logger.info(
                "AkibaServer: reconciliation for instance '$instance' ($reasonTag) — " +
                    "scanned=${report.scanned}, reconciled=${report.reconciled}, " +
                    "failed=${report.failed}, deduped=${report.deduped}, " +
                    "dbError=${report.dbError ?: "<none>"}"
            )
        } finally {
            if (connected) {
                try { client.disconnectToInstance(instance) } catch (_: Exception) {}
            }
            try { client.logout() } catch (_: Exception) {}
        }
    }
}