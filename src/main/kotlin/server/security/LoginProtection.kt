package org.iotsplab.akiba.server.security

import java.time.Duration
import kotlinx.coroutines.delay

/**
 * Delay applied to every rejected sign-in.
 *
 * Akiba has a single operator account, so a lockout would let anyone brick the
 * only way in. A fixed delay bounds guessing instead: each attempt costs three
 * seconds of server time, which leaves no room for a high-rate sweep while
 * keeping a mistyped password from disabling the deployment.
 */
object LoginProtection {

    /** How long a rejected sign-in is held back before the client is answered. */
    val FAILURE_DELAY: Duration = Duration.ofSeconds(3)

    /**
     * Hold the request back before the failure answer is sent. Suspending on
     * purpose: the delay must not occupy a thread, and it must happen *before*
     * the response so a caller cannot pipeline guesses.
     */
    suspend fun awaitFailureDelay() {
        delay(FAILURE_DELAY.toMillis())
    }
}
