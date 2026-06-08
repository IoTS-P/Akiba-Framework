package org.iotsplab.akiba.server.routes

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
    private val workflowStatuses = ConcurrentHashMap<String, WorkflowStatusEntry>()

    fun startWorkflowAsync(instanceName: String, configName: String?, threads: Int): String {
        val workflowId = UUID.randomUUID().toString()
        val progressToken = ProgressManager.registerTask(workflowId)

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

                val progressUrl = "http://127.0.0.1/api/progress"
                // Note: the server port will be resolved by the subprocess
                // We pass it via environment variable for the ProgressReporter

                val pb = ProcessBuilder(scriptPath, "-c", configFile)
                pb.directory(File(System.getProperty("user.dir", ".")))
                pb.environment()["AKIBA_PROGRESS_URL"] = progressUrl
                pb.environment()["AKIBA_PROGRESS_TOKEN"] = progressToken
                pb.environment()["AKIBA_LLM_API_KEY"] = System.getenv("AKIBA_LLM_API_KEY") ?: ""
                pb.redirectErrorStream(true)

                val process = pb.start()
                process.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.let { logger.debug("[workflow $workflowId] $it") }
                    }
                }
                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    ProgressManager.updateStatus(progressToken, "completed")
                    ProgressManager.setResult(progressToken, "Workflow completed successfully")
                    workflowStatuses[workflowId] = workflowStatuses[workflowId]!!.copy(
                        status = "completed", progress = 1f
                    )
                } else {
                    ProgressManager.updateStatus(progressToken, "failed")
                    ProgressManager.setResult(progressToken, "Workflow failed with exit code $exitCode")
                    workflowStatuses[workflowId] = workflowStatuses[workflowId]!!.copy(
                        status = "failed"
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
        runningWorkflows[workflowId]?.cancel()
        runningWorkflows.remove(workflowId)
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

fun Route.workflowRoutes() {

    post("/workflow/start") {
        val req = runCatching { call.receive<StartWorkflowRequest>() }
            .getOrDefault(StartWorkflowRequest(instanceName = "akiba-instance"))
        try {
            val workflowId = WorkflowManager.startWorkflowAsync(req.instanceName, req.configPath, req.threads)
            call.respond(mapOf("workflowId" to workflowId, "message" to "Workflow started"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to start workflow: ${e.message}"))
        }
    }

    post("/workflow/stop/{workflowId}") {
        val workflowId = call.parameters["workflowId"] ?: ""
        if (WorkflowManager.stopWorkflow(workflowId)) {
            call.respond(mapOf("workflowId" to workflowId, "message" to "Workflow stopped"))
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Workflow not found"))
        }
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
            val ch = ProgressManager.getChannel(workflowId)
            if (ch != null) {
                for (msg in ch) {
                    write("data: $msg\n\n")
                    flush()
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

    // Read module log files for a specific binary in this workflow
    get("/workflow/file-logs/{workflowId}/{fileId}") {
        val fileId = call.parameters["fileId"] ?: ""
        val logsDir = Paths.get(System.getProperty("user.home"), ".akiba", "logs")
        val modules = mutableListOf<Map<String, String>>()

        // Look for log files in the logs directory
        if (Files.isDirectory(logsDir)) {
            try {
                val logDirs = Files.list(logsDir).filter { Files.isDirectory(it) }.toList()
                for (dir in logDirs) {
                    val binLogDir = dir.resolve(fileId)
                    if (Files.isDirectory(binLogDir)) {
                        val logFiles = Files.list(binLogDir)
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
            } catch (_: Exception) { }
        }
        call.respond(mapOf("modules" to modules))
    }

    get("/workflow/history") {
        call.respond(mapOf("workflows" to WorkflowManager.getAllWorkflowStatuses()))
    }
}
