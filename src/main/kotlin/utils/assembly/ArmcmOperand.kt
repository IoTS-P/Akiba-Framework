package org.iotsplab.akiba.utils.assembly

import ghidra.program.model.address.Address
import ghidra.program.model.listing.Instruction
import ghidra.program.model.mem.MemoryAccessException
import org.iotsplab.akiba.utils.memory.MemoryUtil
import java.util.regex.Pattern

class ArmcmOperand(inst: Instruction, index: Int, private val parent: Instruction? = null)
    : AsmOperand(inst, index) {
    val operandSize = getInstructionOperandSize(inst)

    init {
        // get its ARM-specified operand type
        if (opStr.endsWith("!")) operandType = operandType or REG_SELF_STEP

        if (opStr.startsWith("[")) {
            if (opStr.contains(",")) {
                val innerOperands = opStr.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                val matcher = IMMEDIATE_REGEX.matcher(innerOperands[1])
                operandType = if (matcher.matches()) operandType or REG_BASE_IMM
                else operandType or REG_BASE_REG
            } else operandType = operandType or REG_INDIRECT
        } else if (opStr.startsWith("{")) operandType = operandType or MULTI_REGISTER
    }

    override fun equals(other: Any?): Boolean {
        when (other) {
            is ArmcmOperand -> {
                if (this.operandType != other.operandType) return false
                return oriObjects.contentEquals(other.oriObjects)
            }
            is String -> return this.opStr == other
            else -> return false
        }
    }

    @Throws(IllegalArgumentException::class, UnsupportedOperationException::class,
        MemoryAccessException::class)
    fun dereference(): Long {
        if (!ADDR_IMM_REGEX.matcher(opStr).matches())
            throw UnsupportedOperationException("$opStr is not immediate address")
        val address = oriObjects[0]
        assert(address is Address)
        return MemoryUtil.readSmall(super.inst.program, address as Address, operandSize)
    }

    companion object {
        const val REG_INDIRECT: Int = 0x1000000
        const val REG_BASE_IMM: Int = 0x2000000
        const val REG_BASE_REG: Int = 0x4000000
        const val MULTI_REGISTER: Int = 0x8000000
        const val REG_SELF_STEP: Int = 0x10000000

        // To match #0x1 in "[r0,#0x1]" or 0x1 in "movs r0,#0x1"
        val IMMEDIATE_REGEX: Pattern = Pattern.compile("^#-?(0x)?[0-9a-fA-F]+$")
        // To match {r2-r6,r10}, {r4,r5,r6,r7,r8}
        val MULTI_REG_REGEX: Pattern = Pattern.compile("^\\{(([^-,]-[^-,])|([^-,]),)*([^-,]-[^-,])|([^-,])}$")
        // To match [0x500]
        val ADDR_IMM_REGEX: Pattern = Pattern.compile("^\\[0x[0-9a-fA-F]+]$")
        // To match "[r0,#0x1]"
        val BASE_IMM_REGEX: Pattern = Pattern.compile("^\\[[a-zA-Z][0-9a-zA-Z]+,#-?(0x)?[0-9a-fA-F]+]$")

        fun getMultiRegs(opStr: String): Array<String>? {
            val ret = ArrayList<String>()

            // Check if it is multi reg
            val matcher = MULTI_REG_REGEX.matcher(opStr)
            if (!matcher.matches()) return null

            val ops = opStr.substring(1, opStr.length - 1).split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            for (op in ops) {
                // There are several continuous registers
                if (op.contains("-")) {
                    val startAndEnd = op.split("-".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                    val startId = startAndEnd[0].substring(1).toInt()
                    val endId = startAndEnd[1].substring(1).toInt()
                    val prefix = startAndEnd[0].substring(0, 1)
                    for (i in startId..endId) ret.add(prefix + i)
                } else ret.add(op)
            }

            return ret.toTypedArray<String>()
        }

        fun getInstructionOperandSize(inst: Instruction): Int {
            var size = 4
            if (inst.mnemonicString!! in ArmcmInstConsts.OPERAND_SIZE_1_MNEMONICS)
                size = 1
            else if (inst.mnemonicString!! in ArmcmInstConsts.OPERAND_SIZE_2_MNEMONICS)
                size = 2
            return size
        }
    }
}
