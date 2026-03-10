package org.iotsplab.akiba.utils.emulator

import ghidra.app.decompiler.DecompInterface
import ghidra.program.model.address.Address
import ghidra.program.model.listing.Program
import ghidra.util.task.TaskMonitor
import org.apache.logging.log4j.Logger

/**
 * BasicBlockEmulator: A basic block emulator that emulates a program by breaking at every basic blocks' start and end.
 *
 * The basic block is got through high functions, that are consisted of high-level P-codes.
 *
 * In the class, we provide a set of handlers to control the behavior of the emulator, you can extend this class and
 * override `blockStartHandler` and `blockEndHandler` to do something at the start/end of a basic block.
 */
open class BasicBlockEmulator(
    program: Program,
    entryPoint: Address,
    stackTop: Address,
    logger: Logger? = null,
    protected var maxExecution: Int = Int.MAX_VALUE     // The number of basic blocks executed
) : Emulator(program, entryPoint, stackTop, logger) {
    protected var blockExecuted: Int = 0
    protected val funcExecuted: HashSet<Address> = hashSetOf()
    protected val endBreakpoints: HashSet<Address> = hashSetOf()
    protected val startBreakpoints: HashSet<Address> = hashSetOf()
    protected val decompInterface: DecompInterface = getDecompiler()

    private fun getDecompiler(): DecompInterface {
        val decompInterface = DecompInterface()
        decompInterface.toggleSyntaxTree(true)
        if (!decompInterface.openProgram(program))
            throw IllegalStateException("ERROR: Failed to open program: ${decompInterface.lastMessage}")
        return decompInterface
    }

    @Throws(Exception::class)
    open fun blockStartHandler() {
        logger?.trace("Block start handler")
    }

    @Throws(Exception::class)
    open fun blockEndHandler() {
        logger?.trace("Block end handler")
    }

    override fun startEmulation() {
        try {
            while (blockExecuted < maxExecution) {
                logger?.trace("Break at {}", context.pc)
                if (startBreakpoints.contains(context.pc))
                    blockStartHandler()
                val currentFunc = api.getFunctionContaining(context.pc)
                    ?: throw IllegalStateException("Orphan code detected at ${context.pc}")
                // We add breakpoints dynamically, when a new function is executed, add breakpoints to all blocks
                if (!funcExecuted.contains(currentFunc.entryPoint)) {
                    funcExecuted.add(currentFunc.entryPoint)
                    val highFunc = decompInterface.decompileFunction(
                        currentFunc, DECOMPILE_TIMEOUT, TaskMonitor.DUMMY).highFunction
                    highFunc.basicBlocks.forEach { block ->
                        // Set breakpoints to all starts of blocks
                        if (!startBreakpoints.contains(block.start)) {
                            startBreakpoints.add(block.start)
                            logger?.debug("Added breakpoint at block start: {}", block.start)
                            // To avoid repeating breakpoints
                            if (!endBreakpoints.contains(block.start))
                                context.setBreakpoint(block.start)
                        }
                        // Set breakpoints to all ends of blocks
                        if (!endBreakpoints.contains(block.stop)) {
                            endBreakpoints.add(block.stop)
                            logger?.debug("Added breakpoint at block end: {}", block.stop)
                            // To avoid repeating breakpoints
                            if (!startBreakpoints.contains(block.stop))
                                context.setBreakpoint(block.stop)
                        }
                    }
                }
                if(!context.run(TaskMonitor.DUMMY)) {
                    logger?.error("Error detected: ${context.lastError}")
                    break
                }
                if (endBreakpoints.contains(context.pc)) {
                    logger?.trace("A block is executed to its end")
                    blockExecuted++
                }
                if (endBreakpoints.contains(context.pc))
                    blockEndHandler()
            }
        } catch (e: Exception) {
            logger?.error("Emulation aborted: ${e.message}")
        }
    }

    companion object {

        const val DECOMPILE_TIMEOUT: Int = 60
    }
}