package org.iotsplab.akiba.managers

import java.nio.file.Files
import java.nio.file.Path
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.server.db.ServerDatabase

private val logger: Logger = LogManager.getLogger("ProjectIndex")

/**
 * Database-backed index of every project's file tree.
 *
 * Ghidra keeps an exclusive channel lock while a project is open, so the
 * Projects browser cannot read a tree an agent session is using. Rows are
 * written at the moments a project is open anyway (a mutation, a copy, a
 * session starting) and read on their own the rest of the time.
 *
 * Rows are keyed by the project's absolute directory, not by its name: two
 * instances can own a project of the same name and must not share a tree.
 */
object ProjectIndex {

    /** One indexed entry, shaped exactly like the tree route's rows. */
    data class Entry(
        val path: String,
        val name: String,
        val folder: Boolean,
        val size: Long,
    )

    /**
     * Replace the indexed tree of [projectName] with the project's current one.
     *
     * Returns false instead of throwing when the project is held elsewhere,
     * missing, or the database is unavailable: the previous rows stay in place,
     * which is all a browser showing a slightly old tree needs.
     */
    fun refresh(projectName: String, projectDirectory: Path): Boolean {
        if (!ServerDatabase.isInitialized) return false
        // Reading a tree opens the project, which needs the Ghidra application
        // that a session would otherwise have initialized first.
        if (!WorkspaceManager.initializeGhidra()) {
            logger.warn("Project index refresh for '{}' skipped: Ghidra is not available", projectName)
            return false
        }
        return try {
            val key = projectKey(projectName, projectDirectory)
            write(key, ProjectPrograms.tree(projectName, projectDirectory))
            true
        } catch (e: Throwable) {
            // Ghidra reports a failed static initializer as an Error, not an
            // Exception; the browser must degrade to a stale tree either way.
            logger.warn("Project index refresh for '{}' failed: {}", projectName, e.toString())
            false
        }
    }

    /**
     * The indexed tree of [projectName], folders first and then by path.
     *
     * @throws IllegalStateException when the server database is not available;
     *         callers that must not fail answer with an empty, stale tree.
     */
    fun entries(projectName: String, projectDirectory: Path): List<Entry> {
        check(ServerDatabase.isInitialized) { "Server database is not initialized" }
        val dir = projectKey(projectName, projectDirectory)
        return ServerDatabase.newConnection().use { conn ->
            conn.prepareStatement(
                "SELECT path, name, folder, size FROM project_entries " +
                    "WHERE project_dir = ? ORDER BY folder DESC, path"
            ).use { stmt ->
                stmt.setString(1, dir)
                stmt.executeQuery().use { rs ->
                    val rows = mutableListOf<Entry>()
                    while (rs.next()) {
                        rows += Entry(
                            path = rs.getString("path"),
                            name = rs.getString("name"),
                            folder = rs.getBoolean("folder"),
                            size = rs.getLong("size"),
                        )
                    }
                    rows
                }
            }
        }
    }

    /** Drop every row of [projectName]; called when the project is deleted. */
    fun forget(projectName: String, projectDirectory: Path): Boolean {
        if (!ServerDatabase.isInitialized) return false
        val dir = projectKey(projectName, projectDirectory)
        return try {
            ServerDatabase.newConnection().use { conn ->
                conn.prepareStatement(
                    "DELETE FROM project_entries WHERE project_dir = ?"
                ).use { stmt ->
                    stmt.setString(1, dir)
                    stmt.executeUpdate()
                }
            }
            true
        } catch (e: Exception) {
            logger.warn("Project index forget for '{}' failed: {}", projectName, e.message)
            false
        }
    }

    /**
     * Drop the rows of projects whose `.gpr` no longer exists under
     * [projectDirectory].
     *
     * Run at server start, where a project deleted while the server was down
     * would otherwise keep its tree in the browser forever.
     *
     * @return how many projects were removed from the index.
     */
    fun reconcile(projectDirectory: Path): Int {
        if (!ServerDatabase.isInitialized) return 0
        if (!Files.isDirectory(projectDirectory)) {
            logger.warn("Project index reconcile skipped: '{}' is not a directory", projectDirectory)
            return 0
        }
        val live = try {
            Files.newDirectoryStream(projectDirectory, "*.gpr").use { stream ->
                stream.map { projectKey(it.fileName.toString().removeSuffix(".gpr"), projectDirectory) }
                    .toSet()
            }
        } catch (e: Exception) {
            logger.warn("Project index reconcile cannot list '{}': {}", projectDirectory, e.message)
            return 0
        }
        return try {
            val indexed = ServerDatabase.newConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT DISTINCT project_dir FROM project_entries").use { rs ->
                        val dirs = mutableListOf<String>()
                        while (rs.next()) dirs += rs.getString(1)
                        dirs
                    }
                }
            }
            val gone = indexed - live
            gone.forEach { dir ->
                ServerDatabase.newConnection().use { conn ->
                    conn.prepareStatement("DELETE FROM project_entries WHERE project_dir = ?").use { stmt ->
                        stmt.setString(1, dir)
                        stmt.executeUpdate()
                    }
                }
            }
            if (gone.isNotEmpty()) {
                logger.info("Project index reconcile dropped {} deleted project(s)", gone.size)
            }
            gone.size
        } catch (e: Exception) {
            logger.warn("Project index reconcile failed: {}", e.message)
            0
        }
    }

    /** Absolute path of the project itself; the identity of its index rows. */
    private fun projectKey(projectName: String, projectDirectory: Path): String =
        projectDirectory.toAbsolutePath().normalize().resolve(projectName).toString()

    /** Delete then bulk-insert [entries] in one transaction on its own connection. */
    private fun write(projectDir: String, entries: List<ProjectPrograms.Entry>) {
        ServerDatabase.newConnection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    "DELETE FROM project_entries WHERE project_dir = ?"
                ).use { stmt ->
                    stmt.setString(1, projectDir)
                    stmt.executeUpdate()
                }
                conn.prepareStatement(
                    "INSERT INTO project_entries (project_dir, path, name, folder, size) " +
                        "VALUES (?, ?, ?, ?, ?)"
                ).use { stmt ->
                    entries.forEach { entry ->
                        stmt.setString(1, projectDir)
                        stmt.setString(2, entry.path)
                        stmt.setString(3, entry.name)
                        stmt.setBoolean(4, entry.folder)
                        stmt.setLong(5, entry.size)
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
                conn.commit()
            } catch (e: Exception) {
                // A half-written tree would be worse than the old one.
                runCatching { conn.rollback() }
                throw e
            }
        }
    }
}
