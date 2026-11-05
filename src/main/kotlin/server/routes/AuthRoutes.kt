package org.iotsplab.akiba.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.server.db.User
import org.iotsplab.akiba.server.db.UserDao
import org.iotsplab.akiba.server.security.JwtService
import org.iotsplab.akiba.server.security.LoginProtection

private val logger: Logger = LogManager.getLogger("AuthRoutes")

data class RegisterRequest(val username: String, val password: String, val isAdmin: Boolean = false)
data class LoginRequest(val username: String, val password: String)
data class AuthResponse(
    val token: String,
    val userId: Int,
    val username: String,
    val isAdmin: Boolean = false,
)
data class MessageResponse(val message: String)
data class SetupStatusResponse(val needsSetup: Boolean, val userCount: Int)

/**
 * Authentication routes.
 *
 * Setup instead of shipped defaults: no account or instance is created at
 * container start any more, because credentials that ship in an image are
 * credentials everybody knows. The first visit shows a setup form
 * ([POST /auth/setup]) that creates the first administrator; once an account
 * exists setup is closed.
 *
 * Sign-in also establishes the daemon identity: password verification stays
 * here (bcrypt in the `users` table), but the user is additionally verified
 * against the database daemon and a derived credential is cached for the
 * session — see [establishDaemonIdentity]. Without that the daemon would not
 * know who the caller is.
 */
