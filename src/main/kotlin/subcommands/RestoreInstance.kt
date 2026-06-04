package org.iotsplab.akiba.subcommands

import org.iotsplab.akiba.data.database.DatabaseClient
import org.iotsplab.akiba.managers.ConfigManager
import org.iotsplab.akiba.utils.Configs
import org.iotsplab.akiba.utils.SqlSource
import picocli.CommandLine
import org.iotsplab.akiba.managers.WorkspaceManager.globalLogger

@CommandLine.Command(
    name = "instance-restore",
    mixinStandardHelpOptions = true,
    description = ["Restore to a backup of a instance"]
)
object RestoreInstance: Runnable {
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

    @CommandLine.Option(
        names = ["-l", "--label"],
        description = ["Label or alias of the backup"],
        required = true
    )
    lateinit var label: String

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
            dbClient.restoreBackup(name, label)
            globalLogger.info("Restore completed")
        } catch (e: DatabaseClient.DatabaseDaemonException) {
            globalLogger.error("Cannot restore backup, error: ${e.statusCode}, ${e.statusMsg?:"null"}")
        }

        dbClient.logout()
    }
}