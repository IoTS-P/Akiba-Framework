package org.iotsplab.akiba.subcommands

import org.iotsplab.akiba.client.database.DatabaseClient
import picocli.CommandLine
import java.io.Console
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "instance-create",
    mixinStandardHelpOptions = true,
    description = ["Create a new instance"]
)
object CreateInstance: Runnable {
    @CommandLine.Option(
        names = ["-n", "--name"],
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
        DatabaseClient.urlHeader = "http://$host:$port"

        if (!DatabaseClient.testConnection())
            println("Cannot connect to database daemon")

        if (password != null) {
            print("Enter password:")
            password = System.console().readPassword().joinToString("")
        }

        DatabaseClient.login(user, password!!)
        DatabaseClient.createInstance(name)
    }
}