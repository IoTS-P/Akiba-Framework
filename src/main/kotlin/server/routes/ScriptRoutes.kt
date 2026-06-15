package org.iotsplab.akiba.server.routes

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.data.database.AgentDatabaseClient.ScriptExecutionInfo
import org.iotsplab.akiba.data.database.AgentDatabaseClient.ScriptInfo
import org.iotsplab.akiba.data.database.DatabaseClient

data class CreateScriptRequest(
    val name: String,
    val description: String = "",
    val code: String,
    val language: String = "kotlin",
    val saveResult: Boolean = true,
    val maxOutputSize: Long = 10 * 1024 * 1024,
    val instanceName: String? = null
)

data class UpdateScriptRequest(
    val name: String? = null,
    val description: String? = null,
    val code: String? = null,
    val language: String? = null,
    val saveResult: Boolean? = null,
    val maxOutputSize: Long? = null
)

data class RunScriptRequest(
    val binaryIds: List<Int> = emptyList(),
    val parallel: Boolean = true
)

data class ScriptResponse(
    val id: Int,
    val name: String,
    val description: String?,
    val author: String?,
    val code: String?,
    val codeSize: Int?,
    val language: String?,
    val output: String?,
    val outputSize: Int?,
    val status: String?,
    val saveResult: Boolean?,
    val maxOutputSize: Long?,
    val createdAt: String?,
    val finishedAt: String?
)

data class ScriptExecutionResponse(
    val id: Int,
    val scriptId: Int,
    val binaryId: Int?,
    val status: String?,
    val output: String?,
    val errorMessage: String?,
    val startedAt: String?,
    val finishedAt: String?
)

data class ScriptRunResponse(
    val executionId: Int,
    val scriptId: Int,
    val binaryIds: List<Int>,
    val status: String,
    val message: String
)

object ScriptConfig {
    const val MAX_CODE_SIZE = 1024 * 1024L // 1MB
    const val MAX_OUTPUT_SIZE = 10 * 1024 * 1024L // 10MB
    const val DEFAULT_MAX_OUTPUT_SIZE = 10 * 1024 * 1024L // 10MB
}

