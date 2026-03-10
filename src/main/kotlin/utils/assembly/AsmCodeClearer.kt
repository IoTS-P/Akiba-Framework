package org.iotsplab.akiba.utils.assembly

import ghidra.program.model.address.Address
import ghidra.program.model.listing.Program

class AsmCodeClearer(private val program: Program) {
    @JvmOverloads
    fun clearCodeStartsWith(start: Address, mostEnd: Address = program.maxAddress) {
        var ptr = start
        while (ptr < mostEnd) {
            val inst = program.listing.getInstructionAt(ptr) ?: break
            ptr = ptr.add(inst.length.toLong())
        }

        if (ptr != start)
            program.listing.clearCodeUnits(start, ptr.subtract(1), true)
    }
}
