package org.iotsplab.akiba.utils.highFunction

import ghidra.app.decompiler.DecompInterface
import ghidra.program.model.listing.Program

object HighFunctionUtil {
    fun getDefaultDecompiler(program: Program): DecompInterface {
        val decompInterface = DecompInterface()
        decompInterface.toggleSyntaxTree(true)
        decompInterface.toggleParamMeasures(true)
        decompInterface.toggleCCode(true)
        if (!decompInterface.openProgram(program))
            throw IllegalStateException("ERROR: Failed to open program: ${decompInterface.lastMessage}")
        return decompInterface
    }
}