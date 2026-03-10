package org.iotsplab.akiba.utils.pcode

import ghidra.program.model.listing.Program
import ghidra.program.model.pcode.PcodeOp
import ghidra.program.model.pcode.Varnode
import org.iotsplab.akiba.utils.memory.MemoryUtil

object PcodeUtil {
    /**
     * Get data flow source and destination varnodes of a LOAD
     * @param op PcodeOp to parse
     * @return Pair of Varnodes, <source, dest>
     */
    @Throws(IllegalArgumentException::class, AssertionError::class)
    fun parseLoad(program: Program, op: PcodeOp): Pair<Varnode, Varnode> {
        return when (op.opcode) {
            PcodeOp.LOAD -> {
                assert(op.inputs[0].isConstant)
                val addrSpace = MemoryUtil.getAddressSpace(op.inputs[0].offset.toInt(), program)
                if(addrSpace.name != "ram") { throw IllegalArgumentException("Invalid address space") }
                assert(op.output.isRegister)
                op.inputs[1] to op.output
            }
            else -> throw IllegalArgumentException("PcodeOp is not a load")
        }
    }

    /**
     * Get data flow source and destination varnodes of a STORE
     * @param op PcodeOp to parse
     * @return Pair of Varnodes, <source, dest>
     */
    @Throws(IllegalArgumentException::class, AssertionError::class)
    fun parseStore(program: Program, op: PcodeOp): Pair<Varnode, Varnode> {
        return when (op.opcode) {
            PcodeOp.STORE -> {
                assert(op.inputs[0].isConstant)
                val addrSpace = MemoryUtil.getAddressSpace(op.inputs[0].offset.toInt(), program)
                if(addrSpace.name != "ram") { throw IllegalArgumentException("Invalid address space") }
                assert(op.inputs[2].isRegister)
                Pair(op.inputs[2], op.inputs[1])
            }
            else -> throw IllegalArgumentException("PcodeOp is not a store")
        }
    }
}