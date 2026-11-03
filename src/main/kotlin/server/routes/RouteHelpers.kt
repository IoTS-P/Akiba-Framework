package org.iotsplab.akiba.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respond
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.data.database.DatabaseClient
import org.iotsplab.akiba.server.db.UserDao
import org.iotsplab.akiba.server.security.JwtService

private val logger: Logger = LogManager.getLogger("RouteHelpers")

/**
 * Helpers for HTTP routes that need to talk to the akiba_db_daemon.
 *
 * Every request runs as the **signed-in user**, identified by their JWT (see
 * [currentUsernameOrNull]); there is no shared service account any more, which
 * is what makes per-user instance ownership possible.
 *
 * Sign-in establishes a [DaemonIdentity] — a derived credential minted by the
 * daemon, so no password is ever written into a generated config file — and
 * instance-scoped sessions resolve theirs from the instance name via
 * [DaemonIdentities], because an instance has exactly one owner; a session the
 * daemon rejects invalidates the cached identity, so the next request fails
 * loudly instead of acting as the wrong user.
 */

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
 *
 * The instance must also belong to the caller: the server authenticates to the
 * database daemon **as the instance's owner**, so without this check naming
 * somebody else's instance would be enough to read their data.
 */
suspend fun ApplicationCall.requireInstanceHeader(): String? {
    val name = instanceHeader()
    if (name == null) {
        respond(HttpStatusCode.BadRequest, mapOf(
            "error" to "Missing instance selection. " +
                "Please select an instance in the UI before making this request."
        ))
        return null
    }
    if (!requireInstanceOwnership(name)) return null
    return name
}

/**
 * Verify that the caller owns [instance], answering 401/403 when it does not.
 *
 * @return true when the request may proceed; false after a response was sent.
 */
suspend fun ApplicationCall.requireInstanceOwnership(instance: String): Boolean {
    val problem = instanceAccessProblem(this, instance) ?: return true
    respond(problem.first, mapOf("error" to problem.second))
    return false
}

/**
 * Username of the authenticated caller, or `null` when the request carries no
 * (or an invalid) session token.
 */
fun ApplicationCall.currentUsernameOrNull(): String? {
    val authHeader = request.header("Authorization") ?: return null
    if (!authHeader.startsWith("Bearer ")) return null
    val token = authHeader.substring(7)
    val session = JwtService.validateToken(token) ?: return null
    return UserDao.getUserById(session.userId)?.username
}

/** Raw JWT of the current request, if any. */
fun ApplicationCall.currentTokenOrNull(): String? =
    request.header("Authorization")
        ?.takeIf { it.startsWith("Bearer ") }
        ?.substring(7)

/**
 * Username of the authenticated caller.
 *
 * @throws IllegalStateException when the request is not authenticated. Routes
 *         behind [org.iotsplab.akiba.server.routes.RouteHelpers] rely on the JWT
 *         and used to fall back to a shared service account; silently borrowing
 *         another identity is exactly what enabled cross-user access, so the
 *         old fallback is gone on purpose — callers now fail instead.
 */
fun ApplicationCall.currentUsernameOrDefault(): String =
    currentUsernameOrNull() ?: throw IllegalStateException(
        "Authentication required: this endpoint needs a signed-in user"
    )

