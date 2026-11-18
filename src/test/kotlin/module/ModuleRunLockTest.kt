package org.iotsplab.akiba.module

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModuleRunLockTest {

    @Test
    fun `a second run of the same module is refused`() {
        val dir = Files.createTempDirectory("akiba-lock")
        val first = ModuleRunLock.acquire(dir, "session-a")

        val refused = assertFailsWith<ModuleBusyException> { ModuleRunLock.acquire(dir, "session-b") }
        assertEquals("session-a", refused.owner)
        assertTrue(refused.message!!.contains("session-a"), refused.message!!)

        first.release()
    }

    @Test
    fun `releasing lets the next run in`() {
        val dir = Files.createTempDirectory("akiba-lock")
        ModuleRunLock.acquire(dir, "session-a").release()
        val second = ModuleRunLock.acquire(dir, "session-b")
        second.release()
        assertTrue(!Files.exists(dir.resolve(".akiba-run.lock")))
    }

    @Test
    fun `a lock left by a dead process is taken over`() {
        val dir = Files.createTempDirectory("akiba-lock")
        // pid 0 can never be a live process, so this stands in for a killed run.
        Files.writeString(dir.resolve(".akiba-run.lock"), "busy|0|2026-01-01T00:00:00Z")

        val lock = ModuleRunLock.acquire(dir, "session-c")
        assertEquals("session-c", lock.owner)
        lock.release()
    }
}
