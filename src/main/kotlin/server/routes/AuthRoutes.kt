package org.iotsplab.akiba.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.server.db.UserDao
import org.iotsplab.akiba.server.security.JwtService
import org.iotsplab.akiba.server.security.LoginProtection

private val logger: Logger = LogManager.getLogger("AuthRoutes")

/** Body of setup, sign-in and password change requests. */
data class PasswordRequest(val password: String)
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

data class AuthResponse(val token: String, val username: String)
data class MessageResponse(val message: String)
data class SetupStatusResponse(val needsSetup: Boolean)

/**
 * The single operator account.
 *
 * Akiba has no user management: one password guards the deployment, and the
 * row behind it is always this name. Setup sets the password the first time,
 * `POST /auth/password` changes it later.
 */
const val ADMIN_USERNAME = "admin"

/**
 * Authentication routes.
 *
 * Setup instead of shipped defaults: credentials baked into an image are
 * credentials everybody knows, so the first visit sets the password
 * ([POST /auth/setup]) and the endpoint closes afterwards.
 *
 * Sign-in also establishes the daemon identity: password verification stays
 * here (bcrypt in the `users` table), while the daemon gets a derived
 * credential for this process — see [establishDaemonIdentity].
 */
fun Route.authRoutes(daemonHost: String, daemonPort: Int) {

    /** Whether the deployment still needs its password to be set. */
    post("/auth/setup-status") {
        try {
            val needsSetup = UserDao.countUsers() == 0
            call.respond(SetupStatusResponse(needsSetup))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, MessageResponse(
                "Failed to read user table: ${e.message}"))
        }
    }

    /**
     * Set the password for the first time. Only available while no row exists;
     * afterwards it returns 409 so a stale browser tab cannot silently replace
     * the operator's credential.
     */
    post("/auth/setup") {
        val req = call.receive<PasswordRequest>()
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
            // creating the row: a credential the daemon does not know would
            // surface later as a confusing 401 on every instance operation.
            establishDaemonIdentity(
                daemonHost, daemonPort, ADMIN_USERNAME, req.password, isAdmin = true)
            UserDao.createUser(ADMIN_USERNAME, req.password)
            val userId = UserDao.getUserByUsername(ADMIN_USERNAME)!!.id
            val (token, _) = JwtService.createSession(userId, ADMIN_USERNAME)
            logger.info("Operator password set through first-run setup")
            call.respond(AuthResponse(token, ADMIN_USERNAME))
        } catch (e: Exception) {
            logger.error("First-run setup failed: {}", e.message, e)
            call.respond(HttpStatusCode.InternalServerError, MessageResponse(
                "Setup failed: ${e.message}"))
        }
    }

    /**
     * Sign in with the operator password.
     *
     * Every rejection is answered only after [LoginProtection.FAILURE_DELAY],
     * which bounds guessing without a lockout that could lock the operator out
     * of their own deployment.
     */
    post("/auth/login") {
        val req = call.receive<PasswordRequest>()
        if (req.password.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, MessageResponse("Password is required"))
            return@post
        }
        try {
            val user = UserDao.getUserByUsername(ADMIN_USERNAME)
            if (user == null) {
                UserDao.burnPasswordCheck(req.password)
                LoginProtection.awaitFailureDelay()
                call.respond(HttpStatusCode.Unauthorized, invalidCredentials())
                return@post
            }
            if (!UserDao.checkPassword(user, req.password)) {
                LoginProtection.awaitFailureDelay()
                logger.info("Rejected sign-in attempt")
                call.respond(HttpStatusCode.Unauthorized, invalidCredentials())
                return@post
            }
            // The password is verified above; from here on the daemon must know
            // the same identity.
            establishDaemonIdentity(
                daemonHost, daemonPort, user.username, req.password, isAdmin = true)
            val (token, _) = JwtService.createSession(user.id, user.username)
            call.respond(AuthResponse(token, user.username))
        } catch (e: Exception) {
            logger.error("Login failed: {}", e.message)
            call.respond(HttpStatusCode.InternalServerError, MessageResponse(
                "Login failed: ${e.message}"))
        }
    }

    /**
     * Replace the operator password. Requires a valid session and the current
     * password, so a stolen token alone cannot lock the operator out.
     */
    post("/auth/password") {
        if (!call.requireAuthenticated()) return@post
        val req = call.receive<ChangePasswordRequest>()
        if (req.newPassword.length < MIN_PASSWORD_LENGTH) {
            call.respond(HttpStatusCode.BadRequest, MessageResponse(
                "Password must be at least $MIN_PASSWORD_LENGTH characters"))
            return@post
        }
        try {
            val user = UserDao.getUserByUsername(ADMIN_USERNAME)
            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, invalidCredentials())
                return@post
            }
            if (!UserDao.checkPassword(user, req.currentPassword)) {
                LoginProtection.awaitFailureDelay()
                call.respond(HttpStatusCode.Unauthorized, invalidCredentials())
                return@post
            }
            UserDao.setPassword(user.id, req.newPassword)
            // The daemon keeps its own credential store, so the operator's new
            // password has to be written there too — otherwise the next sign-in
            // verifies locally but the daemon still holds the old secret.
            syncDaemonPassword(daemonHost, daemonPort, req.newPassword)
            // The daemon authenticates with the password it was given at
            // sign-in, so a changed password needs a fresh identity here too.
            establishDaemonIdentity(
                daemonHost, daemonPort, user.username, req.newPassword, isAdmin = true)
            logger.info("Operator password changed")
            call.respond(MessageResponse("Password changed"))
        } catch (e: Exception) {
            logger.error("Password change failed: {}", e.message, e)
            call.respond(HttpStatusCode.InternalServerError, MessageResponse(
                "Password change failed: ${e.message}"))
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
            call.respond(HttpStatusCode.Unauthorized, MessageResponse("No token provided"))
            return@get
        }
        val token = authHeader.substring(7)
        val session = JwtService.validateToken(token)
        if (session == null) {
            call.respond(HttpStatusCode.Unauthorized, MessageResponse("Invalid or expired token"))
            return@get
        }
        val user = UserDao.getUserById(session.userId)
        if (user == null) {
            call.respond(HttpStatusCode.Unauthorized, MessageResponse("User not found"))
            return@get
        }
        call.respond(AuthResponse(token, user.username))
    }
}

/**
 * Minimum password length, enforced by setup and password change alike (and
 * mirrored by the daemon's own check).
 */
const val MIN_PASSWORD_LENGTH = 6

/** One message for every rejected credential. */
private fun invalidCredentials() = MessageResponse("Invalid password")

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
