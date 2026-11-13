package org.iotsplab.akiba.plan

import org.iotsplab.akiba.managers.ProjectIndex
import org.iotsplab.akiba.managers.ProjectPrograms
import org.iotsplab.akiba.managers.WorkspaceManager
import org.iotsplab.akiba.server.db.ServerDatabase
import java.nio.file.Files
import java.nio.file.Path

/**
 * A plan run from the command line, against the projects on this machine.
 *
 * Verbs open a project only for as long as the fact they need, then release it: a plan
 * that holds a project open would lock the UI out of it exactly like a worker would.
 */
class CliPlanRuntime(
    private val projectsRoot: Path = Path.of("ghidra_projects").toAbsolutePath().normalize(),
) : PlanRuntime {

    // ---- projects -------------------------------------------------------

    override fun projectExists(name: String): Boolean =
        Files.isDirectory(projectsRoot.resolve("$name.rep"))

    override fun createProject(name: String, overwrite: Boolean): ProjectRef {
        if (overwrite && projectExists(name)) deleteOnDisk(name)
        open(name, createIfMissing = true)
        return ProjectRef(name)
    }

    override fun openProject(name: String, createIfMissing: Boolean): ProjectRef {
        if (!createIfMissing && !projectExists(name)) {
            throw ProjectMissingException(name)
        }
        open(name, createIfMissing)
        return ProjectRef(name)
    }

    private fun open(name: String, createIfMissing: Boolean) {
        WorkspaceManager.initializeGhidra()
        WorkspaceManager.openOrCreateInteractiveProject(name, createIfMissing, projectsRoot)
        // Nothing else in a plan needs the handle, and holding it blocks the browser.
        WorkspaceManager.releaseActiveProject()
    }

    private fun deleteOnDisk(name: String) {
        listOf(projectsRoot.resolve("$name.gpr"), projectsRoot.resolve("$name.rep"))
            .forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    // ---- programs -------------------------------------------------------

    override fun copyPrograms(programs: List<ProgramHandle>, target: ImportTarget): Int {
        var copied = 0
        for (program in programs) {
            val source = program.project
                ?: throw PlanException("Program '${program.name}' has no source project")
            if (source == target.project.name) continue
            ProjectPrograms.copy(
                sourceProject = source,
                programName = program.path ?: program.name,
                targetProject = target.project.name,
                projectDirectory = projectsRoot,
            )
            copied++
        }
        return copied
    }

    /**
     * Programs matching [filter], read from the index the server keeps current.
     *
     * The index lives in the server's database, so a plan can only query it when that
     * database is reachable — the CLI initialises it from the same config the server uses.
     */
    override fun findPrograms(filter: String): List<ProgramHandle> {
        check(ServerDatabase.isInitialized) {
            "the project index is not available; start the plan with a database connection"
        }
        val where = if (filter.isBlank()) "" else " AND ($filter)"
        val sql = """
            SELECT project_dir, path, name, program_id, size
            FROM project_entries
            WHERE NOT folder$where
        """.trimIndent()
        return ServerDatabase.newConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val projectDir = rs.getString("project_dir")
                            add(
                                ProgramHandle(
                                    id = rs.getString("program_id") ?: "",
                                    name = rs.getString("name"),
                                    sizeBytes = rs.getLong("size"),
                                    path = rs.getString("path"),
                                    project = Path.of(projectDir).fileName?.toString(),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- modules --------------------------------------------------------

    override fun runModule(spec: ModuleSpec, programIds: List<String>): Int = throw PlanException(
        "running ${spec.className} from a plan is not supported yet: modules still run off " +
            "the binary table, not off programs"
    )

    /** Kept for the index refresh the copy path performs itself. */
    internal fun refreshIndex(project: String) = ProjectIndex.refresh(project, projectsRoot)
}
