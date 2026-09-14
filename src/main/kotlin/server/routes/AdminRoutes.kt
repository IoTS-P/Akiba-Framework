package org.iotsplab.akiba.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.server.db.UserDao
import org.iotsplab.akiba.server.security.LoginProtection

private val logger: Logger = LogManager.getLogger("AdminRoutes")

data class AdminUserEntry(
    val id: Int,
    val username: String,
    val isAdmin: Boolean,
    val createdAt: String,
    /** Consecutive failed sign-ins recorded for the account. */
    val failedLoginAttempts: Int = 0,
    /** ISO-8601 instant until which the account is locked, or null. */
    val lockedUntil: String? = null,
    /** Whether the account is locked right now (see LoginProtection). */
    val locked: Boolean = false,
)

data class AdminCreateUserRequest(
    val username: String,
    val password: String,
    val isAdmin: Boolean = false,
)

data class AdminPasswordRequest(val password: String)

/**
 * User administration, restricted to administrators.
 *
 * Self-registration was removed on purpose (an account is the key to the
 * analysis data behind it), so this is the only way to add users. Creating an
 * account also mirrors the credential into the database daemon — the daemon
 * keeps its own credential store, and a web account it has never heard of would
 * fail on every instance operation.
 */
fun Route.adminRoutes(daemonHost: String, daemonPort: Int) {

    /**
     * Guard for this route group: returns the caller's username when the request
     * belongs to an administrator, otherwise answers and returns null.
     */
    suspend fun requireAdmin(call: ApplicationCall): String? {
        val username = call.currentUsernameOrNull()
        if (username == null) {
            call.respond(HttpStatusCode.Unauthorized, MessageResponse("Authentication required"))
            return null
        }
        if (!call.currentUserIsAdmin()) {
            call.respond(HttpStatusCode.Forbidden, MessageResponse(
                "Administrator privileges are required for user management"))
            return null
        }
        return username
    }

    get("/admin/users") {
        if (requireAdmin(call) == null) return@get
        try {
            val users = UserDao.listUsers().map { user ->
                AdminUserEntry(
                    id = user.id,
                    username = user.username,
                    isAdmin = user.isAdmin,
                    createdAt = user.createdAt,
                    failedLoginAttempts = user.failedLoginAttempts,
                    lockedUntil = user.lockedUntil?.toString(),
                    locked = UserDao.isLocked(user),
                )
            }
            call.respond(mapOf("users" to users))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError,
                MessageResponse("Failed to list users: ${e.message}"))
        }
    }

    post("/admin/users") {
        if (requireAdmin(call) == null) return@post
        val req = call.receive<AdminCreateUserRequest>()
        val username = req.username.trim()
        if (!username.matches(Regex("^[A-Za-z0-9._-]{1,64}$"))) {
            call.respond(HttpStatusCode.BadRequest, MessageResponse(
                "Invalid username: use 1-64 characters (letters, digits, dot, underscore, hyphen)"))
            return@post
        }
        if (req.password.length < MIN_PASSWORD_LENGTH) {
            call.respond(HttpStatusCode.BadRequest, MessageResponse(
                "Password must be at least $MIN_PASSWORD_LENGTH characters"))
            return@post
        }
        try {
            if (UserDao.getUserByUsername(username) != null) {
                call.respond(HttpStatusCode.Conflict, MessageResponse("Username already exists"))
                return@post
            }
            // Register with the daemon first: if the daemon refuses (unreachable,
            // or a credential is already stored under that name), no half-created
            // web account is left behind.
            establishDaemonIdentity(daemonHost, daemonPort, username, req.password, req.isAdmin)
            val id = UserDao.createUser(username, req.password, req.isAdmin)
            logger.info("Administrator created user '{}' (isAdmin={})", username, req.isAdmin)
            call.respond(AdminUserEntry(id, username, req.isAdmin, ""))
        } catch (e: Exception) {
            logger.error("Failed to create user '{}': {}", username, e.message, e)
            call.respond(HttpStatusCode.InternalServerError, MessageResponse(
                "Failed to create user: ${e.message}"))
        }
    }

    delete("/admin/users/{id}") {
        val adminName = requireAdmin(call) ?: return@delete
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, MessageResponse("Invalid user id"))
            return@delete
        }
        val target = UserDao.getUserById(id)
        if (target == null) {
            call.respond(HttpStatusCode.NotFound, MessageResponse("User not found"))
            return@delete
        }
        if (target.username == adminName) {
            call.respond(HttpStatusCode.Conflict, MessageResponse("You cannot delete your own account"))
            return@delete
        }
        if (target.isAdmin && UserDao.countAdmins() <= 1) {
            call.respond(HttpStatusCode.Conflict, MessageResponse(
                "At least one administrator must remain"))
            return@delete
        }
        try {
            UserDao.deleteUser(id)
            DaemonIdentities.invalidate(target.username)
            logger.info("Administrator '{}' deleted user '{}'", adminName, target.username)
            call.respond(MessageResponse("User deleted"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError,
                MessageResponse("Failed to delete user: ${e.message}"))
        }
    }

    /**
     * Lift the brute-force lock of an account.
     *
     * This is what the login error message means by "an administrator can unlock
     * it now": the lock otherwise expires on its own after
     * [LoginProtection.LOCK_DURATION]. Because the daemon keeps its own
     * credentials *and* its own failure counters, an unlock is mirrored there
     * through the administrator's identity — best-effort, since the web account
     * is what actually blocks the sign-in.
     */
    post("/admin/users/{id}/unlock") {
        val adminName = requireAdmin(call) ?: return@post
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, MessageResponse("Invalid user id"))
            return@post
        }
        val target = UserDao.getUserById(id)
        if (target == null) {
            call.respond(HttpStatusCode.NotFound, MessageResponse("User not found"))
            return@post
        }
        try {
            UserDao.unlockUser(id)
            DaemonIdentities.forUser(adminName)?.let { adminIdentity ->
                runCatching { applyDaemonUnlock(daemonHost, daemonPort, adminIdentity, target.username) }
                    .onFailure {
                        logger.warn(
                            "Unlocked '{}' in Akiba, but the database daemon refused the " +
                                "unlock: {}", target.username, it.message
                        )
                    }
            }
            logger.info("Administrator '{}' unlocked account '{}'", adminName, target.username)
            call.respond(MessageResponse("Account unlocked"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError,
                MessageResponse("Failed to unlock user: ${e.message}"))
        }
    }

    /**
     * Reset a user's password.
     *
     * The daemon cannot update an existing credential's password in place; the
     * identity is re-established instead, and a refusal is reported to the
     * administrator together with the remedy (delete + recreate the account).
     */
    post("/admin/users/{id}/password") {
        val adminName = requireAdmin(call) ?: return@post
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, MessageResponse("Invalid user id"))
            return@post
        }
        val req = call.receive<AdminPasswordRequest>()
        if (req.password.length < MIN_PASSWORD_LENGTH) {
            call.respond(HttpStatusCode.BadRequest, MessageResponse(
                "Password must be at least $MIN_PASSWORD_LENGTH characters"))
            return@post
        }
        val target = UserDao.getUserById(id)
        if (target == null) {
            call.respond(HttpStatusCode.NotFound, MessageResponse("User not found"))
            return@post
        }
        try {
            UserDao.setPassword(id, req.password)
            // Keep the daemon's copy in sync through the administrator's own
            // identity; without this the user could sign in but every instance
            // operation would be rejected.
            DaemonIdentities.invalidate(target.username)
            val adminIdentity = DaemonIdentities.forUser(adminName)
                ?: throw IllegalStateException(
                    "No cached daemon credentials for administrator '$adminName'; " +
                        "sign in again and retry")
            applyDaemonPassword(daemonHost, daemonPort, adminIdentity, target.username, req.password)
            logger.info("Administrator '{}' reset the password of '{}'", adminName, target.username)
            call.respond(MessageResponse("Password updated"))
        } catch (e: Exception) {
            logger.error("Password reset failed for '{}': {}", target.username, e.message)
            call.respond(HttpStatusCode.InternalServerError, MessageResponse(
                "Password updated in Akiba, but the database daemon rejected the credential: " +
                    "${e.message}"
            ))
        }
    }
}
