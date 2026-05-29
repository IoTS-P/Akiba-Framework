package org.iotsplab.akiba.subcommands

import org.iotsplab.akiba.managers.ConfigManager
import org.iotsplab.akiba.managers.WorkspaceManager
import org.iotsplab.akiba.server.AkibaServer
import org.iotsplab.akiba.server.ServerConfig
import picocli.CommandLine
import java.io.File
import java.io.File.createTempFile
import java.nio.file.Files
import java.nio.file.Path

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
        names = ["--bin-root"],
        description = ["Root directory for binary files"],
        required = false
    )
    var binRoot: String = System.getProperty("user.home") + "/.akiba"

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
        if (!WorkspaceManager.isLogRootDirInitialized) {
            val serverLogDir: Path = Path.of(System.getProperty("user.home"), ".akiba", "logs", "server")
            Files.createDirectories(serverLogDir)
            WorkspaceManager.logRootDir = serverLogDir
        }

        if (!ConfigManager.isConfigInitialized) {
            val (operatorConfigFile, _) = ConfigManager.parseJsonPath(
                org.iotsplab.akiba.Main.mainConfigPath
            )
            val configFileToLoad: String = if (File(operatorConfigFile).isFile) {
                org.iotsplab.akiba.Main.mainConfigPath
            } else {
                val binariesRoot = Path.of(binRoot)
                Files.createDirectories(binariesRoot.resolve("original"))
                Files.createDirectories(binariesRoot.resolve("processed"))
                val binariesRootJson = binariesRoot.toAbsolutePath().toString()
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")

                val syntheticConfig = """
                    {
                      "main": {
                        "username": "$dbUser",
                        "password": "$dbPassword",
                        "usingInstance": null,
                        "globalConsoleLogLevel": "INFO",
                        "globalFileLogLevel": "DEBUG",
                        "general": {
                          "binariesRoot": "$binariesRootJson",
                          "importRoot": null,
                          "processor": "n/a",
                          "autoAnalysisTimeout": 180,
                          "threads": 1
                        },
                        "withGhidraProject": {
                          "projectRoot": "ghidra_projects",
                          "name": "server",
                          "mode": "new",
                          "forkTo": null,
                          "forkOnTask": false,
                          "continueLog": null,
                          "overwriteProject": false,
                          "deletePreviousProgram": false,
                          "overwriteLog": false,
                          "saveProject": false,
                          "noCreateProgram": false
                        },
                        "sqlSource": {
                          "serverIP": "$daemonHost",
                          "serverPort": $daemonPort,
                          "useSnapshot": "current",
                          "constraint": "",
                          "disableUpdate": false,
                          "useLocalCache": null
                        },
                        "globalPreTasks": [],
                        "packages": null,
                        "dbImports": null,
                        "tasks": []
                      }
                    }
                """.trimIndent()
                val syntheticFile = createTempFile("akiba_server_main_config", ".json")
                syntheticFile.writeText(syntheticConfig)
                syntheticFile.deleteOnExit()
                syntheticFile.absolutePath + ConfigManager.KEY_SEPARATOR + "/main"
            }
            ConfigManager.config = ConfigManager.loadGlobalConfig(configFileToLoad)
        }

        AkibaServer.start(ServerConfig(
            host = host,
            port = port,
            jwtSecret = jwtSecret,
            dbHost = dbHost,
            dbPort = dbPort,
            dbName = dbName,
            dbUser = dbUser,
            dbPassword = dbPassword,
            daemonHost = daemonHost,
            daemonPort = daemonPort
        ))
    }
}