fun Route.scriptRoutes(daemonHost: String, daemonPort: Int) {
    get("/scripts") {
        val instance = call.requireInstanceHeader() ?: return@get
        val limit = call.parameters["limit"]?.toIntOrNull() ?: 100
        val offset = call.parameters["offset"]?.toIntOrNull() ?: 0
        val query = call.parameters["query"]?.trim()?.lowercase()

        try {
            val scripts = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                agentDbClient.listScripts(limit, offset)
            }
            val filtered = if (!query.isNullOrBlank()) {
                scripts.filter { s ->
                    s.name.lowercase().contains(query) ||
                    (s.description?.lowercase()?.contains(query) == true) ||
                    (s.author?.lowercase()?.contains(query) == true)
                }
            } else {
                scripts
            }
            call.respond(mapOf("scripts" to filtered.map { it.toResponse() }))
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    get("/scripts/{id}") {
        val instance = call.requireInstanceHeader() ?: return@get
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Invalid ID"))
            return@get
        }

        try {
            val script = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                agentDbClient.getScript(id)
            }
            call.respond(script.toResponse())
        } catch (e: DatabaseClient.DatabaseDaemonException) {
            if (e.statusCode == io.ktor.http.HttpStatusCode.NotFound) {
                call.respond(io.ktor.http.HttpStatusCode.NotFound, mapOf("error" to "Script not found"))
            } else {
                val (status, body) = errorPayload(e)
                call.respond(status, body)
            }
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    post("/scripts") {
        val instance = call.requireInstanceHeader() ?: return@post
        val req = call.receive<CreateScriptRequest>()
        try {
            if (req.code.toByteArray().size > ScriptConfig.MAX_CODE_SIZE) {
                call.respond(io.ktor.http.HttpStatusCode.BadRequest,
                    mapOf("error" to "Code exceeds maximum size of ${ScriptConfig.MAX_CODE_SIZE} bytes"))
                return@post
            }

            val validationIssues = validateCode(req.code, req.language)
            if (validationIssues.isNotEmpty()) {
                call.respond(io.ktor.http.HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid code: ${validationIssues.joinToString("; ")}"))
                return@post
            }

            val maxOutputSize = if (req.maxOutputSize > ScriptConfig.MAX_OUTPUT_SIZE) {
                ScriptConfig.MAX_OUTPUT_SIZE
            } else {
                req.maxOutputSize
            }

            val script = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                val scriptId = agentDbClient.createScript(
                    name = req.name,
                    description = req.description,
                    code = req.code,
                    language = req.language,
                    saveResult = req.saveResult,
                    maxOutputSize = maxOutputSize
                )
                agentDbClient.getScript(scriptId)
            }
            call.respond(io.ktor.http.HttpStatusCode.Created, script.toResponse())
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    put("/scripts/{id}") {
        val instance = call.requireInstanceHeader() ?: return@put
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Invalid ID"))
            return@put
        }

        try {
            val req = call.receive<UpdateScriptRequest>()
            if (req.code != null && req.code.toByteArray().size > ScriptConfig.MAX_CODE_SIZE) {
                call.respond(io.ktor.http.HttpStatusCode.BadRequest,
                    mapOf("error" to "Code exceeds maximum size of ${ScriptConfig.MAX_CODE_SIZE} bytes"))
                return@put
            }

            val updated = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                // Verify script exists
                agentDbClient.getScript(id)
                agentDbClient.updateScript(
                    id, req.name, req.description, req.code, req.language,
                    req.saveResult, req.maxOutputSize
                )
                agentDbClient.getScript(id)
            }
            call.respond(updated.toResponse())
        } catch (e: DatabaseClient.DatabaseDaemonException) {
            if (e.statusCode == io.ktor.http.HttpStatusCode.NotFound) {
                call.respond(io.ktor.http.HttpStatusCode.NotFound, mapOf("error" to "Script not found"))
            } else {
                val (status, body) = errorPayload(e)
                call.respond(status, body)
            }
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    delete("/scripts/{id}") {
        val instance = call.requireInstanceHeader() ?: return@delete
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Invalid ID"))
            return@delete
        }

        try {
            withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                agentDbClient.deleteScript(id)
            }
            call.respond(mapOf("message" to "Script deleted"))
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    post("/scripts/{id}/run") {
        val instance = call.requireInstanceHeader() ?: return@post
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Invalid ID"))
            return@post
        }

        try {
            val req = call.receive<RunScriptRequest>()
            val binaryIds = req.binaryIds

            // Resolve script + create execution row(s) under one daemon session.
            data class Prepared(val script: AgentDatabaseClient.ScriptInfo, val executionIds: List<Int>)
            val prepared = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                val script = agentDbClient.getScript(id)
                val execs = if (binaryIds.isEmpty()) {
                    listOf(agentDbClient.createScriptExecution(script.id, null))
                } else if (binaryIds.size == 1 || !req.parallel) {
                    listOf(agentDbClient.createScriptExecution(script.id, binaryIds.first()))
                } else {
                    binaryIds.map { agentDbClient.createScriptExecution(script.id, it) }
                }
                Prepared(script, execs)
            }

            // Spawn the executors. Each one opens its own daemon session
            // because the parent route session has already been released.
            if (binaryIds.isEmpty()) {
                CoroutineScope(Dispatchers.Default).launch {
                    runCatching {
                        withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                            val agentDbClient = AgentDatabaseClient(dbClient)
                            executeScript(agentDbClient, prepared.executionIds.first(), prepared.script)
                        }
                    }
                }
                call.respond(ScriptRunResponse(
                    executionId = prepared.executionIds.first(),
                    scriptId = prepared.script.id,
                    binaryIds = emptyList(),
                    status = "pending",
                    message = "Script execution started"
                ))
            } else if (binaryIds.size == 1 || !req.parallel) {
                CoroutineScope(Dispatchers.Default).launch {
                    runCatching {
                        withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                            val agentDbClient = AgentDatabaseClient(dbClient)
                            executeScriptForBinary(agentDbClient, prepared.executionIds.first(), prepared.script, binaryIds.first())
                        }
                    }
                }
                call.respond(ScriptRunResponse(
                    executionId = prepared.executionIds.first(),
                    scriptId = prepared.script.id,
                    binaryIds = binaryIds,
                    status = "pending",
                    message = "Script execution started for single binary (serial)"
                ))
            } else {
                prepared.executionIds.zip(binaryIds).forEach { (execId, binaryId) ->
                    CoroutineScope(Dispatchers.Default).launch {
                        runCatching {
                            withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                                val agentDbClient = AgentDatabaseClient(dbClient)
                                executeScriptForBinary(agentDbClient, execId, prepared.script, binaryId)
                            }
                        }
                    }
                }
                call.respond(ScriptRunResponse(
                    executionId = prepared.executionIds.first(),
                    scriptId = prepared.script.id,
                    binaryIds = binaryIds,
                    status = "pending",
                    message = "Script execution started for ${binaryIds.size} binaries (parallel)"
                ))
            }
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    get("/scripts/{id}/executions") {
        val instance = call.requireInstanceHeader() ?: return@get
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Invalid ID"))
            return@get
        }

        try {
            val executions = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                // Verify script exists
                agentDbClient.getScript(id)
                agentDbClient.listScriptExecutions(id)
            }
            call.respond(mapOf("executions" to executions.map { it.toResponse() }))
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }

    get("/executions/{id}") {
        val instance = call.requireInstanceHeader() ?: return@get
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Invalid ID"))
            return@get
        }

        try {
            val execution = withDaemonSession(daemonHost, daemonPort, instance) { dbClient ->
                val agentDbClient = AgentDatabaseClient(dbClient)
                agentDbClient.getScriptExecution(id)
            }
            call.respond(execution.toResponse())
        } catch (e: DatabaseClient.DatabaseDaemonException) {
            if (e.statusCode == io.ktor.http.HttpStatusCode.NotFound) {
                call.respond(io.ktor.http.HttpStatusCode.NotFound, mapOf("error" to "Execution not found"))
            } else {
                val (status, body) = errorPayload(e)
                call.respond(status, body)
            }
        } catch (e: Exception) {
            val (status, body) = errorPayload(e)
            call.respond(status, body)
        }
    }
}

