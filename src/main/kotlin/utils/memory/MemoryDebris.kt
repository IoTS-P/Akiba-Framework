package org.iotsplab.akiba.utils.memory

import ghidra.program.model.address.Address
import ghidra.program.model.address.Address.max
import ghidra.program.model.address.Address.min
import ghidra.program.model.pcode.Varnode

open class MemoryDebris (
    var address: Address,
    var size: Int,
) {
    companion object {
        fun fromVarnode(varnode: Varnode): MemoryDebris {
            return MemoryDebris(varnode.address, varnode.size)
        }

        fun assembleMultipleSegments(segments: List<MemoryDebris>): List<MemoryDebris> {
            val ret = mutableListOf<MemoryDebris>()
            segments.forEach { seg ->
                ret.filter {
                    // There may be some overlapping segments
                    !(it.address.add(it.size.toLong()) < seg.address || it.address > seg.address.add(seg.size.toLong()))
                } .let { segments ->
                    if (segments.isEmpty()) {
                        ret.add(seg)
                        return@let
                    }
                    val startMost = min(segments.minByOrNull { it.address }!!.address, seg.address)
                    val endMost = max(segments.sortedBy { it.address.add(it.size.toLong()) }.last().address,
                                        seg.address.add(seg.size.toLong()))
                    segments.forEach { ret.remove(it) }
                    ret.add(MemoryDebris(startMost, endMost.subtract(startMost).toInt()))
                }
            }

            return ret
        }
    }
}