/** Whether the authenticated caller is an administrator. */
fun ApplicationCall.currentUserIsAdmin(): Boolean {
    val authHeader = request.header("Authorization") ?: return false
    if (!authHeader.startsWith("Bearer ")) return false
    val session = JwtService.validateToken(authHeader.substring(7)) ?: return false
    return UserDao.getUserById(session.userId)?.isAdmin == true
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
        .ifBlank { "anonymous" }

/**
 * Per-instance locks. The daemon only allows one active database session
 * per instance, so concurrent requests targeting the same instance must be
 * serialized. Requests to *different* instances run in parallel.
 */
private val instanceLocks = ConcurrentHashMap<String, ReentrantLock>()

private fun lockFor(instanceName: String): ReentrantLock =
    instanceLocks.computeIfAbsent(instanceName) { ReentrantLock() }

/**
 * Run [block] inside a fresh [DatabaseClient] session authenticated as the owner
 * of [instanceName], serialized per instance so the daemon never sees two
 * concurrent connections to the same instance.
 *
 * The owner (and therefore the credentials) is looked up in [DaemonIdentities],
 * which is populated at sign-in. A session that the daemon refuses invalidates
 * the cached identity, so the user is told to sign in again rather than being
 * silently served as the wrong account.
 *
 * @throws DatabaseClient.DatabaseDaemonException when no identity is known for
 *         the instance (the user is not signed in, or the instance belongs to
 *         somebody else / does not exist).
 */
fun <T> withDaemonSession(
    daemonHost: String,
    daemonPort: Int,
    instanceName: String? = null,
    serialize: Boolean = true,
    block: (DatabaseClient) -> T
): T {
    if (instanceName == null) throw DatabaseClient.DatabaseDaemonException(
        HttpStatusCode.BadRequest,
        "This operation needs an explicit user identity; use withDaemonSessionForUser()"
    )
    val owner = DaemonIdentities.ownerOf(instanceName)
        ?: throw DatabaseClient.DatabaseDaemonException(
            HttpStatusCode.BadRequest,
            "Instance '$instanceName' is not available in this session. " +
                "Sign in again, or create/select an instance you own."
        )
    val identity = DaemonIdentities.forUser(owner)
        ?: throw DatabaseClient.DatabaseDaemonException(
            HttpStatusCode.Unauthorized,
            "No database credentials cached for user '$owner'. Please sign in again."
        )
    return runDaemonSession(
        daemonHost, daemonPort, identity, instanceName, if (serialize) lockFor(instanceName) else null, block
    )
}

/**
 * Run [block] inside a fresh [DatabaseClient] session authenticated as
 * [username] (used by the routes that manage instances themselves — they cannot
 * be resolved from an instance name because it may not exist yet).
 */
fun <T> withDaemonSessionForUser(
    daemonHost: String,
    daemonPort: Int,
    username: String,
    instanceName: String? = null,
    serialize: Boolean = true,
    block: (DatabaseClient) -> T
): T {
    val identity = DaemonIdentities.forUser(username)
        ?: throw DatabaseClient.DatabaseDaemonException(
            HttpStatusCode.Unauthorized,
            "No database credentials cached for user '$username'. Please sign in again."
        )
    return runDaemonSession(
        daemonHost,
        daemonPort,
        identity,
        instanceName,
        if (serialize && instanceName != null) lockFor(instanceName) else null,
        block
    )
}

private fun <T> runDaemonSession(
    daemonHost: String,
    daemonPort: Int,
    identity: DaemonIdentity,
    instanceName: String?,
    lock: ReentrantLock?,
    block: (DatabaseClient) -> T
): T {
    fun doSession(): T {
        val dbClient = DatabaseClient(daemonHost, daemonPort)
        try {
            dbClient.login(identity.username, identity.secret)
            if (instanceName != null) dbClient.connectToInstance(instanceName)
        } catch (e: DatabaseClient.DatabaseDaemonException) {
            if (e.statusCode == HttpStatusCode.Unauthorized) {
                DaemonIdentities.invalidate(identity.username)
            }
            throw e
        }
        logger.debug("Daemon session established as '${identity.username}' for '${instanceName ?: "<none>"}'")
        return try {
            block(dbClient)
        } finally {
            // Cleanup must be best-effort so errors inside `block` are
            // never masked. But we also must *not* swallow teardown
            // failures silently — a failed disconnect leaves the daemon
            // session dangling, which causes 423 Locked for the next
            // request targeting the same instance.
            if (instanceName != null) {
                try {
                    dbClient.disconnectToInstance(instanceName)
                } catch (e: Exception) {
                    logger.warn(
                        "disconnectToInstance('$instanceName') failed: ${e.message}. " +
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
 * Whether [call] may act on [instance], expressed as the HTTP answer to send
 * when it may not.
 *
 * Instances are namespaced by owner, and the server now authenticates to the
 * daemon **as that owner**: without this check any authenticated (or even
 * anonymous) caller could read another user's analysis data simply by naming
 * their instance in `X-Akiba-Instance`. It is enforced centrally in
 * `AkibaServer` for every `/api` request that carries the header.
 *
 * @return null when the request may proceed, otherwise the status and message
 *         to answer with.
 */
fun instanceAccessProblem(call: ApplicationCall, instance: String): Pair<HttpStatusCode, String>? {
    val username = call.currentUsernameOrNull()
        ?: return HttpStatusCode.Unauthorized to "Authentication required"
    val owner = DaemonIdentities.ownerOf(instance)
        ?: return HttpStatusCode.BadRequest to
            "Instance '$instance' is not available in this session. " +
            "Sign in again, or create/select an instance you own."
    if (owner != username)
        return HttpStatusCode.Forbidden to "Instance '$instance' belongs to another user"
    return null
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
