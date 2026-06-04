package org.iotsplab.akiba.subcommands

import picocli.CommandLine
import org.iotsplab.akiba.managers.ConfigManager
import org.iotsplab.akiba.utils.Configs
import org.iotsplab.akiba.utils.SqlSource
import org.iotsplab.akiba.data.database.DatabaseClient
import org.iotsplab.akiba.managers.WorkspaceManager.globalLogger

@CommandLine.Command(
    name = "instance-shutdown",
    mixinStandardHelpOptions = true,
    description = ["Shut down an instance"]
)
object ShutdownInstance: Runnable {
    @CommandLine.Option(
        names = ["-i", "--instance"],
        description = ["Name of the instance"],
        required = true
    )
    lateinit var name: String

    @CommandLine.Option(
        names = ["-u", "--user"],
        description = ["User name of akiba"],
        required = true
    )
    lateinit var user: String

    @CommandLine.Option(
        names = ["-P", "--password"],
        description = ["Password of akiba user"]
    )
    var password: String? = null

    @CommandLine.Option(
        names = ["-H", "--host"],
        description = ["Host of the database daemon"]
    )
    var host: String = "127.0.0.1"

    @CommandLine.Option(
        names = ["-p", "--port"],
        description = ["Port of the database daemon"]
    )
    var port: Int = 31777

    override fun run() {
        ConfigManager.config = Configs(sqlSource = SqlSource(serverIP = host, serverPort = port))
        val dbClient = DatabaseClient(host, port)

        if (!dbClient.testConnection())
            globalLogger.error("Cannot connect to database daemon")

        if (password == null) {
            print("Enter password:")
            password = System.console().readPassword().joinToString("")
        }

        try {
            dbClient.login(user, password!!)
        } catch (e: DatabaseClient.DatabaseDaemonException) {
            globalLogger.error("Cannot login to database daemon, error: ${e.statusCode}, ${e.statusMsg?:"null"}")
            return
        }

        try {
            dbClient.shutdownInstance(name)
            globalLogger.info("Instance $name shut down successfully")
        } catch (e: DatabaseClient.DatabaseDaemonException) {
            globalLogger.error("Cannot shut down instance, error: ${e.statusCode}, ${e.statusMsg?:"null"}")
        }

        dbClient.logout()
    }
}