package org.iotsplab.akiba.server.routes

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.config.LLMKeyFileStore
import org.iotsplab.akiba.managers.WorkspaceManager
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val logger: Logger = LogManager.getLogger("WorkflowRoutes")
private val mapper = jacksonObjectMapper()

data class StartWorkflowRequest(
    val instanceName: String,
    val configPath: String? = null,
    val threads: Int = 1
)

data class WorkflowStatusEntry(
    val id: String,
    val status: String,
    val progress: Float = 0f,
    val successCount: Int = 0,
    val failCount: Int = 0
)

object WorkflowManager {
    private val runningWorkflows = ConcurrentHashMap<String, Job>()
    private val runningProcesses = ConcurrentHashMap<String, Process>()
    private val workflowStatuses = ConcurrentHashMap<String, WorkflowStatusEntry>()

    /**
     * Daemon connection info captured at workflow start so that
     * [stopWorkflow] and the post-exit reconciler can reach the
     * DB without relying on the HTTP route that triggered the stop.
     */
    private data class DaemonInfo(val host: String, val port: Int, val instance: String)
    private val workflowDaemons = ConcurrentHashMap<String, DaemonInfo>()

    fun startWorkflowAsync(
        instanceName: String,
        configName: String?,
        threads: Int,
        serverPort: Int,
        daemonHost: String,
        daemonPort: Int,
    ): String {
        val workflowId = UUID.randomUUID().toString()
        val workflowLogDir = Path.of(System.getProperty("user.home"), ".akiba", "logs", "workflows")
        val workflowLogFile = workflowLogDir.resolve("$workflowId.log")
        val progressToken = ProgressManager.registerTask(workflowId, logFile = workflowLogFile)

        val status = WorkflowStatusEntry(id = workflowId, status = "running")
        workflowStatuses[workflowId] = status
        workflowDaemons[workflowId] = DaemonInfo(daemonHost, daemonPort, instanceName)
        ProgressManager.onProgress(progressToken, "Workflow started for instance '$instanceName'")

        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                ProgressManager.onProgress(progressToken, "Loading saved configuration '$configName'...")

                // Use the saved config file directly from ~/.akiba/user_configs/<instance>/<configName>.json
                val configDir = configDirForInstance(instanceName)
                val configFile = if (configName != null) {
                    val savedPath = configDir.resolve("${configName}.json")
                    if (Files.exists(savedPath)) {
                        savedPath.toAbsolutePath().toString()
                    } else {
                        ProgressManager.onProgress(progressToken, "Config '$configName' not found at $savedPath")
                        throw RuntimeException("Configuration '$configName' not found. Save it first in Settings.")
                    }
                } else {
                    throw RuntimeException("No configuration selected")
                }

                ProgressManager.onProgress(progressToken, "Launching workflow subprocess with config: $configName")

                // Find and run the start script
                val scriptPath = findAkibaScript()

                val progressUrl = "http://127.0.0.1:$serverPort/api/progress"

                val pb = ProcessBuilder(scriptPath, "-c", "${configFile}@/main")
                pb.directory(File(System.getProperty("user.dir", ".")))

                // Fully reconstruct the environment map — some JVM/OS combos
                // do not propagate modifications to individual env vars made
                // via the returned map.  Copying everything explicitly avoids
                // this issue.
                val fullEnv = mutableMapOf<String, String>()
                // fullEnv.putAll(System.getenv())
                fullEnv["AKIBA_PROGRESS_URL"] = progressUrl
                fullEnv["AKIBA_PROGRESS_TOKEN"] = progressToken
                // Propagate the workflow run id so agent sessions created by
                // module runs inside this subprocess can tag themselves with
                // it (read by AgentModule.startProcess; inherited by child
                // sessions at the daemon level).
                fullEnv["AKIBA_WORKFLOW_ID"] = workflowId

                // Resolve LLM API key from the saved config if present.
                // The frontend sets "llm.apiKeyEnv" to the first 8 hex chars
                // of the UUID for the saved key entry in ~/.akiba/llm_keys.json.
                // We set that env var with the actual API key value.
                // If the user provided a literal "llm.apiKey", the child reads
                // it from the JSON config directly — nothing to do here.
                try {
                    val configRoot = mapper.readTree(java.io.File(configFile))
                    val llmNode = configRoot.at("/main/llm")
                    if (!llmNode.isMissingNode && !llmNode.isNull) {
                        val apiKeyEnv = llmNode.get("apiKeyEnv")?.textValue() ?: ""
                        if (apiKeyEnv.isNotBlank()) {
                            // Frontend prefixes with "K_" to ensure POSIX-valid env var name.
                            // Strip it before matching against the UUID prefix.
                            val keyPrefix = apiKeyEnv.removePrefix("K_")
                            val storedKey = LLMKeyFileStore.load()
                                .firstOrNull { it.id.startsWith(keyPrefix) }?.apiKey
                            if (!storedKey.isNullOrBlank()) {
                                fullEnv[apiKeyEnv] = storedKey
                                logger.info("Passing LLM key via env '$apiKeyEnv'")
                            } else {
                                logger.warn("No LLM key found for prefix '$keyPrefix'")
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to resolve LLM API key: {}", e.message)
                }

                // Write the fully reconstructed environment back to the
                // ProcessBuilder.  Doing a full clear + putAll guarantees
                // that every entry is actually passed to the child.
                pb.environment().clear()
                pb.environment().putAll(fullEnv)

                pb.redirectErrorStream(true)

                val process = pb.start()
                runningProcesses[workflowId] = process
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            line?.let {
                                logger.debug("[workflow $workflowId] $it")
                                ProgressManager.onProgress(progressToken, it)
                            }
                        }
                    }
                } finally {
                    runningProcesses.remove(workflowId)
                }
                val exitCode = process.waitFor()

                // Count success/fail from the persistent log file
                // ([FILE:id] markers come via HTTP POST, not stdout, so they're
                // only in the log file written by ProgressManager.onProgress)
                var successCount = 0
                var failCount = 0
                try {
                    val logLines = Files.readAllLines(workflowLogFile)
                    for (logLine in logLines) {
                        val m = Regex("""\[FILE:(\d+)\]\s*(completed|failed)""").find(logLine)
                        if (m != null) {
                            when (m.groupValues[2]) {
                                "completed" -> successCount++
                                "failed" -> failCount++
                            }
                        }
                    }
                } catch (_: Exception) { }

                if (exitCode == 0) {
                    ProgressManager.updateStatus(progressToken, "completed")
                    ProgressManager.setResult(progressToken, "Workflow completed successfully")
                    workflowStatuses[workflowId] = workflowStatuses[workflowId]!!.copy(
                        status = "completed", progress = 1f,
                        successCount = successCount, failCount = failCount
                    )
                } else {
                    ProgressManager.updateStatus(progressToken, "failed")
                    ProgressManager.setResult(progressToken, "Workflow failed with exit code $exitCode")
                    workflowStatuses[workflowId] = workflowStatuses[workflowId]!!.copy(
                        status = "failed",
                        successCount = successCount, failCount = failCount
                    )
                }

                // NOTE: do NOT call reconcileAgentSessions here.
                // When the process exits normally, all agents should
                // already be in a terminal state, so cleanup is
                // unnecessary.  When the process crashes, the
                // AgentSessionReconciler (startup-time) will clean
                // up stale sessions on the next server start.
                //
                // Calling reconcileAgentSessions here would create a
                // new daemon session via withDaemonSession →
                // connectToInstance, which can force-disconnect the
                // workflow process's existing daemon session before
                // the daemon has detected the process's death.  This
                // breaks in-flight mailbox operations (including
                // child→parent wake-up messages) and also creates a
                // race with stopWorkflow's own reconcileAgentSessions
                // call when the user clicks Stop.
            } catch (e: Exception) {
                logger.error("[workflow $workflowId] Error: {}", e.message, e)
                ProgressManager.updateStatus(progressToken, "failed")
                ProgressManager.setResult(progressToken, "Workflow failed: ${e.message}")
                workflowStatuses[workflowId] = workflowStatuses[workflowId]!!.copy(status = "failed")
            } finally {
                runningWorkflows.remove(workflowId)
                workflowDaemons.remove(workflowId)
                ProgressManager.finish(workflowId)
            }
        }
        runningWorkflows[workflowId] = job
        return workflowId
    }

    fun stopWorkflow(workflowId: String): Boolean {
        // Capture daemon info BEFORE cancelling the coroutine —
        // the coroutine's finally block removes workflowDaemons[workflowId],
        // and we need the info for reconcileAgentSessions below.
        val daemonInfo = workflowDaemons[workflowId]

        // Cancel the coroutine Job first. This stops *this* route's
        // `process.inputStream` reader loop (the one that fills
        // ProgressManager with stdout lines). The subprocess running the
        // actual `akiba` work is handled separately below — cancelling
        // the route's Job does not stop the subprocess.
        runningWorkflows[workflowId]?.cancel()
        runningWorkflows.remove(workflowId)

        // Destroy the subprocess
        runningProcesses[workflowId]?.let { process ->
            try {
                // Try SIGINT (Unix) first for a graceful shutdown
                process.pid().let { pid ->
                    if (pid > 0) {
                        try {
                            Runtime.getRuntime().exec(arrayOf("kill", "-INT", pid.toString()))
                                .waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                            logger.info("Sent SIGINT to workflow subprocess (pid $pid)")
                        } catch (_: Exception) {
                            // Fallback: SIGTERM via destroy()
                            process.destroy()
                            logger.info("Sent SIGTERM to workflow subprocess (pid $pid)")
                        }
                    }
                }
                // Wait up to 10 seconds for the child to drain gracefully.
                if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    logger.warn(
                        "Workflow subprocess (pid ${process.pid()}) did not exit " +
                            "within 10s of SIGINT; escalating to SIGTERM"
                    )
                    process.destroy()
                    if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        logger.warn(
                            "Workflow subprocess (pid ${process.pid()}) still alive " +
                                "after SIGTERM; force killing"
                        )
                        process.destroyForcibly()
                    }
                } else {
                    logger.info("Workflow subprocess (pid ${process.pid()}) exited cleanly")
                }
            } catch (_: Exception) {
                process.destroyForcibly()
            }
            runningProcesses.remove(workflowId)
        }

        workflowStatuses[workflowId]?.let {
            workflowStatuses[workflowId] = it.copy(status = "cancelled")
        }

        // The subprocess has been killed. Reconcile any agent sessions
        // that are still non-terminal — the process did not get a chance
        // to close them cleanly.  Scoped to sessions owned by THIS
        // workflow (exact workflow_id attribution) so parallel
        // workflows are not affected.
        if (daemonInfo != null) {
            reconcileAgentSessions(daemonInfo, workflowId, "workflow_stopped")
        }

        workflowDaemons.remove(workflowId)
        return true
    }

    fun getWorkflowStatus(workflowId: String): WorkflowStatusEntry? = workflowStatuses[workflowId]
    fun getAllWorkflowStatuses(): List<WorkflowStatusEntry> = workflowStatuses.values.toList()

    /**
     * Close non-terminal agent sessions owned by the workflow process
     * that has just been stopped.
     *
     * Session→workflow attribution is EXACT: every module-spawned
     * session carries `agent_sessions.workflow_id` (propagated through
     * the `AKIBA_WORKFLOW_ID` env var set at workflow launch, and
     * inherited by child sessions at the daemon level), so cleanup only
     * touches rows whose `workflow_id` equals [workflowId].  Stopping
     * one workflow can therefore never flip another PARALLEL workflow's
     * live agents to `closed` — the previous creation-window heuristic
     * did exactly that whenever two workflows overlapped in time.
     *
     * Legacy rows with a NULL `workflow_id` (interactive sessions and
     * anything spawned before the column existed) are deliberately left
     * alone; the startup-time AgentSessionReconciler reaps them once
     * their `updated_at` goes stale.
     *
     * The [reasonTag] is embedded in the `closing_reason` column so
     * an operator can tell the trigger (e.g. `"workflow_stopped"`).
     *
     * Failures are logged at WARN and never thrown — the workflow
     * itself has already exited/stopped; the session cleanup is
     * best-effort.
     */
    private fun reconcileAgentSessions(
        info: DaemonInfo,
        workflowId: String,
        reasonTag: String,
    ) {
        try {
            withDaemonSession(info.host, info.port, info.instance) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                val sessions = agentDbClient.listSessions(
                    status = null,
                    binaryId = null,
                    moduleName = null,
                    workflowId = workflowId,
                    limit = 500,
                    offset = 0,
                    parentSessionId = "ALL",
                )
                val nonTerminalStates = setOf(
                    org.iotsplab.akiba.llm.agent.RuntimeState.RUNNING.wire(),
                    org.iotsplab.akiba.llm.agent.RuntimeState.STANDBY.wire(),
                    org.iotsplab.akiba.llm.agent.RuntimeState.MSGHANDLE.wire(),
                    org.iotsplab.akiba.llm.agent.RuntimeState.CANCELLING.wire(),
                )
                var closed = 0
                for (s in sessions) {
                    val rt = s.runtimeState?.lowercase() ?: s.status.lowercase()
                    if (rt !in nonTerminalStates) continue
                    // Skip chat sessions — they are user-interaction
                    // sessions, not workflow sessions.
                    if (s.moduleName == null || s.moduleName == "chat") continue
                    val reason = "$reasonTag:$workflowId:at=${System.currentTimeMillis()}"
                    try {
                        agentDbClient.setRuntimeState(
                            s.sessionId,
                            org.iotsplab.akiba.llm.agent.RuntimeState.CLOSED.wire(),
                            reason,
                        )
                        runCatching {
                            agentDbClient.updateSession(s.sessionId, status = "closed")
                        }
                        closed++
                    } catch (e: Exception) {
                        logger.warn(
                            "reconcileAgentSessions[$workflowId]: failed to close " +
                                "session ${s.sessionId}: ${e.message}"
                        )
                    }
                }
                if (closed > 0) {
                    logger.info(
                        "reconcileAgentSessions[$workflowId]: closed $closed stale " +
                            "agent session(s) ($reasonTag, workflow_id scoped)"
                    )
                }
            }
        } catch (e: Exception) {
            logger.warn(
                "reconcileAgentSessions[$workflowId]: daemon connection failed, " +
                    "cannot clean up agent sessions: ${e.message}"
            )
        }
    }

}

