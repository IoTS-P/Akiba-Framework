package org.iotsplab.akiba.utils.assembly

import ghidra.app.cmd.register.SetRegisterCmd
import ghidra.app.plugin.core.analysis.AutoAnalysisManager
import ghidra.program.disassemble.Disassembler
import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.address.Address
import ghidra.program.model.address.AddressSet
import ghidra.program.model.lang.Register
import ghidra.program.model.listing.Function
import ghidra.program.model.listing.Instruction
import ghidra.program.model.listing.Program
import ghidra.util.task.TaskMonitor
import java.math.BigInteger

class DisasmHelper(private val program: Program) {
    private val api = FlatProgramAPI(program)
    private val aam: AutoAnalysisManager = AutoAnalysisManager.getAnalysisManager(program)

    fun disasmOne(address: Address, monitor: TaskMonitor): Instruction? {
        val disassembler = Disassembler.getDisassembler(program, monitor, null)
        val targetSet = AddressSet(address)
        disassembler.disassemble(address, targetSet, false)
        return program.listing.getInstructionContaining(address)
    }

    /**
     * disasmFunction: disassemble memory region starting at [start] address with preset register values.
     * @param start: start address
     * @param presetRegisterValues: preset register values, some arch may need some control register values to guide
     *                              disassembling processes, like `TMode` in ARM Cortex to determine if the disassembler
     *                              should disassemble in thumb mode.
     * @return Function created
     */
    fun disasmFunction(start: Address,
                       presetRegisterValues: Map<Register, BigInteger> = mapOf(),
                       monitor: TaskMonitor
    ): Function? {
        presetRegisterValues.forEach { k, v ->
            SetRegisterCmd(k, start, start.add(1), v).applyTo(program)
        }

        aam.disassemble(start)
        aam.initializeOptions()
        aam.createFunction(start, true)

        aam.startAnalysis(monitor)
        aam.waitForAnalysis(null, monitor)
        aam.cancelQueuedTasks()

        val functionGot = program.listing.getFunctionContaining(start)
        if (functionGot != null) {
            return functionGot
        } else {
            AsmCodeClearer(program).clearCodeStartsWith(start)
            return null
        }
    }

    fun clearOne(address: Address) {
        AsmCodeClearer(program).clearCodeStartsWith(address, address.add(1))
    }
}