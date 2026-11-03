package org.iotsplab.akiba.script

import ghidra.program.model.listing.Program
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.module.RuntimeReport

/**
 * Abstract base class for Akiba scripts: implement [execute], which runs
 * against a single binary, and use the inherited logger / output helpers.
 *
 * @property scriptSkipDbWrite When true, all database writes are skipped and
 *           results only live in the in-memory [RuntimeReport] (default).
 * @property maxOutputSizeBytes Hard cap on output; callers may set lower.
 */
abstract class AkibaScript(
    val saveResult: Boolean = true,
    val maxOutputSizeBytes: Long = 10 * 1024 * 1024,
    val description: String = "",
    scriptSkipDbWrite: Boolean = true,
    binaryId: Int = -1
) : AkibaModule(
    id = binaryId,
    skipDbWrite = scriptSkipDbWrite
) {

    private val outputBuilder = StringBuilder()

    /**
     * Get the binary ID being processed.
     */
    fun getBinaryId(): Int = id

    /**
     * Append output to the script's output buffer.
     * Output will be truncated if it exceeds [maxOutputSizeBytes].
     */
    protected fun appendOutput(text: String) {
        if (outputBuilder.length < maxOutputSizeBytes) {
            val remaining = maxOutputSizeBytes - outputBuilder.length
            if (text.length <= remaining) {
                outputBuilder.append(text)
            } else {
                outputBuilder.append(text.substring(0, remaining.toInt()))
                outputBuilder.append("\n... (output truncated)")
            }
        }
    }

    /**
     * Append output with a newline.
     */
    protected fun appendLine(text: String) {
        appendOutput(text + "\n")
    }

    /**
     * Clear the output buffer.
     */
    protected fun clearOutput() {
        outputBuilder.clear()
    }

    /**
     * Get the current output.
     */
    fun getOutput(): String = outputBuilder.toString()

    /**
     * Main execution method — implement with script logic.
     */
    abstract suspend fun execute()

    final override suspend fun startProcess() {
        clearOutput()
        execute()
    }
}
