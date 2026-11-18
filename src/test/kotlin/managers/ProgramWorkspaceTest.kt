package org.iotsplab.akiba.managers

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgramWorkspaceTest {

    private fun seed(root: String): java.nio.file.Path {
        val dir = WorkspacePaths.programRoot(root, "test", "/foo/bar.elf")
        Files.createDirectories(dir.resolve("ZeroTrimmer"))
        Files.writeString(dir.resolve("ZeroTrimmer/out.txt"), "blocks=31")
        return dir
    }

    @Test
    fun `deleting a program removes its files`() {
        val root = Files.createTempDirectory("ws").toString()
        val dir = seed(root)
        assertTrue(ProgramWorkspace.delete(root, "test", "/foo/bar.elf"))
        assertFalse(Files.exists(dir))
    }

    @Test
    fun `deleting a program that has no files is not an error`() {
        val root = Files.createTempDirectory("ws").toString()
        assertTrue(ProgramWorkspace.delete(root, "test", "/nothing/here.elf"))
    }

    @Test
    fun `moving a program carries its files and cleans up the empty folder`() {
        val root = Files.createTempDirectory("ws").toString()
        seed(root)
        val from = ProgramWorkspace.dir(root, "test", "/foo/bar.elf")
        val to = ProgramWorkspace.dir(root, "test", "/baz/bar.elf")

        assertTrue(ProgramWorkspace.move(from, to))
        assertTrue(Files.exists(to.resolve("ZeroTrimmer/out.txt")))
        assertFalse(Files.exists(from))
        assertFalse(Files.exists(from.parent), "the now-empty foo/ folder should be gone")
    }

    @Test
    fun `moving onto an existing program is refused rather than merged`() {
        val root = Files.createTempDirectory("ws").toString()
        seed(root)
        val other = WorkspacePaths.programRoot(root, "test", "/baz/bar.elf")
        Files.createDirectories(other)

        assertFalse(ProgramWorkspace.move(
            ProgramWorkspace.dir(root, "test", "/foo/bar.elf"), other))
        assertTrue(Files.exists(ProgramWorkspace.dir(root, "test", "/foo/bar.elf")))
    }

    @Test
    fun `copying a workspace leaves the original in place`() {
        val root = Files.createTempDirectory("ws").toString()
        seed(root)
        val from = ProgramWorkspace.dir(root, "test", "/foo/bar.elf")
        val to = ProgramWorkspace.dir(root, "other", "/foo/bar-copy.elf")

        assertTrue(ProgramWorkspace.copy(from, to))
        assertTrue(Files.exists(to.resolve("ZeroTrimmer/out.txt")))
        assertEquals("blocks=31", Files.readString(to.resolve("ZeroTrimmer/out.txt")))
        assertTrue(Files.exists(from.resolve("ZeroTrimmer/out.txt")))
    }
}
