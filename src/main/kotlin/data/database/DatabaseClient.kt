package org.iotsplab.akiba.data.database

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import io.ktor.client.plugins.websocket.receiveDeserialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.jackson.JacksonWebsocketContentConverter
import io.ktor.serialization.jackson.jackson
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import java.nio.file.Path
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.runBlocking
import org.iotsplab.akiba.managers.BinaryMetadata
import org.iotsplab.akiba.managers.ProgramManager
import org.iotsplab.akiba.managers.WorkspaceManager.globalLogger
import org.iotsplab.akiba.module.Log
import org.iotsplab.akiba.utils.AkibaJson

/**
 * Database daemon client.  Each instance holds its own URL, auth token and
 * locked-tables set, so concurrent multi-tenant use is safe (e.g. in the Akiba
 * HTTP server, where every request can carry a different instance/token).
 * CLI usage reuses a single global instance from [DatabaseClient.Companion.global].
 *
 * @param host  Daemon hostname or IP (e.g. "127.0.0.1")
 * @param port  Daemon port (e.g. 31777)
 * @param token Optional initial bearer token; usually set via [login] instead.
 */
class DatabaseClient(
    val host: String,
    val port: Int,
    var token: String? = null
) {
    /** Convenience computed property — avoids string-concat mistakes. */
    val urlHeader: String get() = "http://$host:$port"

    /** Tables currently locked by **this** client (per-instance, not global). */
    val lockedTables: MutableSet<String> = mutableSetOf()

    // ============================================================
    //  Shared (static) HTTP client — one connection pool per JVM
    // ============================================================

    companion object {
        /**
         * Daemon websocket close code for "conflicts with the current state,
         * retrying later works" — mirrors `AbstractWebsocketRoute.WS_CLOSE_CONFLICT`
         * in akiba_db_daemon (the two components share no code, only the wire).
         */
        private const val WS_CLOSE_CONFLICT: Short = 4409

        val httpClient: HttpClient = HttpClient {
            install(ContentNegotiation) {
                jackson {
                    enable(SerializationFeature.INDENT_OUTPUT)
                    // Maximum JSON string input length: 200 MB
                    factory.setStreamReadConstraints(
                        StreamReadConstraints.builder().maxStringLength(200 * 1024 * 1024).build()
                    )
                }
            }
            install(WebSockets) {
                pingInterval = 15.seconds
                contentConverter = JacksonWebsocketContentConverter(
                    jacksonObjectMapper()
                        .registerKotlinModule()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                )
            }
            install(HttpRequestRetry) {
                maxRetries = 3          // 3 retries maximum
                retryOnExceptionIf { _, cause ->
                    cause is java.io.IOException
                }
                exponentialDelay()
            }
            install(HttpTimeout) {
                // Without an explicit timeout, Ktor's HttpClient waits
                // **forever** for the daemon to respond.  That was the
                // root cause of the "agent appears to hang permanently
                // with no retry notice" bug: when the daemon stalled
                // (network half-open, daemon thread deadlock, extreme
                // GC pause, …), every `appendMessages` /
                // `updateSession` / `getMessages` call from inside a
                // manual-agent worker or runtime agent would block
                // forever, so the LLM retry-status write never
                // reached the DB and the frontend saw only silence.
                //
                // The daemon is local (127.0.0.1) so these budgets are
                // generous; failure means the daemon is genuinely
                // unresponsive, and the worker should surface that
                // instead of hanging.
                connectTimeoutMillis = 5_000        // 5s to establish TCP
                socketTimeoutMillis  = 30_000       // 30s between bytes
                requestTimeoutMillis = 60_000       // 60s for the whole call
            }
        }

        /**
         * Convenience global instance for CLI / single-task usage.
         *
         * Server code should **not** use this — it should create per-request
         * [DatabaseClient] instances inside [withDaemonSession] (see [org.iotsplab.akiba.server.routes.RouteHelpers]).
         */
        var global: DatabaseClient? = null
    }

    // ============================================================
    //  Exception
    // ============================================================

    class DatabaseDaemonException(
        val statusCode: HttpStatusCode?,
        val statusMsg: String? = null
    ) : Exception(
        // Surface a meaningful message to callers. Without this the default
        // Exception() ctor leaves `message` null, which has been bubbling up
        // to the HTTP layer as `{"error": null}` for every server route that
        // catches the exception generically. The status only appears when there
        // is no message of its own — appending "409 Conflict" to a sentence that
        // already explains the problem just reads as noise in the UI.
        listOfNotNull(
            statusMsg?.takeIf { it.isNotBlank() }
        ).joinToString(": ").ifBlank {
            statusCode?.let { "${it.value} ${it.description}" } ?: "Database daemon error"
        }
    )

    // ============================================================
    //  Connection helpers
    // ============================================================

    fun testConnection(): Boolean = runBlocking {
        try {
            val response = httpClient.get("$urlHeader/test")
            if (response.status == HttpStatusCode.OK) {
                Log.current.info("Database daemon connection successful. Reply: ${response.bodyAsText()}")
                return@runBlocking true
            } else {
                Log.current.error(
                    "Database daemon connection failed (${response.status}). Reply: ${response.bodyAsText()}"
                )
                return@runBlocking false
            }
        } catch (e: Exception) {
            Log.current.error("Database connection failed: ${e.message}")
            return@runBlocking false
        }
    }

    @Throws(DatabaseDaemonException::class)
    suspend fun post(path: String, body: Any?, putToken: Boolean = true): Pair<HttpStatusCode, String> {
        try {
            val response = httpClient.post("$urlHeader/$path") {
                contentType(ContentType.Application.Json)
                if (body != null)
                    setBody(body)
                if (putToken)
                    headers.append(HttpHeaders.Authorization,
                        token ?: throw DatabaseDaemonException(null, "No token provided"))
            }
            return response.status to response.bodyAsText()
        } catch (e: Exception) {
            Log.current.error("Database connection failed on $path: ${e.message}")
            throw DatabaseDaemonException(null, e.message)
        }
    }

    /*---------------------------------------------------------------------
     ------------------------- Database Requests --------------------------
     ---------------------------------------------------------------------*/

    /*   Queries   */

    @Throws(DatabaseDaemonException::class)
    fun getIdInSQL(sql: String): List<Long> = runBlocking {
        val response = post("/get/id/sql", mapOf("sql" to sql)).let {
            if (it.first == HttpStatusCode.OK)
                it.second
            else
                throw DatabaseDaemonException(it.first, it.first.description)
        }
        return@runBlocking AkibaJson.mapper.readValue<List<Long>>(response)
    }

    @Throws(DatabaseDaemonException::class)
    fun getIdPage(offset: Int, limit: Int): List<Long> = runBlocking {
        val response = post("/get/id/page", mapOf(
            "offset" to offset,
            "limit" to limit
        )).let {
            if (it.first == HttpStatusCode.OK)
                it.second
            else
                throw DatabaseDaemonException(it.first, it.first.description)
        }
        return@runBlocking AkibaJson.mapper.readValue<List<Long>>(response)
    }

    @Throws(DatabaseDaemonException::class)
    fun getIdCount(): Long = runBlocking {
        val response = post("/get/id/count", null).let {
            if (it.first == HttpStatusCode.OK)
                it.second
            else
                throw DatabaseDaemonException(it.first, it.first.description)
        }
        return@runBlocking AkibaJson.mapper.readValue<Long>(response)
    }

    @Throws(DatabaseDaemonException::class)
    fun getMetadata(id: Long): BinaryMetadata = runBlocking {
        val response = post("/get/metadata", id).let {
            if (it.first == HttpStatusCode.OK)
                it.second
            else
                throw DatabaseDaemonException(it.first, it.first.description)
        }
        return@runBlocking AkibaJson.mapper.readValue<BinaryMetadata>(response)
    }

    /**
     * Search binaries by name, id, architecture, format, or checksum.
     *
     * @param query Free-text search term; matches against original_path, arch, and format.
     *              If the query is a number, also matches by exact id.
     *              If the query is a 32-char hex string, also matches checksum exactly.
     * @return List of binary metadata maps, each containing id, name, originalPath,
     *         arch, format, compilerSpec, and checksum.
     */
    @Throws(DatabaseDaemonException::class)
    fun searchBinaries(query: String): List<Map<String, Any?>> = runBlocking {
        val response = post("/get/search", mapOf("query" to query)).let {
            if (it.first == HttpStatusCode.OK)
                it.second
            else
                throw DatabaseDaemonException(it.first, it.first.description)
        }
        @Suppress("UNCHECKED_CAST")
        return@runBlocking (AkibaJson.mapper.readValue(response) as? List<Map<String, Any?>>) ?: emptyList()
    }

    /**
     * Delete binaries (and their FK-cascaded rows) by id list.
     *
     * @param ids Binary ids to delete; unknown ids are skipped by the daemon.
     * @return The ids that were actually deleted.
     */
    @Throws(DatabaseDaemonException::class)
    fun deleteBinaries(ids: List<Long>): List<Long> = runBlocking {
        val response = post("/delete/binaries", mapOf("ids" to ids)).let {
            if (it.first == HttpStatusCode.OK)
                it.second
            else
                throw DatabaseDaemonException(it.first, it.first.description)
        }
        return@runBlocking AkibaJson.mapper.readValue<List<Long>>(response)
    }

    @Throws(DatabaseDaemonException::class)
    fun getModuleData(id: Long, tableName: String, columns: List<String>?): Map<String, Any?> = runBlocking {
        val data = mapOf(
            "tableName" to tableName,
            "id" to id,
            "columns" to columns
        )
        val response = post("/get/module_data", data).let {
            if (it.first == HttpStatusCode.OK)
                it.second
            else
                throw DatabaseDaemonException(it.first, it.first.description)
        }

        // TODO: Send type here and deserialize with our custom deserializer
        return@runBlocking jacksonObjectMapper()
            .registerModule(SimpleModule().addDeserializer(
                Map::class.java, ServerModDataDeserializer))
            .readValue<Map<String, Any?>>(response)
    }

    /*   Insertion   */

    private var md5CheckID: Int = 1

    @Throws(DatabaseDaemonException::class)
    fun checkMD5Duplicate(md5: String): Boolean = runBlocking {
        val response = post("/insert/check_md5", md5).let {
            if (it.first == HttpStatusCode.OK)
                it.second
            else
                throw DatabaseDaemonException(it.first, it.first.description)
        }
        return@runBlocking AkibaJson.mapper.readValue<Boolean>(response)
    }

    @Throws(DatabaseDaemonException::class)
    fun checkMD5Duplicate(path: Path): Boolean {
        val checksum = ProgramManager.getFileMD5Checksum(path)
        val isDuplicate: Boolean = checkMD5Duplicate(checksum)
        globalLogger.info("[$md5CheckID] Checking MD5 duplicate for $path ($checksum): $isDuplicate")
        md5CheckID += 1
        return isDuplicate
    }

    data class InsertData(
        val originalPath: String,
        val processedPath: String? = null,
        val checksum: String,
        val processedChecksum: String? = null,
        val size: Long,
        val processedSize: Long = -1,
        val loadProperties: String? = null,
        val arch: String,
        val format: String,
        val compilerSpec: String,
        // Provenance for files imported at runtime by an AkibaModule:
        //   sourceId     = id of the binary being analyzed when this file was imported
        //                  (null for top-level imports done by `ImportManager`)
        //   sourceModule = simple class name of the importing `AkibaModule`
        //                  (null for top-level imports done by `ImportManager`)
        val sourceId: Int? = null,
        val sourceModule: String? = null,
    )

    @Throws(DatabaseDaemonException::class)
    fun insertBinary(data: InsertData): Long = runBlocking {
        val response = post("/insert/insert_bin", data).let {
            if (it.first == HttpStatusCode.OK)
                it.second
            else
                throw DatabaseDaemonException(it.first, it.first.description)
        }
        return@runBlocking AkibaJson.mapper.readValue<Long>(response)
    }

    /*   Modules   */

    @Throws(DatabaseDaemonException::class)
    fun createModuleTable(tableName: String, columns: Map<String, String>) = runBlocking {
        LocalCacheDatabase.createTable(tableName, columns)
        post("/module/create_table", mapOf(
            "name" to tableName,
            "columns" to columns
        )).let {
            if (it.first != HttpStatusCode.OK)
                throw DatabaseDaemonException(it.first, it.first.description)
        }
    }

    @Throws(DatabaseDaemonException::class)
    fun createView(viewName: String, sql: String, overwrite: Boolean) = runBlocking {
        post("/module/create_view", mapOf(
            "viewName" to viewName,
            "viewSQL" to sql,
            "overwrite" to overwrite
        )).let {
            if (it.first != HttpStatusCode.OK)
                throw DatabaseDaemonException(it.first, it.first.description)
        }
    }

    @Throws(DatabaseDaemonException::class)
    fun tableLock(tableName: String) {
        runBlocking {
            post("/module/lock_table", mapOf(
                "tableName" to tableName
            )).let {
                if (it.first != HttpStatusCode.OK)
                    throw DatabaseDaemonException(it.first, it.first.description)
            }
            lockedTables.add(tableName)
        }
    }

    @Throws(DatabaseDaemonException::class)
    fun tableUnlock(tableName: String) {
        runBlocking {
            post("/module/unlock_table", mapOf(
                "tableName" to tableName
            )).let {
                if (it.first != HttpStatusCode.OK)
                    throw DatabaseDaemonException(it.first, it.first.description)
            }
            lockedTables.remove(tableName)
        }
    }

    @Throws(DatabaseDaemonException::class)
    fun updateData(tableName: String, id: Long, data: Map<String, Any?>) = runBlocking {
        val body = mapOf(
            "tableName" to tableName,
            "id" to id,
            "data" to data
        )

        post("/module/update", body).let {
            if (it.first != HttpStatusCode.OK) {
                // If send failed, save it into local database first
                LocalCacheDatabase.updateData(tableName, id, data)

                throw DatabaseDaemonException(it.first, it.first.description)
            }
        }
    }

    @Throws(DatabaseDaemonException::class)
    fun startTask(tableName: String, id: Long) = runBlocking {
        post("/module/start", mapOf(
            "tableName" to tableName,
            "id" to id
        )).let {
            if (it.first != HttpStatusCode.OK)
                throw DatabaseDaemonException(it.first, it.first.description)
        }
    }

    @Throws(DatabaseDaemonException::class)
    fun finishTask(tableName: String, id: Long) = runBlocking {
        post("/module/finish", mapOf(
            "tableName" to tableName,
            "id" to id
        )).let {
            if (it.first != HttpStatusCode.OK)
                throw DatabaseDaemonException(it.first, it.first.description)
        }
    }

    /*   Controls   */

    @Throws(DatabaseDaemonException::class)
    fun enableRoute(route: String) = runBlocking {
        post("/control/enable", mapOf(
            "route" to route
        )).let {
            if (it.first != HttpStatusCode.OK)
                throw DatabaseDaemonException(it.first, it.first.description)
        }
    }

    @Throws(DatabaseDaemonException::class)
    fun disableRoute(route: String) = runBlocking {
        post("/control/disable", mapOf(
            "route" to route
        )).let {
            if (it.first != HttpStatusCode.OK)
                throw DatabaseDaemonException(it.first, it.first.description)
        }
    }

    @Throws(DatabaseDaemonException::class)
    fun sendHeartbeat() = runBlocking {
        post("/heartbeat", null).let {
            if (it.first != HttpStatusCode.NoContent)
                throw DatabaseDaemonException(it.first, it.first.description)
        }
    }

    /*   PGInstances   */

    /** Result of a successful `/instance/login` call. */
    data class DaemonLoginInfo(
        val token: String,
        val username: String,
        val isAdmin: Boolean,
        /**
         * True when the daemon had no credential store yet and registered this
         * login as its first (administrator) account.
         */
        val bootstrapped: Boolean,
    )

    @Throws(DatabaseDaemonException::class)
    fun login(userName: String, password: String) {
        loginDetailed(userName, password)
    }

    /**
     * Log in and return the details of the issued session.
     *
     * The daemon verifies the credentials against its own credential store; an
     * unknown username and a wrong password are reported identically (HTTP 401).
     * Callers that own a credential source of their own (the HTTP server
     * verifies the password against its user table first) can use
     * [registerUser] to create the missing credential and retry.
     */
    @Throws(DatabaseDaemonException::class)
    fun loginDetailed(userName: String, password: String): DaemonLoginInfo = runBlocking {
        val response = post("/instance/login", mapOf(
            "username" to userName,
            "password" to password
        ), putToken = false).let {
            if (it.first == HttpStatusCode.OK)
                it.second
            else
                throw DatabaseDaemonException(it.first, it.first.description)
        }
        val body = AkibaJson.mapper.readValue<Map<String, Any?>>(response)
        val token = body["token"] as? String
            ?: throw DatabaseDaemonException(HttpStatusCode.InternalServerError, "Failed to get token")
        this@DatabaseClient.token = token
        DaemonLoginInfo(
            token = token,
            username = body["username"] as? String ?: userName,
            isAdmin = body["isAdmin"] as? Boolean ?: false,
            bootstrapped = body["bootstrapped"] as? Boolean ?: false,
        )
    }

    /**
     * Create a daemon credential.
     *
     * When this client already holds a token belonging to an administrator, that
     * token authorizes creating further users. The daemon always accepts the
     * very first credential without authorization (bootstrap) and accepts new
     * usernames from unauthenticated callers — a brand-new username owns no
     * instance and can therefore reach no data (instance ownership is enforced
     * on `/instance/connect`).
     *
     * @return true when the credential was created; false when the username
     *         already exists (in which case the caller should treat a failed
     *         [login] as a wrong password).
     */
    @Throws(DatabaseDaemonException::class)
    fun registerUser(userName: String, password: String, isAdmin: Boolean = false): Boolean = runBlocking {
        post("/instance/register", mapOf(
            "username" to userName,
            "password" to password,
            "isAdmin" to isAdmin,
        ), putToken = false).let {
            when (it.first) {
                HttpStatusCode.OK -> true
                HttpStatusCode.Conflict -> false
                else -> throw DatabaseDaemonException(it.first, it.first.description)
            }
        }
    }

    /**
     * Change the password of an existing credential (administrator token
     * required). Called by the framework's admin API so that a web password reset
     * also applies to the daemon copy.
     *
     * @return true when the credential was updated; false when the user does not
     *         exist in the daemon's store.
     */
    @Throws(DatabaseDaemonException::class)
    fun setUserPassword(userName: String, newPassword: String): Boolean = runBlocking {
        post("/instance/set-password", mapOf(
            "username" to userName,
            "password" to newPassword,
        )).let {
            when (it.first) {
                HttpStatusCode.OK -> true
                HttpStatusCode.NotFound -> false
                else -> throw DatabaseDaemonException(it.first, it.first.description)
            }
        }
    }

    /**
     * Lift an account's brute-force lock (administrator token required).
     *
     * The daemon applies the same policy as the framework's web login, so an
     * account unlocked on the web has to be unlocked here as well.
     *
     * @return true when the account exists and is unlocked.
     */
    @Throws(DatabaseDaemonException::class)
    fun unlockUser(userName: String): Boolean = runBlocking {
        post("/instance/unlock", mapOf("username" to userName)).let {
            when (it.first) {
                HttpStatusCode.OK -> true
                HttpStatusCode.NotFound -> false
                else -> throw DatabaseDaemonException(it.first, it.first.description)
            }
        }
    }

    /** An instance owned by the authenticated user, as reported by the daemon. */
    data class DaemonInstance(
        val name: String,
        val port: Int = 0,
        val running: Boolean = false,
        val logicalPrior: String? = null,
    )

    private data class InstanceListResponse(
        val username: String? = null,
        val isAdmin: Boolean = false,
        val instances: List<DaemonInstance> = emptyList(),
    )

    /**
     * List the instances owned by the authenticated user.
     *
     * The daemon never exposes other users' instances here, so this is the
     * authoritative answer to "does this user have any instance yet?".
     */
    @Throws(DatabaseDaemonException::class)
    fun listInstances(): List<DaemonInstance> = runBlocking {
        val response = post("/instance/list", mapOf<String, String>()).let {
            if (it.first == HttpStatusCode.OK)
                it.second
            else
                throw DatabaseDaemonException(it.first, it.first.description)
        }
        jacksonObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .readValue<InstanceListResponse>(response)
            .instances
    }

    /**
     * Obtain a fresh single-purpose credential bound to the authenticated user.
     *
     * Child processes spawned by the server (workflow runs, manual agent
     * workers) authenticate with the daemon on their own, and they must do so as
     * the instance's owner. Instead of copying the user's password into a
     * generated config file, the parent asks the daemon for a derived secret:
     * logging in with it yields the same identity, while leaving the real
     * password untouched and letting the daemon expire the secret.
     *
     * @param label Free-form label recorded with the credential (diagnostics).
     * @return the plaintext secret; it is returned exactly once by the daemon.
     */
    @Throws(DatabaseDaemonException::class)
    fun grantWorkerCredential(label: String = "worker"): String = runBlocking {
        val response = post("/instance/worker-grant", mapOf("label" to label)).let {
            if (it.first == HttpStatusCode.OK)
                it.second
            else
                throw DatabaseDaemonException(it.first, it.first.description)
        }
        AkibaJson.mapper.readValue<Map<String, String>>(response)["secret"]
            ?: throw DatabaseDaemonException(
                HttpStatusCode.InternalServerError, "Failed to obtain worker credential")
    }

    @Throws(DatabaseDaemonException::class)
    fun logout() = runBlocking {
        post("/instance/logout", null).let {
            if (it.first != HttpStatusCode.OK)
                throw DatabaseDaemonException(it.first, it.first.description)
        }
    }

    fun createInstance(instanceName: String) = runBlocking {
        globalLogger.info("[CreateInstance] Starting WebSocket connection")
        httpClient.webSocket(
            method = HttpMethod.Get,
            host = host,
            port = port,
            path = "/ws/instance/create"
        ) {
            send(Frame.Text(AkibaJson.mapper.writeValueAsString(mapOf(
                "token" to this@DatabaseClient.token,
                "instanceName" to instanceName
            ))))

            try {
                val createMsg = receiveDeserialized<Map<String, String>>()
                globalLogger.info("Creation of instance $instanceName succeeded, message: $createMsg")
            } catch (_: ClosedReceiveChannelException) {
                globalLogger.error("Creation failed")
            } finally {
                val closeMsg = closeReason.await()
                if (closeMsg?.knownReason != CloseReason.Codes.NORMAL)
                    throw DatabaseDaemonException(
                        HttpStatusCode.InternalServerError,
                        "Failed to create instance: ${closeMsg!!.message}"
                    )
                else
                    globalLogger.info("[CreateInstance] Connection closed normally")
            }
        }
    }

    @Throws(DatabaseDaemonException::class)
    fun connectToInstance(instanceName: String) = runBlocking {
        post("/instance/connect", mapOf(
            "instanceName" to instanceName,
        )).let {
            if (it.first != HttpStatusCode.OK)
                throw DatabaseDaemonException(it.first, it.first.description)
        }
    }

    @Throws(DatabaseDaemonException::class)
    fun disconnectToInstance(instanceName: String) = runBlocking {
        post("/instance/disconnect", mapOf(
            "instanceName" to instanceName,
        )).let {
            if (it.first != HttpStatusCode.OK)
                throw DatabaseDaemonException(it.first, it.first.description)
        }
    }

    @Throws(DatabaseDaemonException::class)
    fun shutdownInstance(instanceName: String) = runBlocking {
        post("/instance/shutdown", mapOf(
            "instanceName" to instanceName,
        )).let {
            if (it.first != HttpStatusCode.OK)
                throw DatabaseDaemonException(it.first, it.first.description)
        }
    }

    @Throws(DatabaseDaemonException::class)
    fun deleteInstance(instanceName: String) = runBlocking {
        post("/instance/delete", mapOf(
            "instanceName" to instanceName,
        )).let {
            if (it.first != HttpStatusCode.OK)
                throw DatabaseDaemonException(it.first, it.first.description)
        }
    }

    /*   Backups   */

    @Throws(DatabaseDaemonException::class)
    fun createBackup(
        isFull: Boolean,
        instanceName: String,
        alias: String?,
        description: String?
    ): String = runBlocking {
        var label: String? = null

        httpClient.webSocket(
            method = HttpMethod.Get,
            host = host,
            port = port,
            path = "/ws/backup/create"
        ) {
            send(Frame.Text(AkibaJson.mapper.writeValueAsString(mapOf(
                "isFull" to isFull,
                "token" to this@DatabaseClient.token,
                "instance" to instanceName,
                "alias" to alias,
                "description" to description
            ))))

            try {
                label = receiveDeserialized<Map<String, String>>()["label"] ?: run {
                    throw DatabaseDaemonException(
                        HttpStatusCode.InternalServerError, "Failed to backup"
                    )
                }
            } catch (_: ClosedReceiveChannelException) {
                val closeMsg = closeReason.await()!!
                throw DatabaseDaemonException(
                    statusFor(closeMsg), "Failed to backup: ${closeMsg.message}"
                )
            } catch (e: Exception) {
                // Log it: this branch used to swallow the cause, which is exactly
                // how "the daemon said no" became an unexplainable 500.
                globalLogger.warn("Backup request for '$instanceName' failed: ${e.message}", e)
                throw DatabaseDaemonException(
                    HttpStatusCode.InternalServerError,
                    "Exception occurred while requesting for backup: ${e.message}"
                )
            }
        }

        return@runBlocking label!!
    }

    /** Instances of the caller that currently have a backup/restore in flight. */
    data class BackupStatus(
        val busyInstances: List<String> = emptyList(),
        /** Set only when a single instance was asked about. */
        val busy: Boolean? = null,
    )

    /**
     * Ask the daemon whether a backup or restore is running for [instanceName]
     * (or, with no argument, which of the caller's instances are busy).
     *
     * See `Backups.BackupStatus` in akiba_db_daemon. The UI uses this to disable
     * "Back up" while a run is in progress — including runs it did not start —
     * instead of letting the user click into a refusal.
     */
    @Throws(DatabaseDaemonException::class)
    fun backupStatus(instanceName: String? = null): BackupStatus = runBlocking {
        post("/backup/status", mapOf("instance" to instanceName)).let {
            if (it.first != HttpStatusCode.OK)
                throw DatabaseDaemonException(it.first, it.first.description)
            AkibaJson.mapper.readValue(it.second)
        }
    }

    data class BackupNode(
        val label: String,
        val isExpired: Boolean,
        val alias: String?,
        val description: String?,
        val parent: String?,
        val backupType: BackupType,
        val createdAt: OffsetDateTime,
        val children: MutableList<String> = mutableListOf()
    )
    enum class BackupType { FULL, DIFF, INCR }

    class BackupNodeDeserializer: JsonDeserializer<BackupNode>() {
        @Throws(IllegalArgumentException::class)
        override fun deserialize(
            parser: JsonParser,
            context: DeserializationContext
        ): BackupNode {
            val node = parser.readValueAsTree<JsonNode>()

            return BackupNode(
                label = node["label"].textValue(),
                isExpired = node["isExpired"].booleanValue(),
                alias = node["alias"]?.textValue(),
                description = node["description"]?.textValue(),
                parent = node["parent"]?.textValue(),
                backupType = BackupType.valueOf(node["backupType"].textValue()),
                createdAt = OffsetDateTime.ofInstant(
                    Instant.ofEpochMilli(node["createdAt"].numberValue().toLong()),
                    ZoneOffset.systemDefault()),
                children = node["children"] ?.map { it.textValue() }?.toMutableList() ?: mutableListOf()
            )
        }
    }

    @Throws(DatabaseDaemonException::class)
    fun peekBackups(instanceName: String): List<BackupNode> = runBlocking {
        post("/backup/peek", instanceName).let {
            if (it.first != HttpStatusCode.OK)
                throw DatabaseDaemonException(it.first, it.first.description)
            else
                return@runBlocking jacksonObjectMapper().registerModule(
                    SimpleModule().addDeserializer(
                        BackupNode::class.java,
                        BackupNodeDeserializer()
                    )
                ).readValue<List<BackupNode>>(it.second)
        }
    }

    @Throws(DatabaseDaemonException::class)
    fun restoreBackup(instanceName: String, aliasOrLabel: String) = runBlocking {
        httpClient.webSocket(
            method = HttpMethod.Get,
            host = host,
            port = port,
            path = "/ws/backup/restore"
        ) {
            send(Frame.Text(AkibaJson.mapper.writeValueAsString(mapOf(
                "token" to this@DatabaseClient.token,
                "instance" to instanceName,
                "aliasOrLabel" to aliasOrLabel
            ))))

            try {
                val restoreMsg = receiveDeserialized<Map<String, String>>()
                globalLogger.info("Restoration of $instanceName to $aliasOrLabel succeeded, message: $restoreMsg")
            } catch (_: ClosedReceiveChannelException) {
                globalLogger.error("Restoration failed")
            } finally {
                val closeMsg = closeReason.await()
                if (closeMsg?.knownReason != CloseReason.Codes.NORMAL)
                    throw DatabaseDaemonException(
                        statusFor(closeMsg), "Failed to restore: ${closeMsg!!.message}"
                    )
            }
        }
    }

    /**
     * HTTP status for a daemon websocket close code.
     *
     * 4409 is the daemon's "conflicts with the current state, retrying later
     * works" code (see `AbstractWebsocketRoute.WS_CLOSE_CONFLICT` in
     * akiba_db_daemon) — the case a second backup/restore of the same instance
     * hits. Reporting it as a conflict, rather than as an internal error, is what
     * lets the UI say "a backup is already running" instead of showing a bare 500.
     */
    private fun statusFor(reason: CloseReason?): HttpStatusCode =
        if (reason?.code == WS_CLOSE_CONFLICT) HttpStatusCode.Conflict
        else HttpStatusCode.InternalServerError
}
