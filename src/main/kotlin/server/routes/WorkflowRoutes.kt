package org.iotsplab.akiba.server.routes

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import org.iotsplab.akiba.managers.ProgramManager
import org.iotsplab.akiba.managers.WorkspaceManager
import org.iotsplab.akiba.managers.ConfigManager
import org.iotsplab.akiba.utils.Configs
import org.iotsplab.akiba.utils.SqlSource
import org.iotsplab.akiba.data.database.DatabaseClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class StartWorkflowRequest(
    val instanceName: String,
    val configPath: String? = null,
    val threads: Int = 1
)

data class WorkflowStatus(
    val id: String,
    val status: String,
    val progress: Float,
    val successCount: Int,
    val failCount: Int
)

object WorkflowManager {
    private val runningWorkflows = ConcurrentHashMap<String, Job>()
    private val workflowStatuses = ConcurrentHashMap<String, WorkflowStatus>()

    suspend fun startWorkflow(instanceName: String, configPath: String?, threads: Int): String {
        val workflowId = UUID.randomUUID().toString()

        try {
            ConfigManager.config = Configs(
                sqlSource = SqlSource(serverIP = "127.0.0.1", serverPort = 31777),
                general = org.iotsplab.akiba.utils.General(threads = threads)
            )
            DatabaseClient.login("akiba", "akiba")
            DatabaseClient.connectToInstance(instanceName)

            val job = CoroutineScope(Dispatchers.Default).launch {
                try {
                    workflowStatuses[workflowId] = WorkflowStatus(
                        id = workflowId,
                        status = "running",
                        progress = 0f,
                        successCount = 0,
                        failCount = 0
                    )

                    WorkspaceManager.initWorkspace()
                    ProgramManager.init()
                    ProgramManager.startProcess(WorkspaceManager.project)

                    workflowStatuses[workflowId] = workflowStatuses[workflowId]!!.copy(
                        status = "completed",
                        progress = 1f,
                        successCount = ProgramManager.successCount,
                        failCount = ProgramManager.failureCount
                    )
                } catch (e: Exception) {
                    workflowStatuses[workflowId] = workflowStatuses[workflowId]!!.copy(
                        status = "failed",
                        progress = 0f
                    )
                } finally {
                    DatabaseClient.disconnectToInstance(instanceName)
                    DatabaseClient.logout()
                    WorkspaceManager.close()
                }
            }
            runningWorkflows[workflowId] = job
            return workflowId
        } catch (e: Exception) {
            throw e
        }
    }

    fun stopWorkflow(workflowId: String): Boolean {
        runningWorkflows[workflowId]?.cancel()
        runningWorkflows.remove(workflowId)
        workflowStatuses[workflowId]?.let {
            workflowStatuses[workflowId] = it.copy(status = "cancelled")
        }
        return true
    }

    fun getWorkflowStatus(workflowId: String): WorkflowStatus? {
        return workflowStatuses[workflowId]
    }

    fun getAllWorkflowStatuses(): List<WorkflowStatus> {
        return workflowStatuses.values.toList()
    }
}

fun Route.workflowRoutes() {
    post("/workflow/start") {
        val req = call.receive<StartWorkflowRequest>()
        try {
            val workflowId = WorkflowManager.startWorkflow(
                req.instanceName,
                req.configPath,
                req.threads
            )
            call.respond(mapOf("workflowId" to workflowId, "message" to "Workflow started"))
        } catch (e: Exception) {
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to start workflow: ${e.message}"))
        }
    }

    post("/workflow/stop/{workflowId}") {
        val workflowId = call.parameters["workflowId"] ?: ""
        try {
            if (WorkflowManager.stopWorkflow(workflowId)) {
                call.respond(mapOf("workflowId" to workflowId, "message" to "Workflow stopped"))
            } else {
                call.respond(io.ktor.http.HttpStatusCode.NotFound,
                    mapOf("error" to "Workflow not found"))
            }
        } catch (e: Exception) {
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to stop workflow: ${e.message}"))
        }
    }

    get("/workflow/status/{workflowId}") {
        val workflowId = call.parameters["workflowId"] ?: ""
        try {
            val status = WorkflowManager.getWorkflowStatus(workflowId)
            if (status != null) {
                call.respond(status)
            } else {
                call.respond(io.ktor.http.HttpStatusCode.NotFound,
                    mapOf("error" to "Workflow not found"))
            }
        } catch (e: Exception) {
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError,
                mapOf("error" to e.message))
        }
    }

    get("/workflow/running") {
        try {
            val statuses = WorkflowManager.getAllWorkflowStatuses()
                .filter { it.status == "running" }
            call.respond(mapOf("workflows" to statuses))
        } catch (e: Exception) {
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError,
                mapOf("error" to e.message))
        }
    }

    get("/workflow/history") {
        try {
            val statuses = WorkflowManager.getAllWorkflowStatuses()
            call.respond(mapOf("workflows" to statuses))
        } catch (e: Exception) {
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError,
                mapOf("error" to e.message))
        }
    }
}
