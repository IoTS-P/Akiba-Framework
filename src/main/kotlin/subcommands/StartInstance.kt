package org.iotsplab.akiba.subcommands

import org.iotsplab.akiba.data.database.DatabaseClient
import org.iotsplab.akiba.managers.ConfigManager
import org.iotsplab.akiba.managers.WorkspaceManager.globalLogger
import org.iotsplab.akiba.utils.ADMIN_USERNAME
import org.iotsplab.akiba.utils.Configs
import org.iotsplab.akiba.utils.SqlSource
import picocli.CommandLine

@CommandLine.Command(
    name = "instance-start",
    mixinStandardHelpOptions = true,
    description = ["Start an instance"]
)
object StartInstance: Runnable { 
    @CommandLine.Option(
        names = ["-i", "--instance"],
        description = ["Name of the instance"],
        required = true
    )
    lateinit var name: String

    @CommandLine.Option(
        names = ["-u", "--user"],
        description = ["Operator account name (defaults to admin)"]
    )
    var user: String = ADMIN_USERNAME

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
            dbClient.connectToInstance(name)
            dbClient.disconnectToInstance(name)
            globalLogger.info("Instance $name started successfully")
        } catch (e: DatabaseClient.DatabaseDaemonException) {
            globalLogger.error("Cannot start instance, error: ${e.statusCode}, ${e.statusMsg?:"null"}")
        }

        dbClient.logout()
    }
}