package org.iotsplab.akiba.utils.io

import java.nio.file.Path

object Command {
    fun exists(command: String): Boolean {
        val process = ProcessBuilder("which", command)
            .redirectErrorStream(true)
            .start()
        val exitCode = process.waitFor()
        return exitCode == 0
    }

    fun which(command: String): String? {
        val process = ProcessBuilder("which", command)
            .redirectErrorStream(true)
            .start()
        val exitCode = process.waitFor()
        return if (exitCode == 0) {
            process.inputStream.bufferedReader().readLine()
        } else {
            null
        }
    }

    fun execute(command: String, envSource: List<Path> = listOf()): Pair<Int, String> {
        val cmd = if (envSource.isEmpty()) command
                  else envSource.joinToString(" && ") { "source $it" } + " && $command"
        val process = ProcessBuilder("/bin/sh", "-c", cmd).redirectErrorStream(true).start()
        process.waitFor()
        return process.exitValue() to process.inputStream.bufferedReader().readText()
    }
}