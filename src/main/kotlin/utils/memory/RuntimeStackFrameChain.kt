package org.iotsplab.akiba.utils.memory

import ghidra.app.emulator.EmulatorHelper
import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.address.Address
import ghidra.program.model.listing.Instruction
import ghidra.program.model.listing.Program
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.module.Log
import org.iotsplab.akiba.utils.assembly.isReturn
import org.iotsplab.akiba.utils.emulator.foreseeNextInstruction
import org.iotsplab.akiba.utils.emulator.pc
import org.iotsplab.akiba.utils.emulator.sp

class RuntimeStackFrameChain(
    private val emu: EmulatorHelper,
    private val stackTop: Address
) {
    val frames: MutableList<RuntimeStackFrame> = mutableListOf(RuntimeStackFrame(emu, stackTop))

    private val program: Program
        get() = emu.program
    private val api: FlatProgramAPI
        get() = FlatProgramAPI(program)

    val returnAddress: Address?
        get() = run {
            val callerInst = program.listing.getInstructionAt(frames.last().callerAddress) ?: return null
            program.listing.getInstructionAt(callerInst.address.add(callerInst.bytes.size.toLong()))?.address
        }

    /**
     * Update stack frame chain through a call target address
     *
     * @param callTarget Call target address
     * @throws IllegalArgumentException if no instruction found at target address
     */
    @Throws(IllegalArgumentException::class)
    fun update(callTarget: Address) {
        check(api.getInstructionContaining(callTarget) != null) {
            "No instruction found at target address"
        }
        frames.last().let {
            it.size = (it.address.offset - emu.sp.offset).toInt()
            it.childFrame = RuntimeStackFrame(emu, api.toAddr(emu.sp.offset)).let { child ->
                child.parentFrame = it
                // Treat pc as caller address, if misused, this could be wrong, send instruction is more recommended
                child.callerAddress = emu.pc
                child
            }
            // Save all register values before call
            it.registerSnapshot = program.language.registers.associateWith { r -> emu.readRegister(r) }.toMutableMap()
        }
    }

    /**
     * Update stack frame chain through the next instruction to be executed.
     * Attention: Must be executed before every instruction !
     *
     * @param inst the next instruction to execute
     * @throws IllegalArgumentException if no instruction found at target address
     */
    @Throws(IllegalArgumentException::class)
    fun update(inst: Instruction) {
        frames.last().instructionExecuted++
        val func = api.getFunctionContaining(inst.address)
        val nextInst = emu.foreseeNextInstruction()
        nextInst ?: return
        api.getFunctionContaining(nextInst.address).let { targetFunc ->
            // Still in this function, no need to create new stack frame
            if (targetFunc == func) {
                frames.last().size = (frames.last().address.offset - emu.sp.offset).toInt()
                return
            }
            if (targetFunc == null) {
                Log.current.warn("Next instruction doesn't belong to any function!")
                return
            }
            if (isReturn.test(nextInst)) {
                frames.removeLast()
                return
            }
            update(nextInst.address)
        }
    }
}