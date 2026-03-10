package org.iotsplab.akiba.structure

import ghidra.program.model.address.Address
import ghidra.program.model.lang.Register
import ghidra.program.model.pcode.Varnode
import org.iotsplab.akiba.utils.memory.MemoryFlowSegments
import org.iotsplab.akiba.utils.memory.MemoryDebris

class DataflowManager(
    val registersWithMemoryData: MutableMap<Register, Varnode> = mutableMapOf(),
    val dataFlowRecorder: MutableList<Pair<Varnode, Varnode>> = mutableListOf(),
    val zeroStoreRecorder: MutableList<Varnode> = mutableListOf(),
)  {
    var willJump: Boolean = false
    var jumpTarget: Address? = null

    fun assembleFlow(): Map<MemoryDebris, MemoryDebris> {
        val ret = MemoryFlowSegments()
        dataFlowRecorder.forEach {
            ret.add(MemoryDebris.fromVarnode(it.first), MemoryDebris.fromVarnode(it.second))
        }
        return ret.getFlowMap()
    }

    fun assembleZeroStore(): List<MemoryDebris> {
        return MemoryDebris.assembleMultipleSegments(zeroStoreRecorder.map { MemoryDebris.fromVarnode(it) })
    }

    fun clear() {
        registersWithMemoryData.clear()
        dataFlowRecorder.clear()
        zeroStoreRecorder.clear()
    }
}
