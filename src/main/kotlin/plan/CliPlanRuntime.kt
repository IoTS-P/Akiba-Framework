package org.iotsplab.akiba.plan

import ghidra.program.model.listing.Program
import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.data.database.DatabaseClient
import org.iotsplab.akiba.managers.ProjectIndex
import org.iotsplab.akiba.managers.ProjectPrograms
import org.iotsplab.akiba.managers.WorkspaceManager
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.module.ModuleRun
import org.iotsplab.akiba.utils.ProcedureArgumentsDeserializer
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
            val result = ProjectPrograms.copy(
                sourceProject = source,
                programName = program.path?.takeIf { it.isNotBlank() } ?: program.name,
                targetProject = target.project.name,
                projectDirectory = projectsRoot,
            )
            // copy() always lands in the project root; a target folder is a second move,
            // so a plan that says "batch / raw" gets its programs in raw/.
            val folder = target.folder?.trim('/')
            if (!folder.isNullOrBlank()) {
                ProjectPrograms.moveEntry(
                    projectName = target.project.name,
                    projectDirectory = projectsRoot,
                    path = result.targetFile,
                    targetFolderPath = folder,
                )
            }
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

    /**
     * Runs [spec] once per program and keeps what each run produced.
     *
     * The programs are opened read-only and one at a time, and the module runs with db
     * writes off — a program a plan found has no binary row to write against, so its data
     * comes back to the script instead of going into a table.
     */
    override fun runModule(spec: ModuleSpec, programs: List<ProgramHandle>): Map<String, ModuleRun> {
        WorkspaceManager.initializeGhidra()
        val runs = LinkedHashMap<String, ModuleRun>()
        programs.forEach { handle -> runs[handle.id] = runOn(handle, spec) }
        return runs
    }

    private fun runOn(handle: ProgramHandle, spec: ModuleSpec): ModuleRun {
        val projectName = handle.project
            ?: throw PlanException("Program '${handle.name}' does not say which project it lives in")
        val project = WorkspaceManager.openOrCreateInteractiveProject(projectName, false, projectsRoot)
        try {
            val path = handle.path ?: handle.name
            val folder = path.substringBeforeLast('/', "")
            val program = project.openProgram(if (folder.isEmpty()) "/" else "/$folder",
                path.substringAfterLast('/'), true)
                ?: throw PlanException("Program '${handle.name}' could not be opened in '$projectName'")
            val module = instantiate(spec, program)
            val started = System.currentTimeMillis()
            runBlocking { module.startProcess() }
            return module.collectResult(System.currentTimeMillis() - started)
        } finally {
            WorkspaceManager.releaseActiveProject()
        }
    }

    /**
     * Builds a module the way its jar declares it. The five-argument shape is what every
     * module in this repository uses; the two-argument one is the minimal form.
     */
    private fun instantiate(spec: ModuleSpec, program: Program): AkibaModule {
        val clazz = ProcedureArgumentsDeserializer.ensureModuleLoaded(spec.className)
        val shapes = listOf(
            arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, Program::class.java,
                Level::class.java, Level::class.java, String::class.java),
            arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, Program::class.java),
        )
        for (types in shapes) {
            val constructor = runCatching { clazz.getDeclaredConstructor(*types) }.getOrNull() ?: continue
            constructor.isAccessible = true
            val args = if (types.size == 5) arrayOf<Any?>(-1, program, Level.INFO, Level.INFO, null)
                       else arrayOf<Any?>(-1, program)
            val module = constructor.newInstance(*args) as AkibaModule
            module.skipDbWrite = true
            return module
        }
        throw PlanException(
            "${spec.className} has no constructor a plan can call; expected (id, program, ...)")
    }

    /**
     * Writes a run's result into the module's table. A program that was never imported has
     * no row, and a negative id says exactly that, so nothing is written.
     */
    override fun saveResult(spec: ModuleSpec, binaryId: Int, run: ModuleRun): Boolean {
        val result = run.result ?: return false
        if (binaryId <= 0) return false
        val client = DatabaseClient.global ?: return false
        client.updateData(tableNameFor(spec.className), binaryId.toLong(), result)
        return true
    }

    private fun tableNameFor(className: String): String =
        className.substringAfterLast('.')
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase() + "_results"

    /** Kept for the index refresh the copy path performs itself. */
    internal fun refreshIndex(project: String) = ProjectIndex.refresh(project, projectsRoot)
}
