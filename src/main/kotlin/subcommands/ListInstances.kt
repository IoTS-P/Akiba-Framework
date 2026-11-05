package org.iotsplab.akiba.subcommands

import org.iotsplab.akiba.data.database.DatabaseClient
import org.iotsplab.akiba.managers.ConfigManager
import org.iotsplab.akiba.managers.WorkspaceManager.globalLogger
import org.iotsplab.akiba.utils.ADMIN_USERNAME
import org.iotsplab.akiba.utils.Configs
import org.iotsplab.akiba.utils.SqlSource
import picocli.CommandLine

/**
 * `akiba instance-list` — list the database instances owned by a user.
 *
 * The daemon namespaces instances by the username that created them, and
 * `/instance/list` (added together with real credential checking) only ever
 * returns the caller's own instances. This subcommand exposes that from the CLI
 * so an operator does not have to parse `~/.akiba/instances.json` by hand.
 */
@CommandLine.Command(
    name = "instance-list",
    mixinStandardHelpOptions = true,
    description = ["List the instances owned by a user"]
)
object ListInstances: Runnable {
    @CommandLine.Option(
        names = ["-u", "--user"],
        description = ["Operator account name (defaults to admin)"]
    )
    var user: String = ADMIN_USERNAME

    @CommandLine.Option(
        names = ["-P", "--password"],
        description = ["Password of the user"]
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

        if (!dbClient.testConnection()) {
            globalLogger.error("Cannot connect to database daemon")
            return
        }

        val pwd = password ?: run {
            print("Enter password:")
            System.console()?.readPassword()?.joinToString("") ?: ""
        }

        try {
            dbClient.login(user, pwd)
        } catch (e: DatabaseClient.DatabaseDaemonException) {
            globalLogger.error("Cannot login to database daemon, error: ${e.statusCode}, ${e.statusMsg ?: "null"}")
            return
        }

        try {
            val instances = dbClient.listInstances()
            if (instances.isEmpty()) {
                println("User '$user' has no instance yet.")
            } else {
                println("Instances of '$user':")
                instances.forEach {
                    println(
                        "  ${it.name}\tport=${it.port}\trunning=${it.running}" +
                            (it.logicalPrior?.let { prior -> "\tlastRestored=$prior" } ?: "")
                    )
                }
            }
        } catch (e: DatabaseClient.DatabaseDaemonException) {
            globalLogger.error("Cannot list instances, error: ${e.statusCode}, ${e.statusMsg ?: "null"}")
        }

        dbClient.logout()
    }
}
