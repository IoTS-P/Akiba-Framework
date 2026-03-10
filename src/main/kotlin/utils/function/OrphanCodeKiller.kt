package org.iotsplab.akiba.utils.function

import ghidra.program.model.listing.Program
import org.iotsplab.akiba.utils.assembly.AsmCodeClearer

object OrphanCodeKiller {
    /**
     * There may be some codes that don't belong to any functions, functions can be defined here to clear them.
     * @param program: program need to process
     */
    @JvmStatic
    fun process(program: Program) {
        val cc = AsmCodeClearer(program)
        val ii = program.listing.getInstructions(true)
        while (ii.hasNext()) {
            val inst = ii.next()
            if (program.listing.getFunctionContaining(inst.address) == null) {
                // Just clear the orphan code, because these codes may not be 'actual codes'
                cc.clearCodeStartsWith(inst.address, inst.address.add(inst.length.toLong()))
            }
        }
    }
}
