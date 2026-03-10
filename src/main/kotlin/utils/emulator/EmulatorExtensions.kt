package org.iotsplab.akiba.utils.emulator

import ghidra.app.emulator.EmulatorHelper
import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.address.Address
import ghidra.program.model.lang.RegisterValue
import ghidra.program.model.listing.Function
import ghidra.program.model.listing.Instruction
import ghidra.program.model.pcode.PcodeOp
import ghidra.util.exception.CancelledException
import ghidra.util.task.TaskMonitor
import org.iotsplab.akiba.structure.DataflowManager
import org.iotsplab.akiba.structure.PcodePolynomialTreeset
import org.iotsplab.akiba.utils.assembly.analyzeMemoryDataFlow
import org.iotsplab.akiba.utils.memory.RuntimeStackFrameChain
import org.iotsplab.akiba.utils.pcode.PcodeConstants
import java.util.function.Predicate

var EmulatorHelper.pc: Address
    get() = FlatProgramAPI(program).toAddr(readRegister(pcRegister).toLong())
    set(value) = writeRegister(pcRegister, value.offset)

val EmulatorHelper.sp: Address
    get() = FlatProgramAPI(program).toAddr(readRegister(stackPointerRegister).toLong())

val EmulatorHelper.nextInst: Instruction?
    get() = program.listing.getInstructionAt(pc)

val EmulatorHelper.currentFunction: Function
    get() = program.functionManager.getFunctionContaining(pc)!!

/**
 * Run the emulator until the condition is met, no need to create breakpoints
 * Attention: This may cause huge time cost because we need to step and check every instruction!
 *
 * @param condition The condition to be met
 * @throws CancelledException When the task monitor is set cancelled while executing instructions
 */
@Throws(CancelledException::class)
fun EmulatorHelper.until(condition: () -> Boolean, monitor: TaskMonitor) {
    while (!condition()) {
        if (!step(monitor))
            return      // If the emulator exited unexpectedly, we can get the error info through `lastError`
    }
}

/**
 * Run the emulator until the next instruction matches the condition, no need to create breakpoints
 * Attention: This may cause huge time cost if presetBreakpoints is not set, because we need to step and check every
 *            instruction!
 *
 * @param condition The condition to be met
 */
fun<T: Predicate<Instruction>> EmulatorHelper.until(condition: T, presetBreakpoints: Boolean = true) {
    if (presetBreakpoints) {
        setBreakpointsIf(condition)
        run(TaskMonitor.DUMMY)
        return
    } else {
        while (!condition.test(nextInst?: run { return })) {
            if (!step(TaskMonitor.DUMMY))
                return      // If the emulator exited unexpectedly, we can get the error info through `lastError`
        }
    }
}

/**
 * Run the emulator until the next instruction matches the condition, no need to create breakpoints
 * Attention: This may cause huge time cost because we need to step and check every instruction!
 *
 * @param addr The addresses to break
 */
fun EmulatorHelper.until(addr: List<Address>) {
    addr.forEach { setBreakpoint(it) }
    try {
        run(TaskMonitor.DUMMY)
    } finally {
        addr.forEach { clearBreakpoint(it) }
    }
}

/**
 * Pre-create all breakpoints for all instructions that satisfy the condition
 *
 * @param condition The condition to be met
 */
fun<T: Predicate<Instruction>> EmulatorHelper.setBreakpointsIf(condition: T) {
    program.listing.getInstructions(true).forEach {
        if (condition.test(it))
            setBreakpoint(it.address)
    }
}

/**
 * Analyze the data flow of the next instruction, the analysis results will be saved into the manager
 *
 * @param manager The manager to save the analysis results
 */
@Throws(IllegalStateException::class)
fun EmulatorHelper.analyzeNextInstructionDataflow(manager: DataflowManager) {
    nextInst?.analyzeMemoryDataFlow(this, manager) ?: throw IllegalStateException("Next instruction is null")
}

