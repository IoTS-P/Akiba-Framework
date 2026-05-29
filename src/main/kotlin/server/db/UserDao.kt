package org.iotsplab.akiba.server.db

import org.mindrot.jbcrypt.BCrypt
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

data class User(
    val id: Int,
    val username: String,
    val passwordHash: String,
    val createdAt: String
)

data class Session(
    val id: Int,
    val userId: Int,
    val token: String,
    val createdAt: String,
    val expiresAt: String
)

object UserDao {
    fun createUser(username: String, password: String): Int {
        val passwordHash = BCrypt.hashpw(password, BCrypt.gensalt())
        return ServerDatabase.getConnection().prepareStatement(
            "INSERT INTO users (username, password_hash) VALUES (?, ?) RETURNING id"
        ).use { stmt ->
            stmt.setString(1, username)
            stmt.setString(2, passwordHash)
            stmt.executeQuery().use { rs ->
                rs.next().let { rs.getInt("id") }
            }
        }
    }

    fun getUserByUsername(username: String): User? {
        return ServerDatabase.getConnection().prepareStatement(
            "SELECT * FROM users WHERE username = ?"
        ).use { stmt ->
            stmt.setString(1, username)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.toUser() else null
            }
        }
    }

    fun getUserById(id: Int): User? {
        return ServerDatabase.getConnection().prepareStatement(
            "SELECT * FROM users WHERE id = ?"
        ).use { stmt ->
            stmt.setInt(1, id)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.toUser() else null
            }
        }
    }

    fun verifyPassword(username: String, password: String): User? {
        val user = getUserByUsername(username) ?: return null
        return if (BCrypt.checkpw(password, user.passwordHash)) user else null
    }

    fun createSession(userId: Int, token: String, expiresAt: Instant): Int {
        return ServerDatabase.getConnection().prepareStatement(
            "INSERT INTO user_sessions (user_id, token, expires_at) VALUES (?, ?, ?) RETURNING id"
        ).use { stmt ->
            stmt.setInt(1, userId)
            stmt.setString(2, token)
            stmt.setTimestamp(3, Timestamp.from(expiresAt))
            stmt.executeQuery().use { rs ->
                rs.next().let { rs.getInt("id") }
            }
        }
    }

    fun getSession(token: String): Session? {
        return ServerDatabase.getConnection().prepareStatement(
            "SELECT * FROM user_sessions WHERE token = ? AND expires_at > now()"
        ).use { stmt ->
            stmt.setString(1, token)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.toSession() else null
            }
        }
    }

    fun deleteSession(token: String) {
        ServerDatabase.getConnection().prepareStatement(
            "DELETE FROM user_sessions WHERE token = ?"
        ).use { stmt ->
            stmt.setString(1, token)
            stmt.executeUpdate()
        }
    }

    fun deleteExpiredSessions() {
        ServerDatabase.getConnection().createStatement().use { stmt ->
            stmt.executeUpdate("DELETE FROM user_sessions WHERE expires_at <= now()")
        }
    }

    private fun ResultSet.toUser() = User(
        id = getInt("id"),
        username = getString("username"),
        passwordHash = getString("password_hash"),
        createdAt = getString("created_at")
    )

    private fun ResultSet.toSession() = Session(
        id = getInt("id"),
        userId = getInt("user_id"),
        token = getString("token"),
        createdAt = getString("created_at"),
        expiresAt = getString("expires_at")
    )
}
