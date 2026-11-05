package org.iotsplab.akiba.server.db

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import org.mindrot.jbcrypt.BCrypt

/**
 * The operator account.
 *
 * Akiba serves a single operator, so this row is the only credential in the
 * system and there is no account management on top of it: [UserDao.setPassword]
 * is the whole editing surface.
 */
data class User(
    val id: Int,
    val username: String,
    val passwordHash: String,
    val createdAt: String,
)

data class Session(
    val id: Int,
    val userId: Int,
    val token: String,
    val createdAt: String,
    val expiresAt: String,
)

object UserDao {

    /**
     * Throwaway hash used by [burnPasswordCheck] to keep the timing of a
     * rejected sign-in identical whether or not the account row exists.
     * Verified against once at class load; nothing can ever match it.
     */
    private val DUMMY_HASH: String = BCrypt.hashpw("akiba-timing-equalizer", BCrypt.gensalt())

    fun createUser(username: String, password: String): Int {
        val passwordHash = BCrypt.hashpw(password, BCrypt.gensalt())
        return ServerDatabase.getConnection().prepareStatement(
            "INSERT INTO users (username, password_hash, is_admin) VALUES (?, ?, TRUE) RETURNING id"
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

    /** Load the operator row; the session stores the id, not the name. */
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

    /** Zero means the deployment still needs its password to be set. */
    fun countUsers(): Int {
        return ServerDatabase.getConnection().createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM users").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    fun setPassword(id: Int, password: String): Boolean {
        val passwordHash = BCrypt.hashpw(password, BCrypt.gensalt())
        return ServerDatabase.getConnection().prepareStatement(
            "UPDATE users SET password_hash = ? WHERE id = ?"
        ).use { stmt ->
            stmt.setString(1, passwordHash)
            stmt.setInt(2, id)
            stmt.executeUpdate() > 0
        }
    }

    /** Verify [password] against an already-loaded row. */
    fun checkPassword(user: User, password: String): Boolean =
        BCrypt.checkpw(password, user.passwordHash)

    /** Spend about as long as a real verification does; see [DUMMY_HASH]. */
    fun burnPasswordCheck(password: String) {
        BCrypt.checkpw(password, DUMMY_HASH)
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
        createdAt = getString("created_at"),
    )

    private fun ResultSet.toSession() = Session(
        id = getInt("id"),
        userId = getInt("user_id"),
        token = getString("token"),
        createdAt = getString("created_at"),
        expiresAt = getString("expires_at"),
    )
}