/**
 * Skip the next instruction that should be executed in the short future
 */
@Throws(IllegalStateException::class)
fun EmulatorHelper.skipNext() {
    this.emulator.setExecuteAddress(pc.offset + (nextInst?.bytes?.size?.toLong()
        ?: throw IllegalStateException("Next instruction is null")
    ))

    // During the emulation, the Thumb status could be lost if we skip instructions containing CALLOTHERs while
    // the previous instruction is not a jump. So we need to restore it every time we skip instructions.
    if (Regex("ARM:(LE|BE):32:Cortex").matches(this.language.languageID.idAsString)) {
        this.contextRegister = RegisterValue(this.language.getRegister("TMode"), 1.toBigInteger())
    }
}

/**
 * Force the emulation to exit the last function executed, and to go back to the caller function
 *
 * @param frameStatus The status of the stack frame
 * @throws IllegalStateException If the stack frame has no caller function, unable to return
 */
@Throws(IllegalStateException::class)
fun EmulatorHelper.returnDirectly(frameStatus: RuntimeStackFrameChain) {
    check(frameStatus.frames.size >= 2) { "Stack frame has no caller function, unable to return" }
    // Restore register snapshot of caller function
    val snapshot = frameStatus.frames.reversed()[1].registerSnapshot!!
    program.language.registers.forEach {
        writeRegister(it, snapshot[it])
    }
    // Change pc
    pc = frameStatus.frames.last().returnFallThrough!!.address
    // Pop the last frame
    frameStatus.frames.removeLast()
}

/**
 * Foresee the next instruction that will be executed according to the context at the moment before this instruction is
 * executed. If the instruction has no possibility to jump PC, returns the address of the next instruction, if there is
 * no instructions ahead, return null.
 *
 * @return The address of the next instruction or null if there is no instructions ahead
 */
fun EmulatorHelper.foreseeNextInstruction(): Instruction? {
    nextInst ?: return null
    val ni = nextInst!!
    val trees = PcodePolynomialTreeset()
    ni.pcode.forEachIndexed { _, it ->
        when (it.opcode) {
            PcodeOp.CBRANCH -> {
                val conditionValue = trees.calculate(this, it.inputs[1])
                if (conditionValue.first != 0L)
                    return program.listing.getInstructionAt(it.inputs[0].address)
            }
            in listOf(PcodeOp.CALL, PcodeOp.BRANCH)  -> return program.listing.getInstructionAt(it.inputs[0].address)
            in listOf(PcodeOp.CALLIND, PcodeOp.BRANCHIND, PcodeOp.RETURN) -> {
                val target = trees.calculate(this, it.inputs[0])
                return program.listing.getInstructionAt(FlatProgramAPI(program).toAddr(target.first))
            }
            else -> try { trees.addPcode(it) } catch (_: Exception) {}
        }
    }
    // No jumps, just next instruction (it may be null)
    // We don't consider arch-specific instructions like `svc` in ARM
    return ni.next
}



/**
 * Foresee the next instruction that will be executed in this function.
 * The difference between this function and `foreseeNextInstruction` is that this function will ignore calls.
 * If the instruction is a call, this function will skip the call process and returns the fall-through address.
 * If the instruction is a jump, this function will check whether the target is in the function. If not, return null.
 */
fun EmulatorHelper.foreseeNextInstructionInFunc(): Instruction? {
    nextInst ?: return null
    val ni = nextInst!!
    return when (ni.pcode.last().opcode) {
        in setOf(PcodeOp.RETURN, PcodeOp.CALLOTHER) -> null
        in setOf(PcodeOp.CALL, PcodeOp.CALLIND) -> ni.next
        in PcodeConstants.JUMP_OPCODES -> {
            val nextInst = foreseeNextInstruction() ?: return null
            return if (currentFunction.body.contains(nextInst.address)) nextInst else null
        }
        else -> ni.next
    }
}