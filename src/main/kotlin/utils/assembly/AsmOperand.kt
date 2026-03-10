package org.iotsplab.akiba.utils.assembly

import ghidra.program.model.listing.Instruction

abstract class AsmOperand(protected var inst: Instruction, protected var opIdx: Int) {
    // instruction toString like: str r3,[r1,#0x0], there is no space near commas
    @JvmField
    protected var oriObjects: Array<Any> = inst.getOpObjects(opIdx)
    @JvmField
    protected var operandType: Int = inst.getOperandType(opIdx)
    @JvmField
    val opStr: String = getOperandString(inst, opIdx)

    fun getOperandType(): Int {
        return operandType
    }

    fun toPrettyString(): String {
        val ret = String.format("Operand #%d for instruction at %s:", opIdx, inst!!.address.toString())
        return ret
    }

    companion object {
        fun getOperandString(inst: Instruction, index: Int): String {
            val operandsString = inst.toString().split(" ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[1]
            val fakeOps = operandsString.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            var inBracket = false
            val realOps = ArrayList<String>()
            for (op in fakeOps) {
                if (!inBracket) realOps.add(op)
                else realOps[realOps.size - 1] = realOps.last() + "," + op
                if ((op.startsWith("[") && !op.endsWith("]")) || (op.startsWith("{") && op.endsWith("}"))) inBracket =
                    true
                else if (op.endsWith("]") || op.endsWith("}")) inBracket = false
            }
            return realOps[index]
        }
    }
}

