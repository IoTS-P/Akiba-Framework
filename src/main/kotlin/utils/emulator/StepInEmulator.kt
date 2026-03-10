package org.iotsplab.akiba.utils.emulator

import ghidra.pcode.emu.jit.gen.op.CallOtherMissingOpGen
import ghidra.pcode.emu.jit.gen.op.CallOtherOpGen
import ghidra.program.model.address.Address
import ghidra.program.model.lang.Register
import ghidra.program.model.listing.Program
import ghidra.program.model.pcode.PcodeOp
import ghidra.util.task.TaskMonitor
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.structure.DataflowManager
import org.iotsplab.akiba.utils.assembly.DisasmHelper
import org.iotsplab.akiba.utils.memory.MemoryDebris
import org.iotsplab.akiba.utils.memory.RuntimeStackFrameChain
import java.math.BigInteger

/**
 * StepInEmulator: A basic emulator that breaks at every single instruction. Useful when you need to control the exact
 *                 number of instructions executed, or to seek for deep analysis while emulating.
 *
 * Due to the feature of this emulator, it could be a lot slower than other emulators. If you seek for extreme speed,
 * you are recommended to use `JitPcodeEmulator` to emulate the program.
 *
 * In the class, we provide a set of handlers to control the behavior of the emulator, including `beforeStep` that will
 * be done before an instruction is executed, and `afterStep` that will be done after. The `beforeStep` has a default
 * implementation.
 */
