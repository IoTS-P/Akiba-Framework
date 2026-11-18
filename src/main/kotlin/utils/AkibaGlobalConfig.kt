package org.iotsplab.akiba.utils

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.apache.logging.log4j.LogManager

/**
 * Deployment-wide settings that do not belong to a single instance.
 *
 * Kept in `~/.akiba/akiba.conf.json` rather than in the per-instance config: lock
 * behaviour answers "how does this deployment behave", which is the same question
 * for every analysis instance.
 */
data class AkibaGlobalConfig(
    val projectLock: ProjectLockSettings = ProjectLockSettings(),
    val agent: AgentSettings = AgentSettings(),
)

/**
 * How long a tool call may run, and when a person has to agree to more.
 *
 * This answers "how does this deployment behave" for every session on it, which is why it
 * lives here and not in the per-run config.
 */
data class AgentSettings(
    /** Every call stops at this many seconds unless the tool asks for something else. */
    val toolTimeoutSeconds: Int = 300,
    /** A call asking for longer than this waits for the user's approval first. */
    val toolTimeoutConfirmThresholdSeconds: Int = 3600,
    /** How long that approval waits before the call counts as declined. */
    val toolTimeoutApprovalSeconds: Int = 300,
    /** Nothing may ask for more than this, approved or not. */
    val maxToolTimeoutSeconds: Int = 86400,
)

/**
 * How a busy project is treated.
 *
 * [mode] is `refuse` (answer immediately, telling the caller who holds the
 * project) or `wait` (poll until [waitTimeoutSeconds] expires, then refuse).
 */
data class ProjectLockSettings(
    val mode: String = MODE_REFUSE,
    val waitTimeoutSeconds: Int = 30,
) {
    val waits: Boolean get() = mode.equals(MODE_WAIT, ignoreCase = true)

    companion object {
        const val MODE_REFUSE = "refuse"
        const val MODE_WAIT = "wait"
    }
}

object GlobalConfig {

    private val configPath: Path =
        Path.of(System.getProperty("user.home"), ".akiba", "akiba.conf.json")

    @Volatile
    private var cached: AkibaGlobalConfig? = null

    /** Current settings; a missing or unreadable file means the defaults. */
    fun load(): AkibaGlobalConfig {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: read().also { cached = it }
        }
    }

    /** Forget the cached copy, so the next [load] re-reads the file. */
    fun reload() {
        synchronized(this) { cached = null }
    }

    private fun read(): AkibaGlobalConfig {
        if (!Files.isRegularFile(configPath)) return AkibaGlobalConfig()
        return runCatching {
            AkibaJson.mapper.readValue(configPath.toFile(), AkibaGlobalConfig::class.java)
        }.getOrElse { AkibaGlobalConfig() }
    }
}

/**
 * Shared secret that authenticates co-located JVMs to the server's internal
 * endpoints.  A worker has no user session, so the server mints a random token
 * at startup and publishes it in `~/.akiba/akiba_internal.token`; every worker
 * reads that file.  A file reaches launch paths that inherit no environment,
 * such as workflow module processes.  Rotating per start invalidates old workers.
 */
object InternalToken {

    /** Request header that carries the secret. */
    const val HEADER = "X-Akiba-Internal"

    private val logger = LogManager.getLogger(InternalToken::class.java)
    private val random = SecureRandom()

    /** `~/.akiba/akiba_internal.token` — shared by every co-located JVM. */
    val file: Path by lazy {
        Path.of(System.getProperty("user.home"), ".akiba", "akiba_internal.token")
    }

    /** Token minted by [rotate] in this JVM; null in worker JVMs. */
    @Volatile private var minted: String? = null

    /** Worker-side cache so a chunk stream does not re-read the file per chunk. */
    @Volatile private var cachedFileToken: String? = null

    /**
     * Mint a fresh token and write it to [file], readable by the owner only.
     *
     * Called once at server startup, before the HTTP listener opens.  A write
     * failure is logged rather than thrown: the server keeps running and only
     * cross-process chunk pushes are lost.
     *
     * @return the token, also kept in memory for this JVM.
     */
    fun rotate(): String {
        val token = ByteArray(32)
            .also { random.nextBytes(it) }
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        minted = token
        runCatching {
            Files.createDirectories(file.parent)
            // Create with the final mode: a world-readable window, however
            // short, would let any local user push chunks as the server.
            val ownerOnly = PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rw-------")
            )
            val channel = Files.newByteChannel(
                file,
                setOf(
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                ),
                ownerOnly,
            )
            try {
                channel.write(ByteBuffer.wrap(token.toByteArray()))
            } finally {
                channel.close()
            }
            // Creation attributes apply only to a new file; an existing one
            // keeps whatever mode it had.
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        }.onFailure {
            logger.warn("Could not write internal token file ${file}: ${it.message}")
        }
        return token
    }

    /** Token of this JVM if it is the server, otherwise the one in [file]. */
    fun readOrNull(): String? = minted ?: cachedFileToken ?: readFileToken()

    /** True when [candidate] equals the current internal token. */
    fun matches(candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        val expected = readOrNull() ?: return false
        return MessageDigest.isEqual(expected.toByteArray(), candidate.toByteArray())
    }

    private fun readFileToken(): String? {
        val token = runCatching {
            Files.readString(file).trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
        if (token != null) cachedFileToken = token
        return token
    }
}
