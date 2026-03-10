package org.iotsplab.akiba.utils.memory

import ghidra.program.model.address.AddressSpace

class MemoryFlowSegments {
    private val flowMap: MutableMap<AddressSpace, MutableMap<MemoryDebris, MemoryDebris>> = mutableMapOf()

    @Throws(IllegalArgumentException::class)
    fun add(src: MemoryDebris, dst: MemoryDebris) {
        var newSrc: MemoryDebris = src
        var newDst: MemoryDebris = dst
        require(src.size == dst.size) { "src and dst must have the same size" }
        flowMap.putIfAbsent(src.address.addressSpace, mutableMapOf())

        val previousSegment = flowMap[src.address.addressSpace]?.entries?.find { (k, v) ->
            k.address.offset + k.size == src.address.offset && v.address.offset + v.size == dst.address.offset
        }
        previousSegment ?.let { (k, v) ->
            flowMap[src.address.addressSpace]?.remove(k)
            newSrc = MemoryDebris(k.address, k.size + dst.size)
            newDst = MemoryDebris(v.address, v.size + dst.size)
        }

        val nextSegment = flowMap[src.address.addressSpace]?.entries?.find { (kk, vv) ->
            kk.address.offset == newSrc.address.offset + newSrc.size &&
            vv.address.offset == newDst.address.offset + newDst.size
        }
        nextSegment ?.let { (kk, _) ->
            flowMap[src.address.addressSpace]?.remove(kk)
            newSrc.size += kk.size
            newDst.size += kk.size
        }

        flowMap[src.address.addressSpace]!![newSrc] = newDst
    }

    fun getFlowMap(): Map<MemoryDebris, MemoryDebris> {
        return flowMap.values.flatMap { it.entries }.associate { (k, v) -> k to v }
    }
}