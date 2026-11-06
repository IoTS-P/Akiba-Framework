package org.iotsplab.akiba.managers

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.iotsplab.akiba.utils.AkibaJson

/**
 * Owner notes are bookkeeping, not the lock — Ghidra's channel lock decides who
 * may open a project. What matters here is that a note left behind by a dead
 * process disappears instead of making a free project look busy.
 */
class ProjectLockOwnerTest {

    private fun projectDir(): Path = createTempDirectory("akiba-project-lock")

    private fun writeOwner(dir: Path, name: String, pid: Long) {
        val owner = ProjectLockOwner(pid = pid, sessionId = "session-$pid", instance = "eval")
        AkibaJson.mapper.writeValue(ProjectLock.ownerFile(dir, name).toFile(), owner)
    }

    @Test
    fun `a live owner is reported`() {
        val dir = projectDir()
        writeOwner(dir, "demo", ProcessHandle.current().pid())

        val owner = ProjectLock.owner(dir, "demo")

        assertNotNull(owner)
        assertEquals(ProcessHandle.current().pid(), owner!!.pid)
    }

    @Test
    fun `an owner whose process is gone is dropped`() {
        val dir = projectDir()
        // PID 1 exists on Linux, so use a value the OS cannot have handed out.
        writeOwner(dir, "demo", Long.MAX_VALUE)

        val owner = ProjectLock.owner(dir, "demo")

        assertNull(owner)
        assertTrue(!ProjectLock.ownerFile(dir, "demo").exists())
    }

    @Test
    fun `sweep removes only the stale notes`() {
        val dir = projectDir()
        writeOwner(dir, "alive", ProcessHandle.current().pid())
        writeOwner(dir, "dead", Long.MAX_VALUE)

        ProjectLock.sweepStaleOwners(dir)

        assertTrue(Files.exists(ProjectLock.ownerFile(dir, "alive")))
        assertTrue(!Files.exists(ProjectLock.ownerFile(dir, "dead")))
    }

    @Test
    fun `a project with no note has no owner`() {
        assertNull(ProjectLock.owner(projectDir(), "nothing"))
    }
}
