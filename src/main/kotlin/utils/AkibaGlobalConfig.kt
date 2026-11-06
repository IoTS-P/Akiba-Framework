package org.iotsplab.akiba.utils

import java.nio.file.Files
import java.nio.file.Path

/**
 * Deployment-wide settings that do not belong to a single instance.
 *
 * Kept in `~/.akiba/akiba.conf.json` rather than in the per-instance config: lock
 * behaviour answers "how does this deployment behave", which is the same question
 * for every analysis instance.
 */
data class AkibaGlobalConfig(
    val projectLock: ProjectLockSettings = ProjectLockSettings(),
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
