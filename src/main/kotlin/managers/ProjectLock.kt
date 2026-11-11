package org.iotsplab.akiba.managers

import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.utils.AkibaJson
import org.iotsplab.akiba.utils.GlobalConfig
import org.iotsplab.akiba.utils.ProjectLockSettings

private val logger: Logger = LogManager.getLogger("ProjectLock")

/** Who is using a project right now — for display and diagnostics only. */
data class ProjectLockOwner(
    val pid: Long,
    val sessionId: String? = null,
    val instance: String? = null,
    val acquiredAt: String = now(),
    val heartbeatAt: String = now(),
) {
    companion object {
        fun now(): String = OffsetDateTime.now(ZoneOffset.UTC).toString()
    }
}

/**
 * Thrown when a project is held by somebody else and the caller chose not to wait.
 *
 * Deliberately NOT an [IllegalStateException]: handlers that translate a state
 * error into "not configured" were swallowing it and reporting the lock as a
 * configuration fault.
 */
class ProjectBusyException(
    val projectName: String,
    val owner: ProjectLockOwner?,
    cause: Throwable? = null,
) : Exception(
    buildString {
        append("Project '").append(projectName).append("' is in use")
        owner?.let {
            append(" by session ").append(it.sessionId ?: "?")
            append(" (pid ").append(it.pid).append(", since ").append(it.acquiredAt).append(")")
        }
    },
    cause,
)

/**
 * Mutual exclusion for Ghidra projects.
 *
 * The lock itself is Ghidra's own `FileChannel` lock, taken when a project is
 * opened: it is released by the operating system if the process dies, so a crash
 * cannot leave a project unusable. This object adds what Ghidra does not record —
 * which session holds it — and applies the deployment's wait/refuse policy.
 *
 * Akiba's server keeps one Ghidra handle per process, so every open here is
 * serialized by [gate]; that serializes *opening*, not the work done while a
 * project is open.
 */
object ProjectLock {

    private const val OWNER_SUFFIX = ".lock.owner.json"
    private const val POLL_INTERVAL_MS = 500L

    private val gate = Mutex()

    fun ownerFile(projectDirectory: Path, projectName: String): Path =
        projectDirectory.resolve("$projectName$OWNER_SUFFIX")

    /** Current owner, or `null` when the project is free. Stale owners are removed. */
    fun owner(projectDirectory: Path, projectName: String): ProjectLockOwner? {
        val file = ownerFile(projectDirectory, projectName)
        if (!file.exists()) return null
        val parsed = runCatching {
            AkibaJson.mapper.readValue(file.toFile(), ProjectLockOwner::class.java)
        }.getOrNull()

        if (parsed != null && isAlive(parsed)) return parsed
        // The process that wrote this is gone; the channel lock died with it.
        runCatching { file.deleteIfExists() }
        return null
    }

    /**
     * Create or open [projectName] once, then close it again.
     *
     * Used when a request only needs to prove the project exists or to learn
     * whether it is free — holding it any longer would block the worker that is
     * about to open the same project.
     */
    fun touch(projectName: String, projectDirectory: Path): ProjectBusyException? =
        runBlocking {
            gate.withLock {
                try {
                    WorkspaceManager.openOrCreateInteractiveProject(
                        projectName, false, projectDirectory
                    )
                    WorkspaceManager.releaseActiveProject()
                    null
                } catch (e: Exception) {
                    ProjectBusyException(projectName, owner(projectDirectory, projectName), e)
                }
            }
        }

    /**
     * Run [block] while recording this process as the project's owner.
     *
     * The Ghidra lock is deliberately *not* held here: [block] starts the worker
     * process, and that worker has to open the project itself. The owner file only
     * tells the next caller who to ask (and lets a crash be spotted), while the
     * channel lock remains the only judge of availability.
     */
    fun <T> asOwner(
        projectName: String,
        projectDirectory: Path,
        sessionId: String?,
        instance: String?,
        block: () -> T,
    ): T {
        val settings = GlobalConfig.load().projectLock
        awaitFree(projectName, projectDirectory, settings)

        val owner = ProjectLockOwner(
            pid = ProcessHandle.current().pid(),
            sessionId = sessionId,
            instance = instance,
        )
        writeOwner(projectDirectory, projectName, owner)
        return try {
            block()
        } finally {
            runCatching { ownerFile(projectDirectory, projectName).deleteIfExists() }
        }
    }

    /** Remove owner files whose process no longer exists. */
    fun sweepStaleOwners(projectDirectory: Path) {
        if (!Files.isDirectory(projectDirectory)) return
        Files.newDirectoryStream(projectDirectory, "*$OWNER_SUFFIX").use { stream ->
            stream.forEach { file ->
                val parsed = runCatching {
                    AkibaJson.mapper.readValue(file.toFile(), ProjectLockOwner::class.java)
                }.getOrNull()
                if (parsed == null || !isAlive(parsed)) {
                    runCatching { file.deleteIfExists() }
                    logger.info("Removed stale owner file {}", file.fileName)
                }
            }
        }
    }

    private fun awaitFree(
        projectName: String,
        projectDirectory: Path,
        settings: ProjectLockSettings,
    ) {
        val deadline = System.nanoTime() + settings.waitTimeoutSeconds.coerceAtLeast(0) * 1_000_000_000L
        while (true) {
            touch(projectName, projectDirectory)?.let { busy ->
                if (!settings.waits || System.nanoTime() > deadline) throw busy
                runBlocking { delay(POLL_INTERVAL_MS) }
                return@let
            } ?: return
        }
    }

    private fun writeOwner(projectDirectory: Path, projectName: String, owner: ProjectLockOwner) {
        runCatching {
            Files.createDirectories(projectDirectory)
            AkibaJson.mapper.writerWithDefaultPrettyPrinter()
                .writeValue(ownerFile(projectDirectory, projectName).toFile(), owner)
        }.onFailure { logger.warn("Could not record project owner: ${it.message}") }
    }

    private fun isAlive(owner: ProjectLockOwner): Boolean =
        ProcessHandle.of(owner.pid).map { it.isAlive }.orElse(false)
}
