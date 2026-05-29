package org.iotsplab.akiba.subcommands

import picocli.CommandLine
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Emergency kill switch for all running Akiba framework processes.
 *
 * When an LLM agent is executing dangerous operations or the user needs to
 * immediately halt all Akiba activity, this command finds every JVM process
 * whose command line contains the Akiba main class and sends SIGKILL to them.
 *
 * Usage:
 * ```
 * akiba meltdown
 * akiba meltdown --force
 * ```
 *
 * Without `--force`, a confirmation prompt is shown before killing.
 */
@CommandLine.Command(
    name = "meltdown",
    mixinStandardHelpOptions = true,
    description = ["Emergency: immediately kill ALL running Akiba framework processes"]
)
class MeltdownCommand : Runnable {

    @CommandLine.Option(
        names = ["-f", "--force"],
        description = ["Skip confirmation prompt and kill immediately"],
        required = false
    )
    var force: Boolean = false

    override fun run() {
        val akibaProcesses = findAkibaProcesses()

        if (akibaProcesses.isEmpty()) {
            println("[meltdown] No running Akiba framework processes found.")
            return
        }

        println("[meltdown] Found ${akibaProcesses.size} Akiba process(es):")
        akibaProcesses.forEach { (pid, cmdLine) ->
            println("  PID $pid: ${cmdLine.take(120)}")
        }
        println()

        if (!force) {
            print("[meltdown] Kill all ${akibaProcesses.size} process(es)? This cannot be undone. [y/N]: ")
            System.out.flush()
            val response = try {
                System.`in`.bufferedReader().readLine()?.trim()?.lowercase()
            } catch (_: Exception) { null }

            if (response != "y" && response != "yes") {
                println("[meltdown] Aborted.")
                return
            }
        }

        // Exclude our own PID to avoid self-kill
        val myPid = ProcessHandle.current().pid()
        var killed = 0
        var skipped = 0

        for ((pid, _) in akibaProcesses) {
            if (pid == myPid) {
                skipped++
                continue
            }
            try {
                val result = ProcessBuilder("kill", "-9", pid.toString())
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
                if (result == 0) {
                    println("[meltdown] ✗ Killed PID $pid")
                    killed++
                } else {
                    System.err.println("[meltdown] Failed to kill PID $pid (exit=$result)")
                }
            } catch (e: Exception) {
                System.err.println("[meltdown] Error killing PID $pid: ${e.message}")
            }
        }

        println()
        println("[meltdown] Done. Killed $killed process(es)" +
            if (skipped > 0) ", skipped $skipped (self)." else ".")
    }

    /**
     * Find all running processes that are Akiba framework JVM instances.
     *
     * Detection heuristics (any match):
     * - Command line contains `org.iotsplab.akiba.Main`
     * - Command line contains `akiba_framework` jar name
     * - Command line contains the Akiba application wrapper script name
     *
     * Works on macOS and Linux via `ps aux`.
     *
     * @return List of (pid, commandLine) pairs.
     */
    private fun findAkibaProcesses(): List<Pair<Long, String>> {
        val markers = listOf(
            "org.iotsplab.akiba.Main",
            "akiba_framework",
            "/bin/akiba "
        )

        return try {
            val process = ProcessBuilder("ps", "aux")
                .redirectErrorStream(true)
                .start()

            val results = mutableListOf<Pair<Long, String>>()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.lineSequence().forEach { line ->
                    // Skip ps header and this grep/meltdown process itself
                    if (line.contains("PID") && line.contains("COMMAND")) return@forEach
                    if (line.contains("meltdown")) return@forEach

                    if (markers.any { marker -> line.contains(marker) }) {
                        val pid = extractPid(line)
                        if (pid != null) {
                            results.add(pid to line.trim())
                        }
                    }
                }
            }
            process.waitFor()
            results
        } catch (e: Exception) {
            System.err.println("[meltdown] Failed to list processes: ${e.message}")
            emptyList()
        }
    }

    /**
     * Extract PID from a `ps aux` output line.
     * Format: `USER  PID  %CPU  %MEM  ...`
     */
    private fun extractPid(line: String): Long? {
        val parts = line.trim().split("\\s+".toRegex())
        if (parts.size < 2) return null
        return parts[1].toLongOrNull()
    }
}
