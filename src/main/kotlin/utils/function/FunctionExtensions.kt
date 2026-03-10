package org.iotsplab.akiba.utils.function

import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.address.Address
import ghidra.program.model.address.AddressOutOfBoundsException
import ghidra.program.model.listing.Function
import ghidra.program.model.listing.Instruction
import ghidra.program.model.listing.Program
import ghidra.util.task.TaskMonitor
import org.iotsplab.akiba.utils.assembly.DisasmHelper
import org.iotsplab.akiba.utils.assembly.isGenericJump

fun Function.allInstructions(): ArrayList<Instruction> {
    val instructions = ArrayList<Instruction>()
    var ptr = entryPoint
    val currentProgram = program

    while (ptr < body.maxAddress) {
        val nextInst = currentProgram.listing.getInstructionAt(ptr) ?: return instructions
        instructions.add(nextInst)
        ptr = ptr.add(nextInst.length.toLong())
    }

    return instructions
}

fun Program.allFunctionStarts(): MutableList<Address> {
    val functionStarts = mutableListOf<Address>()
    functionManager.getFunctions(true).forEach {
        it ?.let { functionStarts.add(it.entryPoint) }
    }
    return functionStarts
}

fun Function.allInstructionsString(): String {
    val program = program
    val instIter = program.listing.getInstructions(body.minAddress, true)
    var ret = ""
    while (instIter.hasNext()) {
        val instruction = instIter.next()
        if (!body.contains(instruction.address)) break
        ret += "${instruction.address}: $instruction${System.lineSeparator()}"
    }
    return ret
}

fun Function.allCallingTargets(offset: Long = 0, monitor: TaskMonitor): List<Address> {
    val descendants = getCalledFunctions(monitor).map { it.entryPoint }.toMutableSet()
    try {
        if (isThunk) {
            val thunkTarget = getThunkedFunction(false)
            val address = FlatProgramAPI(program)
                .toAddr(thunkTarget.name.split("_").last().toInt(16))
                .subtract(offset)
            if (program.listing.getFunctionAt(address) != null
                || DisasmHelper(program).disasmFunction(address, monitor = monitor) != null)
                descendants.add(address)
        }
    } catch (_: AddressOutOfBoundsException) {}
    return descendants.toList()
}

fun Function.allBasicBlockStarts(): List<Address> {
    val set = mutableSetOf<Address>(entryPoint)
    val rm = program.referenceManager

    // Add all jumps
    allInstructions().forEach {
        if (isGenericJump.test(it))
            set.add(it.address)
    }

    // Some jumps may target to non-jump instructions, they are also block starts
    set.addAll(allInstructions().filter { rm.hasReferencesTo(it.address) }.map { it.address })

    return set.toList()
}