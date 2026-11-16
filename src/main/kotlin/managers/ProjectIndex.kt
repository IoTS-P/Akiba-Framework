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
        /** Program identity, when it is already known; null for folders and
         *  for programs that have not been opened since the scheme existed. */
        val programId: String? = null,
        /** The binary row this program came from, when the walker knows it. */
        val binaryId: Long? = null,
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
            val tree = ProjectPrograms.tree(projectName, projectDirectory)
            // A project held open by a worker can read back empty without failing; an
            // empty read must not erase a tree the browser is showing.
            if (tree.isEmpty() && entries(projectName, projectDirectory).isNotEmpty()) {
                logger.warn("Project index refresh for '{}' read an empty tree; keeping the old one", projectName)
                return false
            }
            write(key, tree)
            true
        } catch (e: Throwable) {
            // Ghidra reports a failed static initializer as an Error, not an
            // Exception; the browser must degrade to a stale tree either way.
            logger.warn("Project index refresh for '{}' failed: {}", projectName, e.toString())
            false
        }
    }

    /** Last successful rebuild per project, so browsing does not reopen it every time. */
    private val lastRefresh = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val MIN_REFRESH_INTERVAL_MS = 30_000L

    /**
     * Replaces a project's indexed tree with one read by another process.
     *
     * A resident worker holds its project open for the whole session, so the server can
     * never read that tree itself — and the worker has no database pool. The worker
     * therefore walks the tree it already has open and hands it over here.
     */
    fun replace(projectName: String, projectDirectory: Path, entries: List<ProjectPrograms.Entry>): Boolean {
        if (!ServerDatabase.isInitialized) return false
        return try {
            if (entries.isEmpty() && entries(projectName, projectDirectory).isNotEmpty()) {
                logger.warn("Project index for '{}' replaced with an empty tree; keeping the old one", projectName)
                return false
            }
            write(projectKey(projectName, projectDirectory), entries)
            true
        } catch (e: Throwable) {
            logger.warn("Project index replace for '{}' failed: {}", projectName, e.toString())
            false
        }
    }

    /**
     * Rebuild the index when the project is free and the copy is older than
     * [MIN_REFRESH_INTERVAL_MS].
     *
     * Projects change outside Akiba too — a Ghidra tool can save a new program —
     * and without this the browser kept showing whatever the last mutation left
     * behind. A held project is skipped: opening it would fight the session.
     */
    fun refreshIfStale(projectName: String, projectDirectory: Path): Boolean {
        if (ProjectLock.owner(projectDirectory, projectName) != null) return false
        val key = projectKey(projectName, projectDirectory)
        val now = System.currentTimeMillis()
        val last = lastRefresh[key] ?: 0L
        if (now - last < MIN_REFRESH_INTERVAL_MS) return false
        lastRefresh[key] = now
        return refresh(projectName, projectDirectory)
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
                "SELECT path, name, folder, size, program_id, binary_id FROM project_entries " +
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
                            programId = rs.getString("program_id"),
                            binaryId = rs.getLong("binary_id").takeIf { !rs.wasNull() },
                        )
                    }
                    rows
                }
            }
        }
    }

    /** Drop every row of [projectName]; called when the project is deleted. */
    /** Record a program's identity after it was opened and stamped. */
    fun setProgramId(projectName: String, projectDirectory: Path, path: String, programId: String): Boolean {
        val dir = projectKey(projectName, projectDirectory)
        return runCatching {
            ServerDatabase.newConnection().use { conn ->
                conn.prepareStatement(
                    "UPDATE project_entries SET program_id = ?::uuid WHERE project_dir = ? AND path = ?"
                ).use { stmt ->
                    stmt.setString(1, programId)
                    stmt.setString(2, dir)
                    stmt.setString(3, path)
                    stmt.executeUpdate() > 0
                }
            }
        }.getOrElse {
            logger.warn("Could not record program id for '{}': {}", path, it.message)
            false
        }
    }

    /** Record the binary a program was imported from; import, copy and move only. */
    internal fun setBinaryId(projectName: String, projectDirectory: Path, path: String, binaryId: Long): Boolean {
        val dir = projectKey(projectName, projectDirectory)
        return runCatching {
            ServerDatabase.newConnection().use { conn ->
                conn.prepareStatement(
                    "UPDATE project_entries SET binary_id = ? WHERE project_dir = ? AND path = ?"
                ).use { stmt ->
                    stmt.setLong(1, binaryId)
                    stmt.setString(2, dir)
                    stmt.setString(3, path)
                    stmt.executeUpdate() > 0
                }
            }
        }.getOrElse {
            logger.warn("Could not record binary id for '{}': {}", path, it.message)
            false
        }
    }

    /** Path of the program carrying [programId], or null when it is gone. */
    fun pathOfProgram(projectName: String, projectDirectory: Path, programId: String): String? {
        val dir = projectKey(projectName, projectDirectory)
        return runCatching {
            ServerDatabase.newConnection().use { conn ->
                conn.prepareStatement(
                    "SELECT path FROM project_entries WHERE project_dir = ? AND program_id = ?::uuid LIMIT 1"
                ).use { stmt ->
                    stmt.setString(1, dir)
                    stmt.setString(2, programId)
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.getString("path") else null }
                }
            }
        }.getOrNull()
    }

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
                // Keep the ids of entries that are still there: the tree walk
                // cannot read program metadata cheaply, so it reports null and
                // the previous value is the only one we have.
                val known = mutableMapOf<String, String>()
                val knownBinary = mutableMapOf<String, Long>()
                conn.prepareStatement(
                    "SELECT path, program_id, binary_id FROM project_entries WHERE project_dir = ? " +
                        "AND (program_id IS NOT NULL OR binary_id IS NOT NULL)"
                ).use { stmt ->
                    stmt.setString(1, projectDir)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val path = rs.getString("path")
                            rs.getString("program_id")?.let { known[path] = it }
                            rs.getLong("binary_id").takeIf { !rs.wasNull() }?.let { knownBinary[path] = it }
                        }
                    }
                }
                conn.prepareStatement(
                    "DELETE FROM project_entries WHERE project_dir = ?"
                ).use { stmt ->
                    stmt.setString(1, projectDir)
                    stmt.executeUpdate()
                }
                conn.prepareStatement(
                    "INSERT INTO project_entries (project_dir, path, name, folder, size, program_id, binary_id) " +
                        "VALUES (?, ?, ?, ?, ?, ?::uuid, ?)"
                ).use { stmt ->
                    entries.forEach { entry ->
                        stmt.setString(1, projectDir)
                        stmt.setString(2, entry.path)
                        stmt.setString(3, entry.name)
                        stmt.setBoolean(4, entry.folder)
                        stmt.setLong(5, entry.size)
                        stmt.setString(6, entry.programId ?: known[entry.path])
                        // The walk cannot read program metadata, so a known id wins over nothing.
                        stmt.setObject(7, knownBinary[entry.path])
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
