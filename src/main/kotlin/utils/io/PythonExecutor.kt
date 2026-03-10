package org.iotsplab.akiba.utils.io

import org.iotsplab.akiba.Main
import java.nio.file.Path
import java.util.concurrent.TimeUnit

object PythonExecutor{
    fun createDefaultVenv() {
        val process = ProcessBuilder(
            "python3", "-m", "venv", Main.globalVenv
        ).redirectErrorStream(true).start()
        process.waitFor()
    }

    // Todo: Add async output caption
    fun executeFile(
        pythonPath: Path = Path.of("${Main.globalVenv}/bin/python3"),
        scriptPath: Path,
        args: List<String> = emptyList(),
        timeout: Long = 0
    ): String {
        val process = ProcessBuilder(
            "timeout", timeout.toString(), pythonPath.toString(), scriptPath.toString(), *args.toTypedArray())
            .redirectErrorStream(true)
            .start()
        process.waitFor()
        return process.inputStream.bufferedReader().readText()
    }

    // Todo: Add async output caption
    fun executeCode(
        pythonPath: Path = Path.of("${Main.globalVenv}/bin/python3"),
        code: String,
        args: List<String> = emptyList(),
        timeout: Long = 0
    ): String {
        val process = ProcessBuilder(
            "timeout", timeout.toString(), pythonPath.toString(), "-c", code, *args.toTypedArray())
            .redirectErrorStream(true)
            .start()
        process.waitFor()
        return process.inputStream.bufferedReader().readText()
    }

    // Todo: Add async output caption
    fun executePyExe(
        path: String,
        args: List<String> = emptyList(),
        timeout: Long = 0
    ): String {
        val process = ProcessBuilder(
            "timeout", timeout.toString(), "${Main.globalVenv}/bin/$path", *args.toTypedArray()
        ).redirectErrorStream(true).start()
        process.waitFor()
        return process.inputStream.bufferedReader().readText()
    }
}