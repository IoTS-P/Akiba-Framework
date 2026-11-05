package org.iotsplab.akiba.subcommands

import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.iotsplab.akiba.data.database.DatabaseClient
import org.iotsplab.akiba.managers.ConfigManager
import org.iotsplab.akiba.managers.WorkspaceManager
import org.iotsplab.akiba.managers.WorkspaceManager.globalLogger
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.utils.ADMIN_USERNAME
import org.iotsplab.akiba.utils.Configs
import org.iotsplab.akiba.utils.SqlSource
import picocli.CommandLine

/**
 * `akiba identify-arch` — fill in the architecture of imported binaries.
 *
 * Import only records checksum, size and one copy, so `arch` starts out empty
 * and the analysis tools refuse to run. This subcommand runs the `AkibaArch`
 * module for the selected binaries, which reads the file header and writes
 * `arch` and `format` back — no Ghidra project and no agent involved.
 */
@CommandLine.Command(
    name = "identify-arch",
    mixinStandardHelpOptions = true,
    description = ["Identify arch and format of imported binaries"]
)
object IdentifyArchCommand : Runnable {

    private const val MODULE_CLASS = "org.iotsplab.akiba.process.AkibaArch"

    @CommandLine.Option(names = ["-H", "--host"], description = ["Host of the database daemon"])
    var host: String = "127.0.0.1"

    @CommandLine.Option(names = ["-p", "--port"], description = ["Port of the database daemon"])
    var port: Int = 31777

    @CommandLine.Option(
        names = ["-u", "--user"],
        description = ["Operator account name (defaults to admin)"]
    )
    var user: String = ADMIN_USERNAME

    @CommandLine.Option(names = ["-P", "--password"], description = ["Password of the user"])
    var password: String? = null

    @CommandLine.Option(names = ["-i", "--instance"], description = ["Instance to work on"], required = true)
    lateinit var instance: String

    @CommandLine.Option(
        names = ["-b", "--binary"],
        description = ["Binary id to identify; repeat the option for several ids"]
    )
    var binaries: MutableList<Int> = mutableListOf()

    @CommandLine.Option(names = ["--all"], description = ["Every binary without an architecture"])
    var all: Boolean = false

    override fun run() {
        ConfigManager.config = Configs(sqlSource = SqlSource(serverIP = host, serverPort = port))
        // The module base class writes its log next to this; identification needs
        // no project, so a scratch directory is enough.
        if (!WorkspaceManager.isLogRootDirInitialized) {
            WorkspaceManager.logRootDir = Path.of(System.getProperty("java.io.tmpdir"), "akiba-identify-arch")
        }

        val dbClient = DatabaseClient(host, port)
        if (!dbClient.testConnection()) {
            globalLogger.error("Cannot connect to database daemon at $host:$port")
            return
        }
        // Modules default their `dbClient` to this global, the same way the
        // batch entry points set it before running a procedure.
        DatabaseClient.global = dbClient

        val pwd = password ?: run {
            print("Enter password:")
            System.console()?.readPassword()?.joinToString("") ?: ""
        }
        try {
            dbClient.login(user, pwd)
        } catch (e: DatabaseClient.DatabaseDaemonException) {
            globalLogger.error("Cannot login to database daemon, error: ${e.statusCode}")
            return
        }

        dbClient.connectToInstance(instance)
        try {
            // The binary directories are derived from user + instance, and the
            // module resolves files through them.
            ConfigManager.config.username = user
            ConfigManager.config.usingInstance = instance
            WorkspaceManager.initBinDirectories()

            val ids = selectIds(dbClient)
            if (ids.isEmpty()) {
                println("Nothing to identify: no binary without an architecture.")
                return
            }
            identify(dbClient, ids)
        } finally {
            runCatching { dbClient.disconnectToInstance(instance) }
            runCatching { dbClient.logout() }
        }
    }

    private fun selectIds(dbClient: DatabaseClient): List<Int> = when {
        binaries.isNotEmpty() -> binaries
        all -> pendingIds(dbClient)
        else -> emptyList()
    }

    /**
     * Ids still waiting for an architecture.
     *
     * The daemon exposes paged ids but no filtered query (raw SQL is disabled by
     * default), so each page is filtered here by reading the row's metadata.
     */
    private fun pendingIds(dbClient: DatabaseClient): List<Int> {
        val pageSize = 200
        val pending = mutableListOf<Int>()
        var offset = 0
        while (true) {
            val ids = dbClient.getIdPage(offset, pageSize)
            if (ids.isEmpty()) break
            for (id in ids) {
                val metadata = runCatching { dbClient.getMetadata(id) }.getOrNull() ?: continue
                if (metadata.arch.isNullOrBlank()) pending.add(id.toInt())
            }
            if (ids.size < pageSize) break
            offset += pageSize
        }
        return pending
    }

    private fun identify(dbClient: DatabaseClient, ids: List<Int>) {
        val root = CliRootModule(dbClient)
        var identified = 0
        var failed = 0
        for (id in ids) {
            val module = runCatching {
                runBlocking {
                    root.callModule(
                        program = null,
                        mainClassName = MODULE_CLASS,
                        targetId = id,
                    )
                }
            }.getOrElse { e ->
                failed++
                println("  #$id failed: ${describe(e)}")
                return@getOrElse null
            }
            if (module == null) continue

            if (module.failureSign == AkibaModule.SUCCESS) identified++ else failed++
            val metadata = runCatching { dbClient.getMetadata(id.toLong()) }.getOrNull()
            println(
                "  #$id -> ${metadata?.arch ?: "unidentified"} " +
                    "(${metadata?.format ?: "unknown format"})"
            )
        }
        println("identified=$identified failed=$failed")
    }

    /** Root cause with its type: reflection wrappers report a null message. */
    private fun describe(t: Throwable): String {
        var root = t
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val msg = root.message ?: t.message
        return "${root.javaClass.simpleName}" + if (msg != null) ": $msg" else ""
    }

    /**
     * Stand-in parent module.
     *
     * [AkibaModule.callModule] is an instance method, so the CLI needs some
     * module to call it on; this one does nothing itself and creates no table.
     */
    private class CliRootModule(dbClient: DatabaseClient) : AkibaModule(
        id = -1,
        dbClient = dbClient,
        skipDbWrite = true,
    ) {
        override suspend fun startProcess() = Unit
    }
}