// Reuse the script finder from FileRoutes
private fun findAkibaScript(): String {
    try {
        val source = org.iotsplab.akiba.Main::class.java.protectionDomain.codeSource
        val loc = source.location.toURI()
        val jarFile = File(loc)
        if (jarFile.name.endsWith(".jar")) {
            val distRoot = jarFile.parentFile.parentFile
            val script = File(distRoot, "bin/akiba")
            if (script.isFile) return script.absolutePath
        }
    } catch (_: Exception) { }
    val cwd = System.getProperty("user.dir", ".")
    val candidates = listOf(
        File(cwd, "bin/akiba"),
        File(cwd, "../bin/akiba"),
        File(cwd, "akiba_framework/bin/akiba"),
    )
    for (candidate in candidates) {
        if (candidate.isFile) return candidate.absolutePath
    }
    return "akiba"
}

fun Route.workflowRoutes(daemonHost: String, daemonPort: Int) {

    get("/workflow/projects") {
        try {
            val projectDirectory = call.currentUserProjectDirectory()
            call.respond(mapOf("projects" to WorkspaceManager.listGhidraProjects(projectDirectory)))
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    post("/workflow/start") {
        val req = runCatching { call.receive<StartWorkflowRequest>() }
            .getOrDefault(StartWorkflowRequest(instanceName = "akiba-instance"))
        try {
            val serverPort = call.request.local.serverPort
            val workflowId = WorkflowManager.startWorkflowAsync(
                req.instanceName, req.configPath, req.threads, serverPort,
                daemonHost, daemonPort,
            )
            call.respond(mapOf("workflowId" to workflowId, "message" to "Workflow started"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to start workflow: ${e.message}"))
        }
    }

    post("/workflow/stop/{workflowId}") {
        val workflowId = call.parameters["workflowId"] ?: ""
        WorkflowManager.stopWorkflow(workflowId)
        call.respond(mapOf("workflowId" to workflowId, "message" to "Workflow stopped"))
    }

    get("/workflow/status/{workflowId}") {
        val workflowId = call.parameters["workflowId"] ?: ""
        val status = WorkflowManager.getWorkflowStatus(workflowId)
        if (status != null) call.respond(status)
        else call.respond(HttpStatusCode.NotFound, mapOf("error" to "Workflow not found"))
    }

    // SSE stream for workflow logs
    get("/workflow/stream/{workflowId}") {
        val workflowId = call.parameters["workflowId"] ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing workflowId"))
            return@get
        }
        if (ProgressManager.getStatus(workflowId) == "unknown") {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Workflow not found"))
            return@get
        }
        call.response.header(HttpHeaders.ContentType, "text/event-stream")
        call.response.header("Cache-Control", "no-cache")
        call.response.header("X-Accel-Buffering", "no")
        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            val flow = ProgressManager.getFlow(workflowId)
            if (flow != null) {
                coroutineScope {
                    val collectJob = launch {
                        flow.collect { msg ->
                            write("data: $msg\n\n")
                            flush()
                        }
                    }
                    // Keep collecting until task is no longer running
                    while (ProgressManager.getStatus(workflowId) == "running") {
                        delay(100)
                    }
                    collectJob.cancel()
                }
            }
            write("data: [STATUS] ${ProgressManager.getStatus(workflowId)}\n\n")
            write("data: [RESULT] ${ProgressManager.getResult(workflowId) ?: ""}\n\n")
            write("data: [DONE]\n\n")
            flush()
        }
    }

    get("/workflow/running") {
        val statuses = WorkflowManager.getAllWorkflowStatuses().filter { it.status == "running" }
        call.respond(mapOf("workflows" to statuses))
    }

    // Read persistent workflow log file
    get("/workflow/logs/{workflowId}") {
        val workflowId = call.parameters["workflowId"] ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing workflowId"))
            return@get
        }
        val logFile = Path.of(System.getProperty("user.home"), ".akiba", "logs", "workflows", "$workflowId.log")
        if (Files.exists(logFile)) {
            call.respond(mapOf("logs" to Files.readAllLines(logFile)))
        } else {
            call.respond(mapOf("logs" to emptyList<String>()))
        }
    }

    // Read module log files for a specific binary
    get("/workflow/file-logs/{workflowId}/{fileId}") {
        val fileId = call.parameters["fileId"] ?: ""
        val logsDir = Paths.get(System.getProperty("user.home"), ".akiba", "logs")
        val modules = mutableListOf<Map<String, String>>()

        if (Files.isDirectory(logsDir)) {
            try {
                Files.list(logsDir).filter { Files.isDirectory(it) }.use { stream ->
                    stream.toList().forEach { projectDir ->
                        val candidates = listOf(
                            projectDir.resolve(fileId),                               // running: <project>/<id>/
                            projectDir.resolve("success").resolve(fileId),              // completed: <project>/success/<id>/
                            projectDir.resolve("failed").resolve(fileId),               // failed: <project>/failed/<id>/
                            projectDir.resolve("runtime_error").resolve(fileId)         // error: <project>/runtime_error/<id>/
                        )
                        for (candidateDir in candidates) {
                            if (Files.isDirectory(candidateDir)) {
                                val logFiles = Files.list(candidateDir)
                                    .filter { it.toString().endsWith(".log") }
                                    .sorted()
                                    .toList()
                                for (logFile in logFiles) {
                                    val moduleName = logFile.fileName.toString().removeSuffix(".log")
                                    val logContent = try { Files.readString(logFile) } catch (_: Exception) { "" }
                                    modules.add(mapOf(
                                        "name" to moduleName,
                                        "status" to "completed",
                                        "log" to logContent
                                    ))
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) { }
        }
        call.respond(mapOf("modules" to modules))
    }

    get("/workflow/history") {
        call.respond(mapOf("workflows" to WorkflowManager.getAllWorkflowStatuses()))
    }
}
