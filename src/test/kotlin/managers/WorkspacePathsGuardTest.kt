package org.iotsplab.akiba.managers

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The guard is the only thing between a URL and the host filesystem, so every way out of the
 * workspace is pinned down here.
 */
class WorkspacePathsGuardTest {

    private fun session(): Path = Files.createTempDirectory("ws-guard").also {
        Files.createDirectories(it.resolve("notes"))
        Files.writeString(it.resolve("notes/todo.txt"), "hi")
    }

    @Test
    fun `an ordinary path inside the workspace resolves`() {
        val root = session()
        val p = WorkspacePaths.resolveInside(root, "notes/todo.txt")
        assertNotNull(p)
        assertEquals(root.resolve("notes/todo.txt").normalize(), p)
    }

    @Test
    fun `a file that does not exist yet still resolves inside`() {
        val root = session()
        assertNotNull(WorkspacePaths.resolveInside(root, "notes/new.txt"))
    }

    @Test
    fun `climbing out with dot-dot is refused`() {
        val root = session()
        assertNull(WorkspacePaths.resolveInside(root, "../secret"))
        assertNull(WorkspacePaths.resolveInside(root, "notes/../../secret"))
    }

    @Test
    fun `an absolute path is treated as relative and cannot escape`() {
        val root = session()
        val p = WorkspacePaths.resolveInside(root, "/etc/passwd")
        assertNotNull(p)
        assertEquals(root.resolve("etc/passwd").normalize(), p)
    }

    @Test
    fun `a NUL byte is refused`() {
        val root = session()
        assertNull(WorkspacePaths.resolveInside(root, "notes/todo.txt\u0000.png"))
    }

    @Test
    fun `a symlink pointing outside is refused`() {
        val root = session()
        val outside = Files.createTempDirectory("ws-outside")
        Files.writeString(outside.resolve("secret.txt"), "secret")
        Files.createSymbolicLink(root.resolve("escape"), outside.resolve("secret.txt"))

        assertNull(WorkspacePaths.resolveInside(root, "escape"))
    }

    @Test
    fun `the binary link is the one exception`() {
        val root = session()
        val programBinary = Files.createTempFile("binary-", ".bin")
        Files.createSymbolicLink(root.resolve("binary"), programBinary)

        assertNotNull(WorkspacePaths.resolveInside(root, "binary"))
    }

    @Test
    fun `a link named binary still cannot be used to reach a subpath`() {
        val root = session()
        val outside = Files.createTempDirectory("ws-outside")
        Files.createDirectory(outside.resolve("inner"))
        Files.createSymbolicLink(root.resolve("binary"), outside.resolve("inner"))

        assertNull(WorkspacePaths.resolveInside(root, "binary/secret"))
    }
}
