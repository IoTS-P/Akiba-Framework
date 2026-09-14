package org.iotsplab.akiba.server.db

import org.iotsplab.akiba.server.security.LoginProtection
import org.mindrot.jbcrypt.BCrypt
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

data class User(
    val id: Int,
    val username: String,
    val passwordHash: String,
    /** Administrators may manage other users; the first account created is one. */
    val isAdmin: Boolean,
    /** Consecutive failed sign-ins; reset by a successful one or by an admin. */
    val failedLoginAttempts: Int = 0,
    val lastFailedLoginAt: Instant? = null,
    /** Set while the account is locked out; see [UserDao.isLocked]. */
    val lockedUntil: Instant? = null,
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

    /**
     * Throwaway hash used by [burnPasswordCheck] to keep the timing of a
     * rejected sign-in identical whether or not the username exists.
     * Verified against once at class load; nothing can ever match it.
     */
    private val DUMMY_HASH: String = BCrypt.hashpw("akiba-timing-equalizer", BCrypt.gensalt())

    fun createUser(username: String, password: String, isAdmin: Boolean = false): Int {
        val passwordHash = BCrypt.hashpw(password, BCrypt.gensalt())
        return ServerDatabase.getConnection().prepareStatement(
            "INSERT INTO users (username, password_hash, is_admin) VALUES (?, ?, ?) RETURNING id"
        ).use { stmt ->
            stmt.setString(1, username)
            stmt.setString(2, passwordHash)
            stmt.setBoolean(3, isAdmin)
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

    /** Number of registered accounts. Zero means the instance needs first-run setup. */
    fun countUsers(): Int {
        return ServerDatabase.getConnection().createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM users").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    fun countAdmins(): Int {
        return ServerDatabase.getConnection().createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM users WHERE is_admin").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    fun listUsers(): List<User> {
        return ServerDatabase.getConnection().createStatement().use { stmt ->
            stmt.executeQuery("SELECT * FROM users ORDER BY id").use { rs ->
                val users = mutableListOf<User>()
                while (rs.next()) users.add(rs.toUser())
                users
            }
        }
    }

    fun deleteUser(id: Int): Boolean {
        return ServerDatabase.getConnection().prepareStatement(
            "DELETE FROM users WHERE id = ?"
        ).use { stmt ->
            stmt.setInt(1, id)
            stmt.executeUpdate() > 0
        }
    }

    fun setPassword(id: Int, password: String): Boolean {
        val passwordHash = BCrypt.hashpw(password, BCrypt.gensalt())
        return ServerDatabase.getConnection().prepareStatement(
            // The administrator chose this password, so any previous lock and
            // failure count no longer describe the credentials on the account.
            "UPDATE users SET password_hash = ?, failed_login_attempts = 0, " +
                "last_failed_login_at = NULL, locked_until = NULL WHERE id = ?"
        ).use { stmt ->
            stmt.setString(1, passwordHash)
            stmt.setInt(2, id)
            stmt.executeUpdate() > 0
        }
    }

    fun setAdmin(id: Int, isAdmin: Boolean): Boolean {
        return ServerDatabase.getConnection().prepareStatement(
            "UPDATE users SET is_admin = ? WHERE id = ?"
        ).use { stmt ->
            stmt.setBoolean(1, isAdmin)
            stmt.setInt(2, id)
            stmt.executeUpdate() > 0
        }
    }

    fun verifyPassword(username: String, password: String): User? {
        val user = getUserByUsername(username) ?: return null
        return if (BCrypt.checkpw(password, user.passwordHash)) user else null
    }

    /** Verify [password] against an already-loaded row. */
    fun checkPassword(user: User, password: String): Boolean =
        BCrypt.checkpw(password, user.passwordHash)

    /**
     * Spend about as long as a real verification does.
     *
     * Without this, "no such user" would answer measurably faster than "wrong
     * password", which turns the login endpoint into a username oracle. The hash
     * is a throwaway, so no password can ever match it.
     */
    fun burnPasswordCheck(password: String) {
        BCrypt.checkpw(password, DUMMY_HASH)
    }

    /** Whether the account is currently locked out. */
    fun isLocked(user: User, now: Instant = Instant.now()): Boolean =
        user.lockedUntil?.isAfter(now) == true

    /**
     * Record a rejected sign-in and lock the account once
     * [LoginProtection.MAX_FAILED_ATTEMPTS] consecutive failures fall inside
     * [LoginProtection.FAILURE_WINDOW]. Failures older than the window start the
     * count over, so a user who mistypes once every few days is never locked.
     *
     * Returns the refreshed row so the caller can tell whether *this* attempt
     * locked the account.
     */
    fun recordFailedLogin(user: User): User {
        val now = Instant.now()
        val withinWindow = user.lastFailedLoginAt?.isAfter(now.minus(LoginProtection.FAILURE_WINDOW)) == true
        val attempts = if (withinWindow) user.failedLoginAttempts + 1 else 1
        val lockedUntil = if (attempts >= LoginProtection.MAX_FAILED_ATTEMPTS) {
            now.plus(LoginProtection.LOCK_DURATION)
        } else {
            user.lockedUntil
        }

        ServerDatabase.getConnection().prepareStatement(
            "UPDATE users SET failed_login_attempts = ?, last_failed_login_at = ?, locked_until = ? WHERE id = ?"
        ).use { stmt ->
            stmt.setInt(1, attempts)
            stmt.setObject(2, OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            if (lockedUntil != null) {
                stmt.setObject(3, OffsetDateTime.ofInstant(lockedUntil, ZoneOffset.UTC))
            } else {
                stmt.setNull(3, Types.TIMESTAMP_WITH_TIMEZONE)
            }
            stmt.setInt(4, user.id)
            stmt.executeUpdate()
        }

        return getUserById(user.id) ?: user
    }

    /**
     * Clear the failure counter and any lock. Called after a successful sign-in
     * and by administrators (unlock, password reset, account creation).
     */
    fun clearLoginFailures(id: Int): Boolean {
        return ServerDatabase.getConnection().prepareStatement(
            "UPDATE users SET failed_login_attempts = 0, last_failed_login_at = NULL, locked_until = NULL WHERE id = ?"
        ).use { stmt ->
            stmt.setInt(1, id)
            stmt.executeUpdate() > 0
        }
    }

    /** Administrator action: lift a lock without touching the password. */
    fun unlockUser(id: Int): Boolean = clearLoginFailures(id)

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
        isAdmin = getBoolean("is_admin"),
        failedLoginAttempts = getInt("failed_login_attempts"),
        lastFailedLoginAt = getTimestamp("last_failed_login_at")?.toInstant(),
        lockedUntil = getTimestamp("locked_until")?.toInstant(),
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
