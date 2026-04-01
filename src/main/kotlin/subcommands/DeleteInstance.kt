package org.iotsplab.akiba.subcommands

import picocli.CommandLine
import org.iotsplab.akiba.managers.ConfigManager
import org.iotsplab.akiba.utils.SqlSource
import org.iotsplab.akiba.utils.Configs
import org.iotsplab.akiba.client.database.DatabaseClient
import org.iotsplab.akiba.managers.WorkspaceManager.globalLogger

@CommandLine.Command(
    name = "instance-delete",
    mixinStandardHelpOptions = true,
    description = ["Delete an instance"]
)
object DeleteInstance: Runnable { 
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
        DatabaseClient.urlHeader = "http://$host:$port"
        
        if (!DatabaseClient.testConnection())
            globalLogger.error("Cannot connect to database daemon")

        if (password == null) {
            print("Enter password:")
            password = System.console().readPassword().joinToString("")
        }

        try {
            DatabaseClient.login(user, password!!)
        } catch (e: DatabaseClient.DatabaseDaemonException) {
            globalLogger.error("Cannot login to database daemon, error: ${e.statusCode}, ${e.statusMsg?:"null"}")
            return
        }

        try {
            DatabaseClient.deleteInstance(name)
            globalLogger.info("Instance $name deleted successfully")
        } catch (e: DatabaseClient.DatabaseDaemonException) {
            globalLogger.error("Cannot delete instance, error: ${e.statusCode}, ${e.statusMsg?:"null"}")
        }
        
        DatabaseClient.logout()
    }
}