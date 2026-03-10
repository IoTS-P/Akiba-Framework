package org.iotsplab.akiba.utils.memory

import ghidra.program.model.address.Address
import ghidra.program.model.listing.Program
import ghidra.program.model.mem.MemoryAccessException
import java.util.*

class EnhancedMemorySearcher(val program: Program) {
    @Throws(MemoryAccessException::class, IllegalArgumentException::class)
    fun nextAddressNotZero(addr: Address, align: Int): Address? {
        return nextAddressNotZero(addr, program.maxAddress, align)
    }

    @Throws(MemoryAccessException::class, IllegalArgumentException::class)
    fun nextAddressNotZero(addr: Address, ceil: Address, align: Int): Address? {
        var addr = addr
        if (!program.memory.contains(addr))
            return null

        if (Arrays.stream(SUPPORTED_ALIGNS).noneMatch { a: Int -> a == align })
            throw IllegalArgumentException("Invalid align: $align")

        // To handle super large binaries with a lot 00 bytes, we need to skip these empty huge gaps.
        while (addr.offset % align != 0L) {
            val i = program.memory.getByte(addr)
            if (i != 0.toByte()) return addr
            addr = addr.add(1)
        }

        while (addr < ceil.subtract(ceil.offset % align) && ceil.subtract(addr) >= 8) {
            val i = program.memory.getLong(addr)
            if (i != 0L) break
            addr = addr.add(8)
        }

        if (ceil.subtract(addr) < align)
            return null

        var bias = 0
        while ((bias < 8 / align) && (addr < ceil)) {
            var v: Long = 0
            when (align) {
                1 -> v = program.memory.getByte(addr.add((bias * align).toLong())).toLong()
                2 -> v = program.memory.getShort(addr.add((bias * align).toLong())).toLong()
                4 -> v = program.memory.getInt(addr.add((bias * align).toLong())).toLong()
                8 -> v = program.memory.getLong(addr.add((bias * align).toLong()))
            }
            if (v != 0L) return addr.add((bias * align).toLong())
            bias++
        }

        return addr
    }


    @Throws(MemoryAccessException::class, IllegalArgumentException::class)
    inline fun <reified T : Comparable<T>> nextAddressNot(addr: Address, avoids: Array<T>): Address {
        return nextAddressNot(addr, program.maxAddress.add(1), avoids)
    }


    @Throws(MemoryAccessException::class, IllegalArgumentException::class)
    inline fun <reified T : Comparable<T>> nextAddressNot(addr: Address, ceil: Address, avoids: Array<T>): Address {
        if (addr > ceil) return ceil

        var ceil = ceil
        var ptr = addr

        val t = TreeSet(listOf(*avoids))

        val align = MemoryUtil.intClassToSize(T::class)

        if ((ceil.offset - addr.offset) % align != 0L) ceil = ceil.subtract((ceil.offset - addr.offset) % align)

        while (ptr < ceil) {
            val v = MemoryUtil.readSmall(program, ptr, align)

            if (t.stream().noneMatch { tt: T -> (tt as Number).toLong() == v }) return ptr

            ptr = ptr.add(align.toLong())
        }

        return ceil
    }

    companion object {
        val SUPPORTED_ALIGNS: IntArray = intArrayOf(1, 2, 4, 8)
    }
}