open class StepInEmulator(
    program: Program,
    entryPoint: Address,
    stackTop: Address,
    logger: Logger? = null,
    private val options: Int = SUPPORT_SKIP_CALLOTHER or SUPPORT_AUTO_DISASSEMBLE_AFTER_JUMP,
    private val maxExecution: Long = Long.MAX_VALUE,
    val monitor: TaskMonitor
) : Emulator(program, entryPoint, stackTop, logger) {
    // If we just run, we cannot know how many instructions have been executed
    protected var instExecuted: Int = 0
    // Distinct instructions executed
    protected var distinctInstExecuted: HashSet<Address> = hashSetOf()
    // Distinct number of functions executed, the elements are the entry point of functions
    protected var distinctFunctionExecuted: HashSet<Address> = hashSetOf()
    protected val dataFlowManager = DataflowManager()
    protected lateinit var stackFrameManager: RuntimeStackFrameChain

    val assembledDataflow: Map<MemoryDebris, MemoryDebris>
        get() = dataFlowManager.assembleFlow()
    val assembledZeroStore: List<MemoryDebris>
        get() = dataFlowManager.assembleZeroStore()

    init {
        // Check the option arguments
        if (options and SUPPORT_AUTO_DISASSEMBLE_AFTER_JUMP != 0)
            require(options and SUPPORT_DATA_FLOW_ANALYZER != 0) {
                "Auto runtime disassembling requires data flow analyzer"
            }
    }

    override fun initialization() {
        super.initialization()
        instExecuted = 0
        distinctInstExecuted.clear()
        distinctFunctionExecuted.clear()
        dataFlowManager.clear()
        stackFrameManager = RuntimeStackFrameChain(context, stackTop)   // discard previous frame recorder
        // If entry point is not recognized as codes, disassemble it and define a function there
        if (options and SUPPORT_AUTO_DISASSEMBLE_ENTRYPOINT != 0 && api.getInstructionAt(entryPoint) == null) {
            logger?.info("Entry point not recognized as codes, disassemble it.")
            DisasmHelper(program).disasmFunction(entryPoint, monitor = monitor)
        }
    }

    /**
     * Something needed to be done before the execution of next instruction.
     *
     * @param addr The address of the next instruction to be executed.
     * @return What this emulator should do to the next instruction:
     *      - NEXT_BEHAVIOR_NORMAL: Execute it normally.
     *      - NEXT_BEHAVIOR_SKIP: Skip it.
     *      - NEXT_BEHAVIOR_STOP: Stop the emulation immediately.
     */
    @Throws(IllegalStateException::class)
    open fun beforeStep(addr: Address, presetDisasmRegs: Map<Register, BigInteger> = mapOf()): Int {
        if (context.nextInst == null)
            throw IllegalArgumentException(
                "Next instruction not found, address: ${context.pc.offset.toString(16)}")
        else
            logger?.trace("{} {}", context.pc, context.nextInst.toString())

        if (options and SUPPORT_SKIP_CALLOTHER != 0 &&
            context.nextInst!!.pcode.any { it.opcode == PcodeOp.CALLOTHER }) {
            logger?.debug("Detected CALLOTHER, skipped")
            return NEXT_BEHAVIOR_SKIP
        }

        if (options and SUPPORT_DATA_FLOW_ANALYZER != 0)
            context.analyzeNextInstructionDataflow(dataFlowManager)

        if (options and SUPPORT_AUTO_DISASSEMBLE_AFTER_JUMP != 0) {
            // If the jump target is not disassembled yet, do it and define a function starting there.
            if (dataFlowManager.willJump && api.getInstructionAt(dataFlowManager.jumpTarget!!) == null) {
                logger?.info("Detected jump to ${dataFlowManager.jumpTarget} which is not disassembled, " +
                        "disassemble it.")
                DisasmHelper(program).disasmFunction(
                    dataFlowManager.jumpTarget!!, presetDisasmRegs, monitor)
                if (api.getFunctionAt(dataFlowManager.jumpTarget!!) != null)
                    logger?.debug("Successfully created a function at " +
                            dataFlowManager.jumpTarget!!.offset.toString(16))
            }
        }

        if (options and SUPPORT_STACK_MONITOR != 0)
            stackFrameManager.update(context.nextInst!!)

        if (options and SUPPORT_DISTINCT_INSTRUCTION_COUNTER != 0)
            distinctInstExecuted.add(context.pc)

        if (options and SUPPORT_DISTINCT_FUNCTION_COUNTER != 0)
            distinctFunctionExecuted.add(context.currentFunction.entryPoint)

        return NEXT_BEHAVIOR_NORMAL
    }

    override fun startEmulation() {
        try {
            while (instExecuted < maxExecution) {
                if (monitor.isCancelled) {
                    logger?.warn("Stopped due to monitor cancellation")
                    return
                }

                val behavior: Int = beforeStep(context.pc)
                when (behavior) {
                    NEXT_BEHAVIOR_NORMAL -> { /* Do nothing */ }
                    NEXT_BEHAVIOR_SKIP -> {
                        context.skipNext()
                        continue
                    }
                    NEXT_BEHAVIOR_STOP -> {
                        logger?.info("Stopped due to user-defined beforeStep")
                        break
                    }
                }
                if (!context.step(monitor)) {
                    logger?.error("Error detected: ${context.lastError}")
                    break
                }
                instExecuted++
                afterStep(context.pc)
            }
        } catch (e: Exception) {
            logger?.error("Emulation aborted: ${e.message}")
        }
    }

    open fun afterStep(addr: Address) {}

    companion object {
        const val SUPPORT_STACK_MONITOR: Int = 0x01
        const val SUPPORT_DATA_FLOW_ANALYZER: Int = 0x02
        const val SUPPORT_SKIP_CALLOTHER: Int = 0x04
        const val SUPPORT_DISTINCT_INSTRUCTION_COUNTER: Int = 0x08
        const val SUPPORT_DISTINCT_FUNCTION_COUNTER: Int = 0x10
        const val SUPPORT_AUTO_DISASSEMBLE_AFTER_JUMP: Int = 0x20
        const val SUPPORT_AUTO_DISASSEMBLE_ENTRYPOINT: Int = 0x40

        const val NEXT_BEHAVIOR_NORMAL: Int = 0
        const val NEXT_BEHAVIOR_SKIP: Int = 1
        const val NEXT_BEHAVIOR_STOP: Int = 2
    }
}