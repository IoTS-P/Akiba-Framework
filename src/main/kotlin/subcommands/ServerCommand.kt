package org.iotsplab.akiba.subcommands

import ghidra.program.model.listing.Program
import org.apache.logging.log4j.Level
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.utils.ProcedureArguments
import org.iotsplab.akiba.utils.ProcedureArgumentsDeserializer
import org.iotsplab.akiba.utils.ProcedureArgumentsDeserializer.loadAllModules
import org.iotsplab.akiba.utils.ProcedureArgumentsDeserializer.resolveModule
import picocli.CommandLine
import java.io.File.createTempFile
import kotlin.String
import kotlin.reflect.full.primaryConstructor

@CommandLine.Command(
    name = "server",
    mixinStandardHelpOptions = true,
    description = ["Start Akiba HTTP server"]
)
class ServerCommand : Runnable {
    @CommandLine.Option(
        names = ["-p", "--port"],
        description = ["Server port"],
        required = false
    )
    var port: Int = 8080

    @CommandLine.Option(
        names = ["--host"],
        description = ["Server host"],
        required = false
    )
    var host: String = "0.0.0.0"

    @CommandLine.Option(
        names = ["--jwt-secret"],
        description = ["JWT secret key"],
        required = false
    )
    var jwtSecret: String = "change-me-in-production-use-long-random-string"

    @CommandLine.Option(
        names = ["--db-host"],
        description = ["PostgreSQL host for user management"],
        required = false
    )
    var dbHost: String = "127.0.0.1"

    @CommandLine.Option(
        names = ["--db-port"],
        description = ["PostgreSQL port for user management"],
        required = false
    )
    var dbPort: Int = 5432

    @CommandLine.Option(
        names = ["--db-name"],
        description = ["Database name for user management"],
        required = false
    )
    var dbName: String = "akiba_users"

    @CommandLine.Option(
        names = ["--db-user"],
        description = ["PostgreSQL user"],
        required = false
    )
    var dbUser: String = "akiba"

    @CommandLine.Option(
        names = ["--db-password"],
        description = ["PostgreSQL password"],
        required = false
    )
    var dbPassword: String = "akiba"

    @CommandLine.Option(
        names = ["--daemon-host"],
        description = ["Akiba DB daemon host"],
        required = false
    )
    var daemonHost: String = "127.0.0.1"

    @CommandLine.Option(
        names = ["--daemon-port"],
        description = ["Akiba DB daemon port"],
        required = false
    )
    var daemonPort: Int = 31777

    override fun run() {
        System.err.println("DEBUG: ServerCommand.run() called")

        val serverConfigJson = """
            {
                "mode": "server",
                "server": {
                    "host": "$host",
                    "port": $port,
                    "jwtSecret": "$jwtSecret",
                    "dbHost": "$dbHost",
                    "dbPort": $dbPort,
                    "dbName": "$dbName",
                    "dbUser": "$dbUser",
                    "dbPassword": "$dbPassword",
                    "daemonHost": "$daemonHost",
                    "daemonPort": $daemonPort
                }
            }
        """.trimIndent()

        val tempFile = createTempFile("akiba_server_config", ".json")
        tempFile.writeText(serverConfigJson)
        tempFile.deleteOnExit()

        System.err.println("DEBUG: Config file created at ${tempFile.absolutePath}")

        resolveModule("org.iotsplab.akiba.module.AkibaUtils")

        val task = ProcedureArguments(
            mainClassName = "org.iotsplab.akiba.module.AkibaUtils",
            configKey = tempFile.absolutePath
        )
        loadAllModules(listOf(task))
        System.err.println("DEBUG: Modules loaded, task.mainClass = ${task.mainClass}")

        val mainClass = task.mainClass
        if (mainClass == null) {
            System.err.println("ERROR: task.mainClass is null!")
            return
        }

        System.err.println("DEBUG: Using Java reflection to get constructor...")
        val constructor = mainClass.kotlin.primaryConstructor!!

        val instance = constructor.call(tempFile.absolutePath) as AkibaModule

        System.err.println("DEBUG: Module created via Java reflection: $instance, calling startProcess()")
        kotlinx.coroutines.runBlocking {
            instance.startProcess()
        }
    }
}