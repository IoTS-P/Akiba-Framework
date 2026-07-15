package org.iotsplab.akiba.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respond
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.data.database.DatabaseClient
import org.iotsplab.akiba.server.db.UserDao
import org.iotsplab.akiba.server.security.JwtService
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val logger: Logger = LogManager.getLogger("RouteHelpers")

/**
 * Helpers for HTTP routes that need to talk to the akiba_db_daemon.
 *
 * Each call to [withDaemonSession] creates a **new**, independent
 * [DatabaseClient] instance — login, connect to the requested instance,
 * run the caller's block, then disconnect + logout.
 *
 * The daemon only allows one active session per instance at a time, so
 * we serialize access with a per-instance lock. Requests targeting
 * **different** instances run in parallel; requests to the **same**
 * instance wait for the previous session to release.
 */

/** Username used for daemon access. Currently a single hard-coded service account. */
const val DAEMON_USER = "akiba"
const val DAEMON_PASSWORD = "akiba"

/**
 * Header that the frontend uses to pin every request to a specific
 * akiba_db_daemon instance. The Vue app stores the user's selection in
 * Pinia and attaches it through an axios interceptor.
 */
const val INSTANCE_HEADER = "X-Akiba-Instance"

/**
 * Read the [INSTANCE_HEADER] from the request. Returns null if the
 * header is missing or blank.
 */
fun ApplicationCall.instanceHeader(): String? =
    request.header(INSTANCE_HEADER)?.takeIf { it.isNotBlank() }

/**
 * Read the [INSTANCE_HEADER]; if missing, respond with 400 and return null.
 * Use this from routes that strictly require an instance.
 */
suspend fun ApplicationCall.requireInstanceHeader(): String? {
    val name = instanceHeader()
    if (name == null) {
        respond(HttpStatusCode.BadRequest, mapOf(
            "error" to "Missing instance selection. " +
                "Please select an instance in the UI before making this request."
        ))
    }
    return name
}

/** Current authenticated username, falling back to daemon user for legacy/no-auth calls. */
fun ApplicationCall.currentUsernameOrDefault(): String {
    val authHeader = request.header("Authorization") ?: return DAEMON_USER
    if (!authHeader.startsWith("Bearer ")) return DAEMON_USER
    val token = authHeader.substring(7)
    val session = JwtService.validateToken(token) ?: return DAEMON_USER
    return UserDao.getUserById(session.userId)?.username ?: DAEMON_USER
}

/** Safely-mapped current username that is always safe to embed into a path segment. */
fun ApplicationCall.currentSafeUsername(): String = safePathSegment(currentUsernameOrDefault())

/**
 * Absolute directory under `ghidra_projects/<username>/` that is the per-user
 * Ghidra project root. This is independent of the global `projectConf.projectRoot`
 * because each user gets an isolated directory tree for their projects.
 */
fun ApplicationCall.currentUserGhidraProjectsRoot(): Path {
    val username = currentSafeUsername()
    return Path.of("ghidra_projects", username).toAbsolutePath().normalize()
}

/** `~/.akiba/logs/<username>/` — fixed per-user logs directory. */
fun ApplicationCall.currentUserLogsRoot(): Path {
    val username = currentSafeUsername()
    return Path.of(System.getProperty("user.home"), ".akiba", "logs", username)
        .toAbsolutePath()
        .normalize()
}

/** `~/.akiba/workspace/<username>/` — fixed per-user workspace directory. */
fun ApplicationCall.currentUserWorkspaceRoot(): Path {
    val username = currentSafeUsername()
    return Path.of(System.getProperty("user.home"), ".akiba", "workspace", username)
        .toAbsolutePath()
        .normalize()
}

/** Directory name under the configured Ghidra project root for this request's user. */
fun ApplicationCall.currentUserProjectDirectory(): Path =
    Path.of(currentSafeUsername())

private fun safePathSegment(value: String): String =
    value.replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .take(64)
        .ifBlank { DAEMON_USER }

/**
 * Per-instance locks. The daemon only allows one active database session
 * per instance, so concurrent requests targeting the same instance must be
 * serialized. Requests to *different* instances run in parallel.
 */
private val instanceLocks = ConcurrentHashMap<String, ReentrantLock>()

private fun lockFor(instanceName: String): ReentrantLock =
    instanceLocks.computeIfAbsent(instanceName) { ReentrantLock() }

/**
 * Run [block] inside a fresh [DatabaseClient] session, serialized per
 * instance so the daemon never sees two concurrent connections to the
 * same instance.
 *
 * The block receives the logged-in [DatabaseClient] as its argument.
 * When [instanceName] is non-null, the client is connected to that
 * instance before the block runs. Cleanup (disconnect + logout) happens
 * in a `finally` block.
 *
 * Pass `instanceName = null` only for operations that legitimately work
 * without a database session (login probe, create/delete instance).
 */
fun <T> withDaemonSession(
    daemonHost: String,
    daemonPort: Int,
    instanceName: String? = null,
    serialize: Boolean = true,
    block: (DatabaseClient) -> T
): T {
    val lock = if (serialize && instanceName != null) lockFor(instanceName) else null

    fun doSession(): T {
        val dbClient = DatabaseClient(daemonHost, daemonPort)
        dbClient.login(DAEMON_USER, DAEMON_PASSWORD)
        val connectedInstance = instanceName?.also {
            dbClient.connectToInstance(it)
        }
        logger.debug("Daemon session established for instance '${instanceName ?: "<none>"}'")
        return try {
            block(dbClient)
        } finally {
            // Cleanup must be best-effort so errors inside `block` are
            // never masked. But we also must *not* swallow teardown
            // failures silently — a failed disconnect leaves the daemon
            // session dangling, which causes 423 Locked for the next
            // request targeting the same instance.
            if (connectedInstance != null) {
                try {
                    dbClient.disconnectToInstance(connectedInstance)
                } catch (e: Exception) {
                    logger.warn(
                        "disconnectToInstance('$connectedInstance') failed: ${e.message}. " +
                        "The daemon may still hold this session.", e
                    )
                }
            }
            try {
                dbClient.logout()
            } catch (e: Exception) {
                logger.warn(
                    "logout() failed: ${e.message}. " +
                    "A stale auth token may remain on the daemon.", e
                )
            }
        }
    }

    return if (lock != null) lock.withLock { doSession() } else doSession()
}

/**
 * Translate a [DatabaseClient.DatabaseDaemonException] into a stable
 * `(HTTP status, error message)` pair. Falls back to 500 + the exception
 * message for unexpected errors.
 *
 * **Logs every exception** — previously this function swallowed the
 * throwable silently, producing 500 responses with no backend log entry.
 * Now unexpected errors (500) are logged at ERROR with the full stack
 * trace, and database daemon errors are logged at WARN so they are
 * visible without cluttering the error stream.
 */
fun errorPayload(e: Throwable): Pair<HttpStatusCode, Map<String, String?>> {
    val msg = e.message ?: e.javaClass.simpleName
    val dbEx = e as? DatabaseClient.DatabaseDaemonException
    val status = dbEx?.statusCode ?: HttpStatusCode.InternalServerError
    if (dbEx != null) {
        logger.warn("Route error (HTTP ${status.value}): ${dbEx.message}", dbEx)
    } else {
        logger.error("Unexpected route error (HTTP 500): ${e.message}", e)
    }
    return status to mapOf("error" to msg)
}