private fun validateCode(code: String, language: String): List<String> {
    val issues = mutableListOf<String>()
    if (code.isBlank()) {
        issues.add("Source code is empty")
        return issues
    }

    val hasClass = code.contains("class ")
    val hasInterface = code.contains("interface ")

    if (!hasClass && !hasInterface) {
        issues.add("Source must contain a class or interface definition")
    }

    return issues
}

private fun executeScript(agentDbClient: AgentDatabaseClient, executionId: Int, script: ScriptInfo) {
    try {
        agentDbClient.updateScriptExecution(executionId, null, "running", null)

        val output = "Script execution placeholder - requires full framework integration"
        agentDbClient.updateScriptOutput(
            script.id, output, "completed", script.maxOutputSize
        )
        agentDbClient.updateScriptExecution(executionId, output, "completed", null)

        if (script.saveResult == true) {
            agentDbClient.updateScriptOutput(
                script.id, output, "completed", script.maxOutputSize
            )
        }
    } catch (e: Exception) {
        val errorMsg = e.message ?: "Unknown error"
        try {
            agentDbClient.updateScriptOutput(script.id, "Error: $errorMsg", "failed", script.maxOutputSize)
            agentDbClient.updateScriptExecution(executionId, null, "failed", errorMsg)
        } catch (_: Exception) { }
    }
}

private fun executeScriptForBinary(agentDbClient: AgentDatabaseClient, executionId: Int, script: ScriptInfo, binaryId: Int) {
    try {
        agentDbClient.updateScriptExecution(executionId, null, "running", null)

        val output = "Script execution for binary $binaryId - placeholder"
        agentDbClient.updateScriptOutput(
            script.id, output, "completed", script.maxOutputSize
        )
        agentDbClient.updateScriptExecution(executionId, output, "completed", null)

        if (script.saveResult == true) {
            agentDbClient.updateScriptOutput(
                script.id, output, "completed", script.maxOutputSize
            )
        }
    } catch (e: Exception) {
        val errorMsg = e.message ?: "Unknown error"
        try {
            agentDbClient.updateScriptOutput(script.id, "Error: $errorMsg", "failed", script.maxOutputSize)
            agentDbClient.updateScriptExecution(executionId, null, "failed", errorMsg)
        } catch (_: Exception) { }
    }
}

private fun ScriptInfo.toResponse() = ScriptResponse(
    id = id,
    name = name,
    description = description,
    author = author,
    code = code,
    codeSize = codeSize,
    language = language,
    output = output,
    outputSize = outputSize,
    status = status,
    saveResult = saveResult,
    maxOutputSize = maxOutputSize,
    createdAt = createdAt,
    finishedAt = finishedAt
)

private fun ScriptExecutionInfo.toResponse() = ScriptExecutionResponse(
    id = id,
    scriptId = scriptId,
    binaryId = binaryId,
    status = status,
    output = output,
    errorMessage = errorMessage,
    startedAt = startedAt,
    finishedAt = finishedAt
)
