package org.iotsplab.akiba.utils.memory

import ghidra.features.base.memsearch.searcher.MemoryMatch
import ghidra.program.model.address.Address
import ghidra.program.model.address.AddressSpace
import ghidra.program.model.listing.Program
import ghidra.program.model.mem.Memory
import ghidra.program.model.mem.MemoryAccessException
import ghidra.util.task.TaskMonitor
import org.iotsplab.akiba.module.Log
import java.nio.charset.Charset
import kotlin.reflect.KClass

class MemoryUtil {

    companion object {
        const val PAGE_MASK = 0xFFFF_FFFF_FFFF_F000UL

        @JvmStatic
        @Throws(IllegalArgumentException::class, MemoryAccessException::class)
        fun readSmall(program: Program, address: Address, size: Int) : Long {
            return when (size) {
                1 -> program.memory.getByte(address).toLong()
                2 -> program.memory.getShort(address).toLong()
                4 -> program.memory.getInt(address).toLong()
                8 -> program.memory.getInt(address).toLong()
                else -> throw IllegalArgumentException("Unsupported alignment: $size")
            }
        }

        @JvmStatic
        @Throws(UnsupportedOperationException::class)
        fun<T : Any> intClassToSize(type: KClass<T>) : Int {
            return when {
                type.isInstance(0.toByte()) -> 1
                type.isInstance(0.toShort()) -> 2
                type.isInstance(0.toInt()) -> 4
                type.isInstance(0.toLong()) -> 8
                else -> throw UnsupportedOperationException("${(type as Any).javaClass.simpleName} is not integer type")
            }
        }

        @JvmStatic
        @Throws(IllegalArgumentException::class)
        fun bytesToLong(bytes: ByteArray): Long {
            require(bytes.size <= 8) { "Byte array too long (${bytes.size})" }
            var ret: Long = 0
            bytes.forEachIndexed { index, byte -> ret = ret or (byte.toLong() shl (index * 8)) }
            return ret
        }

        @JvmStatic
        @Throws(UnsupportedOperationException::class)
        fun<T> numberToBytes(value: T): ByteArray {
            return when (value) {
                is Byte -> byteArrayOf(value)
                is Short -> byteArrayOf(value.toByte(), (value.toInt() shr 8).toByte())
                is Int -> numberToBytes((value and 0xffff).toShort())
                    .plus(numberToBytes((value shr 16).toShort()))
                is Long -> numberToBytes((value and 0xffff_ffff).toInt())
                    .plus(numberToBytes((value shr 32).toInt()))
                else -> throw IllegalArgumentException("Unsupported type: ${value?.javaClass?.simpleName?:"null"}")
            }
        }

        @JvmStatic
        @Throws(MemoryAccessException::class)
        fun readProgramBytesToUTF8String(memory: Memory, addr: Address, length: Int): String {
            val matchedBytes = ByteArray(length)
            memory.getBytes(addr, matchedBytes)
            return matchedBytes.toString(Charset.forName("UTF-8"))
        }

        @JvmStatic
        @Throws(MemoryAccessException::class)
        fun readProgramBytesToUTF8String(memory: Memory, result: MemoryMatch): String {
            return readProgramBytesToUTF8String(memory, result.address, result.length)
        }

        @JvmStatic
        @Throws(MemoryAccessException::class)
        fun moveWholeBlock(memory: Memory, imageBase: Long) {
            val addressSpace = memory.program.addressFactory.defaultAddressSpace
            val block = memory.getBlock(memory.minAddress)
            memory.moveBlock(block, addressSpace.getAddress(imageBase), TaskMonitor.DUMMY)
        }

        /**
         * Moves all blocks by the image base. Relative offsets of blocks remains the same.
         * E.g. If there are 2 blocks for [0, 0x1000) and [0x3000, 0x4000), Moving to image base 0x100000:
         *      [0x100000, 0x101000) and [0x103000, 0x104000).
         */
//        @JvmStatic
//        @Throws(MemoryAccessException::class)
//        fun moveAllBlocks(memory: Memory, addr: Long) {
//            WorkspaceManager.globalLogger.info("Moving to absolute address ${addr.toString(16)}")
//            val blocks = memory.blocks.sortedBy { it.start }
//            val firstBlockStart = blocks.first().start
//            val addressSpace = memory.program.addressFactory.defaultAddressSpace
//            blocks.forEach { block ->
//                memory.moveBlock(block,
//                    addressSpace.getAddress(block.start.subtract(firstBlockStart)).add(addr), TaskMonitor.DUMMY)
//            }
//        }

        @JvmStatic
        @Throws(MemoryAccessException::class, IllegalArgumentException::class)
        fun moveAllBlocksInOffset(memory: Memory, offset: Long) {
            Log.current.info("Moving to relative address ${offset.toString(16)}")
            val blocks = memory.blocks.sortedBy { it.start }
            if (offset < 0 && blocks.first().start.offset < -offset)
                throw IllegalArgumentException("Movement of ${offset.toString(16)} will cause addr downflow")
            else if (offset > 0) {
                val memorySpaceCeil = memory.blocks.last().addressRange.addressSpace.maxAddress.offset
                try {
                    if (Math.addExact(memory.blocks.last().end.offset, offset) > memorySpaceCeil)
                        throw IllegalArgumentException(
                            "Movement of ${offset.toString(16)} will cause addr space overflow")
                } catch (_: ArithmeticException) {
                    throw IllegalArgumentException("Movement of ${offset.toString(16)} will cause addr overflow")
                }
            } else if (offset == 0L)  // offset == 0, no need to move
                return
            // To avoid overlaps during movements, if offset > 0, we should start from the last block
            // if offset < 0, we should start from the first block
            if (offset > 0)
                blocks.reversed().forEach { block ->
                    memory.moveBlock(block, block.start.add(offset), TaskMonitor.DUMMY)
                }
            else
                blocks.forEach { block ->
                    memory.moveBlock(block, block.start.subtract(-offset), TaskMonitor.DUMMY)
                }
        }

        @JvmStatic
        fun Address.getPageStartUnchecked(): Address {
            return subtract(offset % 0x1000)
        }

        @JvmStatic
        fun getPagesIncluding(addr: Address, length: Long): Pair<Address, Address> {
            val pageStart = addr.getPageStartUnchecked()
            val pageEnd = addr.add(length - 1).getPageStartUnchecked()
            return Pair(pageStart, pageEnd)
        }

        @JvmStatic
        fun getAddressSpace(id: Int, program: Program): AddressSpace {
            return program.addressFactory.getAddressSpace(id)
        }

        @JvmStatic
        fun addrIsInvalid(addr: Address): Boolean {
            return addr.offset in 0L..0xfff ||
                    addr.offset in
                    (1.toBigInteger() shl (addr.size * 8) - 0x1000).toLong()..
                    (1.toBigInteger() shl (addr.size * 8) - 1).toLong()
        }

        /**
         * Finds the upmost free space of the given size in Memory.
         * It can be used to generate a probably usable stack space for an emulation that does not care about the stack.
         */
        @JvmStatic
        fun Memory.getUpmostFreeSpace(size: Long): Address? {
            var start = this.maxAddress.subtract(size - 1)
            blocks.sortedBy { it.start }.reversed().forEach {
                if (it.contains(start))
                    start = it.start.subtract(size)
                else
                    return start
            }
            return null
        }
    }
}

fun tryParseToLong(str: String): Long? {
    listOf(2, 8, 10, 16).forEach { radix ->
        when (radix) {
            2 -> if (str.startsWith("0b") || str.startsWith("-0b"))
                str.substring(2).toLongOrNull(radix) ?. let { return it }
            8 -> if (str.startsWith("0") || str.startsWith("-0"))
                str.substring(1).toLongOrNull(radix) ?. let { return it }
            10 -> str.substring(1).toLongOrNull(radix) ?. let { return it }
            16 -> if (str.startsWith("0x") || str.startsWith("-0x"))
                str.substring(2).toLongOrNull(radix) ?. let { return it }
        }
    }
    return null
}