package org.iotsplab.akiba.server.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.iotsplab.akiba.server.db.Session
import org.iotsplab.akiba.server.db.UserDao
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import javax.crypto.SecretKey

object JwtService {
    private lateinit var secretKey: SecretKey
    private const val EXPIRATION_MS = 24 * 60 * 60 * 1000L // 24 hours

    fun init(secret: String) {
        val keyBytes = secret.toByteArray().let {
            if (it.size < 32) it + ByteArray(32 - it.size) else it
        }.copyOf(32)
        secretKey = Keys.hmacShaKeyFor(keyBytes)
    }

    fun generateToken(userId: Int): String {
        val now = Date()
        val expiration = Date(now.time + EXPIRATION_MS)
        return Jwts.builder()
            .subject(userId.toString())
            .issuedAt(now)
            .expiration(expiration)
            .signWith(secretKey)
            .compact()
    }

    fun validateToken(token: String): Session? {
        return try {
            val claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
            val userId = claims.payload.subject.toInt()
            val session = Session(
                id = 0,
                userId = userId,
                token = token,
                createdAt = "",
                expiresAt = ""
            )
            UserDao.getSession(token) ?: run {
                UserDao.createSession(userId, token, Instant.now().plus(EXPIRATION_MS, ChronoUnit.MILLIS))
                session
            }
        } catch (_: Exception) {
            null
        }
    }

    fun createSession(userId: Int, username: String): Pair<String, Session> {
        val token = generateToken(userId)
        val expiresAt = Instant.now().plus(EXPIRATION_MS, ChronoUnit.MILLIS)
        UserDao.createSession(userId, token, expiresAt)
        val session = Session(
            id = 0,
            userId = userId,
            token = token,
            createdAt = Instant.now().toString(),
            expiresAt = expiresAt.toString()
        )
        return token to session
    }

    fun invalidateToken(token: String) {
        UserDao.deleteSession(token)
    }
}
