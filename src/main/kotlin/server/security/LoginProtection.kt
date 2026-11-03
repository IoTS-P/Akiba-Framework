package org.iotsplab.akiba.server.security

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay

/**
 * Brute-force protection for the sign-in endpoint.
 *
 * Every rejected sign-in — wrong password *or* unknown username — waits for
 * [FAILURE_DELAY]; [MAX_FAILED_ATTEMPTS] failures within [FAILURE_WINDOW] lock
 * the account for [LOCK_DURATION], releasable early by an administrator.
 *
 * Counters live in the `users` table ([org.iotsplab.akiba.server.db.UserDao]) and
 * survive a restart; usernames with no row are counted in memory instead.
 */
object LoginProtection {

    /** How long a rejected sign-in is held back before the client is answered. */
    val FAILURE_DELAY: Duration = Duration.ofSeconds(3)

    /** Consecutive failures that lock the account. */
    const val MAX_FAILED_ATTEMPTS: Int = 5

    /** Failures older than this do not count towards the next lock. */
    val FAILURE_WINDOW: Duration = Duration.ofHours(24)

    /** How long an account stays locked once it is locked. */
    val LOCK_DURATION: Duration = Duration.ofHours(24)

    /**
     * Hold the request back before the failure answer is sent. Suspending on
     * purpose: the delay must not occupy a thread, and it must happen *before*
     * the response so a caller cannot pipeline guesses.
     */
    suspend fun awaitFailureDelay() {
        delay(FAILURE_DELAY.toMillis())
    }

    private class Attempts(var count: Int, var lastFailure: Instant)

    private val unknownUserFailures = ConcurrentHashMap<String, Attempts>()

    /** Remember a rejected sign-in for a username that has no account. */
    fun noteUnknownUserFailure(username: String) {
        val now = Instant.now()
        unknownUserFailures.compute(username) { _, existing ->
            if (existing != null && existing.lastFailure.isAfter(now.minus(FAILURE_WINDOW))) {
                existing.count += 1
                existing.lastFailure = now
                existing
            } else {
                Attempts(1, now)
            }
        }
        if (unknownUserFailures.size > MAX_TRACKED_UNKNOWN_USERS) pruneUnknownUserFailures()
    }

    /**
     * Whether guessing this unknown username has already been rejected
     * [MAX_FAILED_ATTEMPTS] times inside [FAILURE_WINDOW]. Such a name has no
     * account to lock, so the caller keeps getting the ordinary rejection — the
     * counters exist to keep the bookkeeping bounded and to make the pattern
     * visible in the logs.
     */
    fun isUnknownUserThrottled(username: String): Boolean {
        val attempts = unknownUserFailures[username] ?: return false
        if (attempts.lastFailure.isBefore(Instant.now().minus(FAILURE_WINDOW))) {
            unknownUserFailures.remove(username)
            return false
        }
        return attempts.count >= MAX_FAILED_ATTEMPTS
    }

    /** Failure count currently recorded for an unknown username (0 when none). */
    fun unknownUserFailureCount(username: String): Int =
        unknownUserFailures[username]?.count ?: 0

    fun clearUnknownUserFailures(username: String) {
        unknownUserFailures.remove(username)
    }

    private const val MAX_TRACKED_UNKNOWN_USERS = 10_000

    private fun pruneUnknownUserFailures() {
        val cutoff = Instant.now().minus(FAILURE_WINDOW)
        unknownUserFailures.entries.removeIf { it.value.lastFailure.isBefore(cutoff) }
    }
}
