package org.iotsplab.akiba.utils.emulator

import ghidra.app.emulator.DefaultEmulator
import ghidra.app.emulator.EmulatorHelper
import ghidra.pcode.memstate.MemoryFaultHandler
import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.address.Address
import ghidra.program.model.lang.RegisterValue
import ghidra.program.model.listing.Program
import ghidra.util.task.TaskMonitor
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.utils.memory.MemoryUtil
import org.iotsplab.akiba.utils.memory.MemoryUtil.Companion.getPageStartUnchecked
import java.io.Closeable
import java.math.BigInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * Emulator: class for emulating Ghidra program through Ghidra default emulator
 */
open class Emulator(
    protected val program: Program,
    var entryPoint: Address,
    var stackTop: Address,
    protected val logger: Logger? = null
) : Closeable {
    lateinit var context: EmulatorHelper
    lateinit var emulator: DefaultEmulator
    lateinit var api: FlatProgramAPI

    // Default memory fault handler: creating the address that causes memory fault and treat it as accessible
    // In default handler, R/W instructions in 0 page/the highest page are forbidden.
    protected var mfh: MemoryFaultHandler = object : MemoryFaultHandler {
        @Throws(IllegalStateException::class)
        override fun uninitializedRead(addr: Address, size: Int, buf: ByteArray, bufOffset: Int): Boolean {
            if (addr.addressSpace.isRegisterSpace) {
                context.writeMemory(addr, ByteArray(4) { 0x00.toByte() })
                return true
            }

            check(!MemoryUtil.addrIsInvalid(addr)) { "Got invalid address access: 0x${addr.offset.toString(16)}" }
            val pageIncluded = MemoryUtil.getPagesIncluding(addr, size.toLong())
            var unmappedPage = pageIncluded.first
            while (unmappedPage != pageIncluded.second.add(0x1000)) {
                emulator.memState.getMemoryBank(addr.addressSpace).let {
                    it.setInitialized(unmappedPage.offset, it.pageSize, true)
                    context.writeMemoryValue(unmappedPage, 0x1000, 0)
                    unmappedPage = unmappedPage.add(0x1000)
                }
            }
            return true
        }

        @Throws(IllegalStateException::class)
        override fun unknownAddress(addr: Address, isWrite: Boolean): Boolean {
            check(!MemoryUtil.addrIsInvalid(addr)) { "Got invalid address access: 0x${addr.offset.toString(16)}" }
            val pageIncluded = addr.getPageStartUnchecked()
            context.createMemoryBlockFromMemoryState(
                "ADDED-${pageIncluded.offset.toString(16)}", pageIncluded, 0x1000,
                true, TaskMonitor.DUMMY)
            context.writeMemoryValue(pageIncluded, 0x1000, 0)
            return true
        }
    }

    @Throws(UnsupportedOperationException::class)
    open fun initialization() {
        context = EmulatorHelper(program)
        emulator = context.emulator as DefaultEmulator
        api = FlatProgramAPI(program)

        context.memoryFaultHandler = mfh
        archSpecifiedInit[program.languageID.toString()] ?.let { it(context) }
        context.writeRegister(context.stackPointerRegister, stackTop.offset)
        emulator.setExecuteAddress(entryPoint.offset)
    }

    open fun startEmulation() {
        try {
            emulator.executeInstruction(true, TaskMonitor.DUMMY)
        } catch (e: Exception) {
            logger?.error("Emulation aborted: ${e.message}")
        }
    }

    open fun finalization() {}

    open fun go() {
        try {
            initialization()
            startEmulation()
            finalization()
        } catch (e: Exception) {
            logger?.error("Emulation error: ${e.message}")
        }
    }

    override fun close() {}

    companion object {
        private var idLock: ReentrantLock = ReentrantLock()
        private var id = 0

        val archSpecifiedInit: Map<String, (EmulatorHelper) -> Unit> = mapOf(
            "ARM:LE:32:Cortex" to { emu ->
                emu.writeRegister("TB", 1)
                // emu.contextRegister = RegisterValue(emu.language.getRegister("TMode"), 1.toBigInteger())
            }
        )
    }
}