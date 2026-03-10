package org.iotsplab.akiba.utils.memory

import ghidra.app.emulator.EmulatorHelper
import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.address.Address
import ghidra.program.model.lang.Register
import ghidra.program.model.listing.Instruction
import ghidra.program.model.listing.Program
import java.math.BigInteger

class RuntimeStackFrame(private val emu: EmulatorHelper, ceil: Address): MemoryDebris(ceil, 0) {
    var callerAddress: Address? = null
    var parentFrame: RuntimeStackFrame? = null
    var childFrame: RuntimeStackFrame? = null
    var registerSnapshot: MutableMap<Register, BigInteger>? = null
    var instructionExecuted: Int = 0
    private val api: FlatProgramAPI = FlatProgramAPI(program)

    val program: Program
        get() = emu.program
    val returnFallThrough: Instruction?
        get() = api.getInstructionAt(callerAddress).next
}