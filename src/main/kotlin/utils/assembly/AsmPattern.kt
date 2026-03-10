package org.iotsplab.akiba.utils.assembly

import ghidra.program.model.address.Address
import ghidra.program.model.listing.Instruction
import ghidra.program.model.pcode.PcodeOp
import ghidra.program.model.pcode.Varnode
import org.iotsplab.akiba.utils.pcode.PcodeConstants.JUMP_OPCODES
import java.util.function.Predicate
import kotlin.reflect.KClass

class operandIs(private val opIndex: Int, private val targetOp: String) : Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        val armOp = ArmcmOperand(matchTarget, opIndex)
        return armOp.equals(targetOp)
    }
}

class operandTypeIs(private val opIndex: Int, private val opType: Int) : Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        val armOp = ArmcmOperand(matchTarget, opIndex)
        return armOp.getOperandType() == opType
    }
}

class subOperandIs<T: Any>(private val opIndex: Int, private val subIndex: Int = 0, private val type: KClass<T>)
    : Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        return type.isInstance(matchTarget.getOpObjects(opIndex)[subIndex])
    }
}

class mnemonicIs(private val mnemonic: String) : Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        return matchTarget.toString().split(" ")[0].equals(mnemonic, ignoreCase = true)
    }
}

class mnemonicStartsWith(private val mnemonic: String) : Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        return matchTarget.toString().split(" ")[0].startsWith(mnemonic, ignoreCase = true)
    }
}

class mnemonicLike(private val pattern: Regex) : Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        return pattern.matches(matchTarget.toString())
    }
}

object isBranch : Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        val pcs = matchTarget.pcode
        return pcs[pcs.size - 1].toString().contains("BRANCH")
    }
}

object isConditionalBranch : Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        val pcs = matchTarget.pcode
        return pcs[pcs.size - 1].toString().startsWith("CBRANCH")
    }
}

object isCall : Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        val pcs = matchTarget.pcode
        return if (pcs.isEmpty()) false else pcs[pcs.size - 1].opcode in listOf(PcodeOp.CALL, PcodeOp.CALLIND)
    }
}

object isDirectCall : Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        val pcs = matchTarget.pcode
        return if (pcs.isEmpty()) false else pcs[pcs.size - 1].opcode == PcodeOp.CALL &&
                matchTarget.getOpObjects(0)[0] is Address
    }
}

object isReturn : Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        val pcs = matchTarget.pcode
        return pcs[pcs.size - 1].opcode == PcodeOp.RETURN
    }
}

object isLoad: Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        val pcs = matchTarget.pcode
        return pcs.any { pc ->
             when (pc.opcode) {
                 PcodeOp.COPY -> {
                     val input: Varnode = pc.inputs[0]
                     input.isAddress
                 }
                 PcodeOp.LOAD -> true
                 else -> false
             }
        }
    }
}

object isStore: Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        val pcs = matchTarget.pcode
        return pcs.any { pc ->
            when (pc.opcode) {
                PcodeOp.COPY -> {
                    val output: Varnode = pc.output
                    output.isAddress
                }
                PcodeOp.STORE -> true
                else -> false
            }
        }
    }
}

object isMemoryRW : Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        return isLoad.test(matchTarget) || isStore.test(matchTarget)
    }
}

object isGenericJump: Predicate<Instruction> {
    @Throws(Exception::class)
    override fun test(matchTarget: Instruction): Boolean {
        val pcs = matchTarget.pcode
        if (pcs.isEmpty())      // This could happen for instruction like `nop`
            return false
        return JUMP_OPCODES.contains(pcs.last().opcode)
    }
}