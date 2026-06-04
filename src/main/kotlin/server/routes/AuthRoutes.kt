package org.iotsplab.akiba.server.routes

import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.iotsplab.akiba.server.db.UserDao
import org.iotsplab.akiba.server.security.JwtService

data class RegisterRequest(val username: String, val password: String)
data class LoginRequest(val username: String, val password: String)
data class AuthResponse(val token: String, val userId: Int, val username: String)
data class MessageResponse(val message: String)

fun Route.authRoutes() {
    post("/auth/register") {
        val req = call.receive<RegisterRequest>()
        if (req.username.isBlank() || req.password.isBlank()) {
            call.respond(io.ktor.http.HttpStatusCode.BadRequest,
                MessageResponse("Username and password are required")
            )
            return@post
        }
        if (req.password.length < 6) {
            call.respond(io.ktor.http.HttpStatusCode.BadRequest,
                MessageResponse("Password must be at least 6 characters")
            )
            return@post
        }
        try {
            val existingUser = UserDao.getUserByUsername(req.username)
            if (existingUser != null) {
                call.respond(io.ktor.http.HttpStatusCode.Conflict,
                    MessageResponse("Username already exists")
                )
                return@post
            }
            val userId = UserDao.createUser(req.username, req.password)
            val (token, session) = JwtService.createSession(userId, req.username)
            call.respond(AuthResponse(token, userId, req.username))
        } catch (e: Exception) {
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError,
                MessageResponse("Registration failed: ${e.message}")
            )
        }
    }

    post("/auth/login") {
        val req = call.receive<LoginRequest>()
        if (req.username.isBlank() || req.password.isBlank()) {
            call.respond(io.ktor.http.HttpStatusCode.BadRequest,
                MessageResponse("Username and password are required")
            )
            return@post
        }
        try {
            val user = UserDao.verifyPassword(req.username, req.password)
            if (user == null) {
                call.respond(io.ktor.http.HttpStatusCode.Unauthorized,
                    MessageResponse("Invalid credentials")
                )
                return@post
            }
            val (token, session) = JwtService.createSession(user.id, user.username)
            call.respond(
                AuthResponse(
                    token,
                    user.id,
                    user.username
                )
            )
        } catch (e: Exception) {
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError,
                MessageResponse("Login failed: ${e.message}")
            )
        }
    }

    post("/auth/logout") {
        val authHeader = call.request.header("Authorization")
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            val token = authHeader.substring(7)
            JwtService.invalidateToken(token)
        }
        call.respond(MessageResponse("Logged out successfully"))
    }

    get("/auth/me") {
        val authHeader = call.request.header("Authorization")
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            call.respond(io.ktor.http.HttpStatusCode.Unauthorized,
                MessageResponse("No token provided")
            )
            return@get
        }
        val token = authHeader.substring(7)
        val session = JwtService.validateToken(token)
        if (session == null) {
            call.respond(io.ktor.http.HttpStatusCode.Unauthorized,
                MessageResponse("Invalid or expired token")
            )
            return@get
        }
        val user = UserDao.getUserById(session.userId)
        if (user == null) {
            call.respond(io.ktor.http.HttpStatusCode.Unauthorized,
                MessageResponse("User not found")
            )
            return@get
        }
        call.respond(AuthResponse(token, user.id, user.username))
    }
}
