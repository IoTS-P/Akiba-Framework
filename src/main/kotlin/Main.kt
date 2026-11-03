package org.iotsplab.akiba

import java.util.*
import kotlin.system.exitProcess
import kotlinx.coroutines.*
import org.fusesource.jansi.AnsiConsole
import org.iotsplab.akiba.data.database.DatabaseClient
import org.iotsplab.akiba.managers.*
import org.iotsplab.akiba.managers.WorkspaceManager.globalLogger
import org.iotsplab.akiba.managers.WorkspaceManager.project
import org.iotsplab.akiba.subcommands.*
import picocli.CommandLine
import picocli.CommandLine.ArgGroup
import sun.misc.Signal

@CommandLine.Command(
    name = "Akiba",
    mixinStandardHelpOptions = true,
    description = ["A batch workflow framework of Ghidra"],
    subcommands = [
        CreateInstance::class,
        BackupInstance::class,
        RestoreInstance::class,
        DeleteInstance::class,
        StartInstance::class,
        ShutdownInstance::class,
        ListInstances::class,
        ServerCommand::class,
        MeltdownCommand::class
    ]
)
class Main : Runnable {
    override fun run() = runBlocking {
        // Install colorful ANSI console, it seems that without it, the console can really print colors
        AnsiConsole.systemInstall()

        // Initialize workspace
        if (!WorkspaceManager.initWorkspace()) {
            globalLogger.error("Failed to initialize workspace")
            return@runBlocking
        }

        // If it's importing mode, import files and exit
        try {
            importConfig ?.let {
                ImportManager.import()
                finally()
                return@runBlocking
            }
        } catch (e: Exception) {
            globalLogger.error("Import interrupted")
            e.printStackTrace()
            finally()
            return@runBlocking
        }

        // Initialize programs
        if (!ProgramManager.init()) {
            globalLogger.error("Initialization failed")
            finally()
            return@runBlocking
        }

        // Import all binaries in sequence, when a binary file is analyzed, delete its import.
        ProgramManager.startProcess(project)

        project.close()

        finally()
    }

    companion object {
        class MainModeGroup {
            @CommandLine.Option(
                names = ["-r", "--restore"],
                description = ["Restore mode for a previous task"],
                required = false
            )
            var restore: String? = null

            @CommandLine.Option(
                names = ["-i", "--import"],
                description = ["Import binaries with a config file"],
                required = false
            )
            var importConfig: String? = null
        }

        @ArgGroup(exclusive = true, multiplicity = "0..1")
        var Mode = MainModeGroup()
        val restore: String?
            get() = Mode.restore
        val importConfig: String?
            get() = Mode.importConfig

        @CommandLine.Option(
            names = ["-c", "--main-config"],
            description = ["Main configuration file path"],
            required = false
        )
        @JvmStatic
        var mainConfigPath: String = "configs/config.yaml${ConfigManager.KEY_SEPARATOR}main"

        @CommandLine.Option(
            names = ["--venv"],
            description = ["Global Python venv (virtual environment) root directory"],
            required = false
        )
        @JvmStatic
        var globalVenv: String = "akiba-venv"

        class RestoreModeGroup {
            @CommandLine.Option(
                names = ["-f", "--fail-only"],
                description = ["Only process failed programs, if -r not specified, this option will be ignored"],
                required = false
            )
            var restoreFailedOnly: Boolean = false

            @CommandLine.Option(
                names = ["-e", "--error-only"],
                description = ["Only process programs with error, if -r not specified, this option will be ignored"],
                required = false
            )
            var restoreErrorOnly: Boolean = false
        }

        @ArgGroup(exclusive = true, multiplicity = "0..1")
        var restoreGroup = RestoreModeGroup()
        val restoreFailedOnly: Boolean
            get() = restoreGroup.restoreFailedOnly
        val restoreErrorOnly: Boolean
            get() = restoreGroup.restoreErrorOnly

        @JvmStatic
        fun main(args: Array<String>): Unit = runBlocking {
            val source = Main::class.java.protectionDomain.codeSource
            val jarFile = source.location.toURI()
            val appHome = java.io.File(jarFile).parentFile.parentFile.absolutePath
            System.setProperty("user.dir", appHome)
            println("Working directory: $appHome")

            // Set timezone
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))

            println("Max runtime memory: ${Runtime.getRuntime().maxMemory() / 1024.0 / 1024.0}MB")

            // Register signal handler
            println("Registering signal handler")
            listOf("INT", "TERM").forEach { signalName ->
                Signal.handle(Signal(signalName)) {
                    interruptHandler()
                }
            }

            try {
                exitProcess(CommandLine(Main::class.java).execute(*args))
            } catch (_: Exception) {
                finally()
            }
        }

        @JvmStatic
        fun interruptHandler() {
            globalLogger.error("Interrupted by user")
            // Step 1 — stop accepting new binaries: jobs started after this
            // flag is set bail out in ProgramManager.startProcess, so no work
            // begins that cannot finish.
            try {
                ProgramManager.stopRequested = true
            } catch (_: Exception) {}
            // Step 2 — cancel every in-flight module's task monitor: each one
            // wraps a Ghidra `TaskMonitor` and a coroutine `Job`, so a single
            // `cancel()` unwinds both at their next checkpoint instead of
            // waiting out a deep disassembly.
            try {
                val cancelled = org.iotsplab.akiba.utils.ActiveTaskMonitors.cancelAll()
                globalLogger.info(
                    "Sent cancel to $cancelled active task monitor(s)"
                )
            } catch (e: Exception) {
                globalLogger.warn("cancelAll() failed: ${e.message}")
            }
            // Step 3 — drain in-flight coroutines (they unwind within seconds
            // once the monitors are cancelled), capped at 30 s so a
            // pathological module cannot hang the JVM.
            try {
                ProgramManager.drainGracefully(timeoutMs = 30_000L)
            } catch (e: Exception) {
                globalLogger.warn("Drain failed: ${e.message}")
            }
            // Step 4 — flush the DB and close the Ghidra project after the
            // in-flight work has cleaned up, so saved counts match disk.
            try {
                finally()
            } catch (_: Exception) {}
            globalLogger.info("Tested ${ProgramManager.successCount + ProgramManager.failureCount} cases")
            globalLogger.info("Success: ${ProgramManager.successCount}")
            globalLogger.info("Failed: ${ProgramManager.failureCount}")
            exitProcess(0)
        }

        fun finally() {
            globalLogger.info("Exiting...")
            try {
                DatabaseClient.global?.logout()
                WorkspaceManager.close()
                AnsiConsole.systemUninstall()
            } catch (_: Exception) {}
        }
    }
}