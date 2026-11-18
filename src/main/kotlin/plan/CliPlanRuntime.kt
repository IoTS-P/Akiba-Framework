package org.iotsplab.akiba.plan

import ghidra.program.model.listing.Program
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.Level
import io.ktor.http.HttpStatusCode
import org.iotsplab.akiba.data.database.DatabaseClient
import org.iotsplab.akiba.managers.ProjectIndex
import org.iotsplab.akiba.managers.ProjectPrograms
import org.iotsplab.akiba.managers.WorkspaceManager
import org.iotsplab.akiba.managers.BinaryMetadata
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.module.ModuleContext
import org.iotsplab.akiba.module.ModuleRun
import org.iotsplab.akiba.utils.ProcedureArgumentsDeserializer
import org.iotsplab.akiba.server.db.ServerDatabase
import org.iotsplab.akiba.utils.AkibaJson
import org.iotsplab.akiba.utils.WithConfigClass
import org.iotsplab.akiba.utils.WithTableColumn
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
            SELECT project_dir, path, name, program_id, size, binary_id
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
                                    binaryId = rs.getLong("binary_id").takeIf { !rs.wasNull() },
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
    override fun runModule(
        programs: List<ProgramHandle>,
        specFor: (ProgramHandle) -> ModuleSpec,
    ): Map<String, ModuleRun> {
        WorkspaceManager.initializeGhidra()
        val runs = LinkedHashMap<String, ModuleRun>()
        programs.forEach { handle -> runs[handle.id] = runOn(handle, specFor(handle)) }
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
                path.substringAfterLast('/'), !spec.writable)
                ?: throw PlanException("Program '${handle.name}' could not be opened in '$projectName'")
            val module = instantiate(spec, program)
            val started = System.currentTimeMillis()
            // Modules read their target's facts from this context. A program the index
            // knows a binary for gets that row; one that was never imported is described
            // by the program itself, with no id.
            val row = handle.binaryId?.let { binaryId ->
                runCatching { DatabaseClient.global?.getMetadata(binaryId) }.getOrNull()
            }
            val metadata = BinaryMetadata.of(program, row)
            runBlocking {
                withContext(ModuleContext(metadata)) {
                    // Zero means "no limit", which is what most modules want; a plan that
                    // set one gets a TIMEOUT ending instead of hanging the whole file.
                    module.startProcess(spec.timeoutSeconds ?: 0)
                }
            }
            val run = module.collectResult(
                programId = handle.id,
                binaryId = handle.binaryId,
                durationMs = System.currentTimeMillis() - started,
            )
            // A module that changes the program only keeps its changes once saved, and a
            // run that failed or timed out may have stopped halfway through its edits.
            if (spec.writable && run.isSuccess) project.save(program)
            return run
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
            spec.config?.let { values -> applyConfig(module, clazz, values) }
            return module
        }
        throw PlanException(
            "${spec.className} has no constructor a plan can call; expected (id, program, ...)")
    }

    /**
     * Hands the module the config the script wrote.
     *
     * The script cannot name the module's config class — it lives in a jar the script never
     * sees — so the map is written as JSON and read back as whatever the module declared.
     */
    private fun applyConfig(module: AkibaModule, clazz: Class<*>, values: Map<String, Any?>) {
        val declared = clazz.kotlin.annotations.filterIsInstance<WithConfigClass>().firstOrNull()?.clazz
            ?: throw PlanException("${clazz.simpleName} declares no config class, so a plan cannot pass one")
        val json = AkibaJson.mapper.writeValueAsString(values)
        @Suppress("UNCHECKED_CAST")
        val moduleClass = clazz.kotlin as KClass<out AkibaModule>
        val instance = AkibaModule.getDeserializerMapper(moduleClass).readValue(json, declared.java)
        module.replaceConfig(instance)
    }

    /**
     * Writes a run's result into the module's table. A program that was never imported has
     * no row, and a negative id says exactly that, so nothing is written.
     */
    override fun saveResult(spec: ModuleSpec, run: ModuleRun): Boolean {
        val result = run.result ?: return false
        val binaryId = run.binaryId ?: return false
        if (binaryId <= 0) return false
        val client = DatabaseClient.global ?: return false
        val table = tableNameFor(spec.className)
        // A plan can be the first thing that ever runs this module for this instance, so
        // the result table may not exist yet. The run carries the columns the module
        // declared, which keeps that declaration the single source of truth.
        ensureTable(client, table, run.columns)
        client.updateData(table, binaryId, result)
        return true
    }

    /** Create the module's result table if this instance has never seen the module. */
    private fun ensureTable(client: DatabaseClient, table: String, columns: Map<String, String>) {
        if (columns.isEmpty())
            throw PlanException("$table has no columns: the module returned a result but declares none")
        runCatching { client.createModuleTable(table, columns) }
            .onFailure { failure ->
                // Conflict means someone else created it first, which is the good case.
                val status = (failure as? DatabaseClient.DatabaseDaemonException)?.statusCode
                if (status != HttpStatusCode.Conflict)
                    throw PlanException("cannot create '$table': ${failure.message}", cause = failure)
            }
        // The daemon only writes into a table it has been told to own; the task path locks
        // it right after creating it, so a plan has to do the same.
        runCatching { client.tableLock(table) }.onFailure { failure ->
            val status = (failure as? DatabaseClient.DatabaseDaemonException)?.statusCode
            if (status != HttpStatusCode.Conflict)
                throw PlanException("cannot lock '$table': ${failure.message}", cause = failure)
        }
    }

    private fun tableNameFor(className: String): String =
        className.substringAfterLast('.')
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase() + "_results"

    /** Kept for the index refresh the copy path performs itself. */
    internal fun refreshIndex(project: String) = ProjectIndex.refresh(project, projectsRoot)
}
