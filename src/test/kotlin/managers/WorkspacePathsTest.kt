package org.iotsplab.akiba.managers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspacePathsTest {

    @Test
    fun `program path becomes a directory tree under programs`() {
        val root = WorkspacePaths.programRoot("/ws", "test", "/foo/bar.elf")
        assertTrue(root.toString().endsWith("test/programs/foo/bar.elf"), root.toString())
    }

    @Test
    fun `a program without a path still gets a directory`() {
        assertEquals(listOf("_"), WorkspacePaths.programSegments(""))
        assertEquals(listOf("_"), WorkspacePaths.programSegments("/"))
    }

    @Test
    fun `sessions sit beside programs, not inside them`() {
        val session = WorkspacePaths.sessionRoot("/ws", "test", "3f2a-11")
        val program = WorkspacePaths.programRoot("/ws", "test", "/foo/bar.elf")
        assertTrue(session.toString().endsWith("test/sessions/3f2a-11"), session.toString())
        assertTrue(!session.startsWith(program), "$session must not live under $program")
    }

    @Test
    fun `separators and traversal never reach the filesystem`() {
        assertEquals("a_b_c", WorkspacePaths.sanitizeSegment("a/b\\c"))
        assertEquals("_", WorkspacePaths.sanitizeSegment(".."))
        assertEquals("_", WorkspacePaths.sanitizeSegment("."))
        assertEquals("_", WorkspacePaths.sanitizeSegment("   "))
        assertTrue(!WorkspacePaths.sanitizeSegment("../../etc/passwd").contains(".."))
    }

    @Test
    fun `spaces become underscores so paths stay quotable`() {
        assertEquals("my_program.elf", WorkspacePaths.sanitizeSegment("my program.elf"))
    }

    @Test
    fun `a long name is cut to a length filesystems accept`() {
        val long = "x".repeat(400)
        assertEquals(120, WorkspacePaths.sanitizeSegment(long).length)
    }

    @Test
    fun `conversation output is timestamped, script output is not`() {
        val scope = WorkspacePaths.sessionRoot("/ws", "test", "s1")
        val stamped = WorkspacePaths.moduleOutput(scope, "ZeroTrimmer", timestamped = true, stamp = "20261118-1530")
        val plain = WorkspacePaths.moduleOutput(scope.resolveSibling("p"), "ZeroTrimmer", timestamped = false, stamp = "")

        assertTrue(stamped.toString().endsWith("sessions/s1/ZeroTrimmer/20261118-1530"), stamped.toString())
        assertTrue(plain.toString().endsWith("p/ZeroTrimmer"), plain.toString())
    }
}