fun Route.authRoutes(daemonHost: String, daemonPort: Int) {

    /** Whether the deployment still needs its first administrator. */
    post("/auth/setup-status") {
        try {
            val count = UserDao.countUsers()
            call.respond(SetupStatusResponse(needsSetup = count == 0, userCount = count))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, MessageResponse(
                "Failed to read user table: ${e.message}"))
        }
    }

    /**
     * Create the first administrator. Only available while the user table is
     * empty; afterwards it returns 409 so a stale browser tab cannot add an
     * account behind the administrator's back.
     */
    post("/auth/setup") {
        val req = call.receive<RegisterRequest>()
        val username = req.username.trim()
        if (username.isBlank() || req.password.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, MessageResponse("Username and password are required"))
            return@post
        }
        if (req.password.length < MIN_PASSWORD_LENGTH) {
            call.respond(HttpStatusCode.BadRequest, MessageResponse(
                "Password must be at least $MIN_PASSWORD_LENGTH characters"))
            return@post
        }
        try {
            if (UserDao.countUsers() > 0) {
                call.respond(HttpStatusCode.Conflict, MessageResponse(
                    "Setup has already been completed; sign in instead"))
                return@post
            }
            // Mirror the account into the daemon and cache its identity *before*
            // creating the row: signing in "successfully" while the daemon does
            // not know the user would surface later as a confusing 401 on every
            // instance operation, and a half-created account is harder to clean
            // up than a credential nobody can log in with yet.
            establishDaemonIdentity(daemonHost, daemonPort, username, req.password, isAdmin = true)
            val userId = UserDao.createUser(username, req.password, isAdmin = true)
            val (token, _) = JwtService.createSession(userId, username)
            logger.info("First administrator '{}' created through first-run setup", username)
            call.respond(AuthResponse(token, userId, username, isAdmin = true))
        } catch (e: Exception) {
            logger.error("First-run setup failed for '{}': {}", username, e.message, e)
            call.respond(HttpStatusCode.InternalServerError, MessageResponse(
                "Setup failed: ${e.message}"))
        }
    }

    /**
     * Sign in.
     *
     * Brute-force protection (see [LoginProtection]): every rejected attempt —
     * wrong password *or* unknown username — is answered only after
     * [LoginProtection.FAILURE_DELAY], and an account is locked for
     * [LoginProtection.LOCK_DURATION] once [LoginProtection.MAX_FAILED_ATTEMPTS]
     * consecutive attempts failed inside [LoginProtection.FAILURE_WINDOW]. The
     * text of a rejection is identical in the "no such user" and "wrong
     * password" cases on purpose: a distinguishable answer would let anyone test
     * which accounts exist.
     */
    post("/auth/login") {
        val req = call.receive<LoginRequest>()
        val username = req.username.trim()
        if (username.isBlank() || req.password.isBlank()) {
            call.respond(HttpStatusCode.BadRequest,
                MessageResponse("Username and password are required")
            )
            return@post
        }
        try {
            val user = UserDao.getUserByUsername(username)
            if (user == null) {
                // Cost the same as a real verification before answering.
                UserDao.burnPasswordCheck(req.password)
                LoginProtection.noteUnknownUserFailure(username)
                if (LoginProtection.isUnknownUserThrottled(username)) {
                    logger.warn(
                        "Repeated sign-in attempts for unknown account '{}' ({} in the last {})",
                        username,
                        LoginProtection.unknownUserFailureCount(username),
                        LoginProtection.FAILURE_WINDOW,
                    )
                }
                LoginProtection.awaitFailureDelay()
                call.respond(HttpStatusCode.Unauthorized, invalidCredentials())
                return@post
            }

            if (UserDao.isLocked(user)) {
                LoginProtection.awaitFailureDelay()
                call.respond(HttpStatusCode.Locked, lockedAccount(user))
                return@post
            }

            if (!UserDao.checkPassword(user, req.password)) {
                val updated = UserDao.recordFailedLogin(user)
                LoginProtection.awaitFailureDelay()
                if (UserDao.isLocked(updated)) {
                    logger.warn(
                        "Account '{}' locked for {} after {} failed sign-ins",
                        username, LoginProtection.LOCK_DURATION, updated.failedLoginAttempts,
                    )
                    call.respond(HttpStatusCode.Locked, lockedAccount(updated))
                } else {
                    logger.info(
                        "Rejected sign-in for '{}' ({}/{} attempts)",
                        username, updated.failedLoginAttempts, LoginProtection.MAX_FAILED_ATTEMPTS,
                    )
                    call.respond(HttpStatusCode.Unauthorized, invalidCredentials())
                }
                return@post
            }

            // Success: the counter tracks *consecutive* failures only.
            UserDao.clearLoginFailures(user.id)
            LoginProtection.clearUnknownUserFailures(username)
            // The password is verified above; from here on the daemon must know
            // the same identity.
            establishDaemonIdentity(daemonHost, daemonPort, user.username, req.password, user.isAdmin)
            val (token, _) = JwtService.createSession(user.id, user.username)
            call.respond(AuthResponse(token, user.id, user.username, user.isAdmin))
        } catch (e: Exception) {
            logger.error("Login failed for '{}': {}", username, e.message)
            call.respond(HttpStatusCode.InternalServerError,
                MessageResponse("Login failed: ${e.message}")
            )
        }
    }

    post("/auth/logout") {
        val authHeader = call.request.header("Authorization")
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            JwtService.invalidateToken(authHeader.substring(7))
        }
        call.respond(MessageResponse("Logged out successfully"))
    }

    get("/auth/me") {
        val authHeader = call.request.header("Authorization")
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            call.respond(HttpStatusCode.Unauthorized,
                MessageResponse("No token provided")
            )
            return@get
        }
        val token = authHeader.substring(7)
        val session = JwtService.validateToken(token)
        if (session == null) {
            call.respond(HttpStatusCode.Unauthorized,
                MessageResponse("Invalid or expired token")
            )
            return@get
        }
        val user = UserDao.getUserById(session.userId)
        if (user == null) {
            call.respond(HttpStatusCode.Unauthorized,
                MessageResponse("User not found")
            )
            return@get
        }
        call.respond(AuthResponse(token, user.id, user.username, user.isAdmin))
    }
}

/**
 * Minimum password length, enforced by setup, login-time account creation and
 * the admin API alike (and mirrored by the daemon's own check).
 */
const val MIN_PASSWORD_LENGTH = 6

/**
 * One message for every rejected credential, so the answer cannot be used to
 * find out which usernames exist.
 */
private fun invalidCredentials() = MessageResponse("Invalid username or password")

/**
 * Explain the lock and when it lifts, so a legitimate user knows the wait is
 * deliberate rather than a broken deployment.
 */
private fun lockedAccount(user: User): MessageResponse {
    val formatted = user.lockedUntil?.let {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(it)
    }
    return MessageResponse(
        "Account locked after ${user.failedLoginAttempts} failed sign-in attempts. " +
            if (formatted != null) {
                "It unlocks at $formatted, or an administrator can unlock it now."
            } else {
                "An administrator can unlock it."
            }
    )
}

/**
 * Lightweight authentication guard for route groups that must not run for
 * anonymous callers. Routes in this file deliberately do their own checks so
 * that the failure message can be precise (missing vs invalid vs expired).
 */
suspend fun ApplicationCall.requireAuthenticated(): Boolean {
    if (currentUsernameOrNull() == null) {
        respond(HttpStatusCode.Unauthorized, MessageResponse("Authentication required"))
        return false
    }
    return true
}
