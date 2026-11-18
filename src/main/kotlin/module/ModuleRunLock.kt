package org.iotsplab.akiba.module

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * One run of one module at a time.
 *
 * Runs are sequential by design: a second run of the same module against the same target
 * overwrites the first one's output on purpose. Two runs at the same time would interleave
 * writes, so the second one is refused instead.
 *
 * The lock records who holds it and under which process, because a killed process leaves the
 * file behind. A lock whose process is gone is stale, not a refusal.
 */
class ModuleRunLock private constructor(
    private val file: Path,
    val owner: String,
) {

    fun release() {
        try {
            Files.deleteIfExists(file)
        } catch (_: Exception) {
            // A lock that cannot be removed at the end is still removed by the stale check.
        }
    }

    companion object {
        private const val LOCK_NAME = ".akiba-run.lock"

        /**
         * Take the lock for [dir], or throw [ModuleBusyException] if someone else holds it.
         *
         * [owner] is whatever identifies the caller to a person: a session id, or `script`.
         */
        fun acquire(dir: Path, owner: String): ModuleRunLock {
            Files.createDirectories(dir)
            val file = dir.resolve(LOCK_NAME)

            repeat(2) {
                try {
                    val line = "$owner|${ProcessHandle.current().pid()}|${Instant.now()}"
                    Files.writeString(file, line, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    return ModuleRunLock(file, owner)
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    val held = read(file)
                    if (held != null && isAlive(held.second)) {
                        throw ModuleBusyException(dir, held.first, held.second)
                    }
                    // The holder is gone: this is a leftover, not a refusal.
                    try {
                        Files.deleteIfExists(file)
                    } catch (e: Exception) {
                        throw ModuleBusyException(dir, held?.first ?: "unknown", held?.second ?: -1)
                    }
                }
            }
            throw ModuleBusyException(dir, "unknown", -1)
        }

        private fun read(file: Path): Pair<String, Long>? = try {
            val parts = Files.readString(file).split('|')
            if (parts.size >= 2) parts[0] to (parts[1].toLongOrNull() ?: -1L) else null
        } catch (_: Exception) {
            null
        }

        private fun isAlive(pid: Long): Boolean =
            pid > 0 && ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    }
}

/** Thrown when a module is already running for the same target. */
class ModuleBusyException(
    val dir: Path,
    val owner: String,
    val pid: Long,
) : IllegalStateException(
    if (pid > 0) "This module is already running for this target (held by $owner, pid $pid)."
    else "This module is already running for this target (held by $owner).",
)
