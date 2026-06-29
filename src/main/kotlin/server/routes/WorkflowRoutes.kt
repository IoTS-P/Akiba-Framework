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

    fun startWorkflowAsync(instanceName: String, configName: String?, threads: Int, serverPort: Int): String {
        val workflowId = UUID.randomUUID().toString()
        val workflowLogDir = Path.of(System.getProperty("user.home"), ".akiba", "logs", "workflows")
        val workflowLogFile = workflowLogDir.resolve("$workflowId.log")
        val progressToken = ProgressManager.registerTask(workflowId, logFile = workflowLogFile)

        val status = WorkflowStatusEntry(id = workflowId, status = "running")
        workflowStatuses[workflowId] = status
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
            } catch (e: Exception) {
                logger.error("[workflow $workflowId] Error: {}", e.message, e)
                ProgressManager.updateStatus(progressToken, "failed")
                ProgressManager.setResult(progressToken, "Workflow failed: ${e.message}")
                workflowStatuses[workflowId] = workflowStatuses[workflowId]!!.copy(status = "failed")
            } finally {
                runningWorkflows.remove(workflowId)
                ProgressManager.finish(workflowId)
            }
        }
        runningWorkflows[workflowId] = job
        return workflowId
    }

    fun stopWorkflow(workflowId: String): Boolean {
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
        return true
    }

    fun getWorkflowStatus(workflowId: String): WorkflowStatusEntry? = workflowStatuses[workflowId]
    fun getAllWorkflowStatuses(): List<WorkflowStatusEntry> = workflowStatuses.values.toList()
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
            val workflowId = WorkflowManager.startWorkflowAsync(req.instanceName, req.configPath, req.threads, serverPort)
            call.respond(mapOf("workflowId" to workflowId, "message" to "Workflow started"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to start workflow: ${e.message}"))
        }
    }

    post("/workflow/stop/{workflowId}") {
        val workflowId = call.parameters["workflowId"] ?: ""
        val instance = call.requireInstanceHeader() ?: return@post
        WorkflowManager.stopWorkflow(workflowId)
        // Also cancel all active automated (non-chat) agent sessions for this instance
        try {
            withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                val sessions = agentDbClient.listSessions(limit = 100)
                for (s in sessions) {
                    if (s.status != "closed" && s.status != "error" &&
                        s.moduleName != null && s.moduleName != "chat") {
                        agentDbClient.updateSession(s.sessionId, status = "closed")
                        runCatching {
                            agentDbClient.setRuntimeState(
                                s.sessionId,
                                org.iotsplab.akiba.llm.agent.RuntimeState.CLOSED.wire(),
                                "workflow_stop",
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Non-critical; workflow is already stopped
        }
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
