package org.iotsplab.akiba.utils.assembly

import ghidra.app.emulator.EmulatorHelper
import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.listing.Instruction
import ghidra.program.model.pcode.PcodeOp
import ghidra.program.model.pcode.Varnode
import org.iotsplab.akiba.structure.DataflowManager
import org.iotsplab.akiba.structure.PcodePolynomialTree
import org.iotsplab.akiba.structure.PcodePolynomialTreeset
import org.iotsplab.akiba.utils.pcode.PcodeConstants
import org.iotsplab.akiba.utils.pcode.PcodeUtil

fun Instruction.isValidToEndBasicBlock(): Boolean {
    return this.pcode.any {
        it.opcode in PcodeConstants.JUMP_OPCODES
    }
}

@Throws(IllegalArgumentException::class)
private fun Instruction.getPolynomialTree(dst: Varnode): PcodePolynomialTree {
    val opIdx = pcode.indices.reversed().firstOrNull { pcode[it].output == dst }
        ?: throw IllegalArgumentException("Varnode $dst not found in instruction $mnemonicString")
    return getPolynomialTree(dst, opIdx)
}

@Throws(IllegalArgumentException::class)
fun Instruction.getPolynomialTree(dst: Varnode, opIdx: Int, isNotOutput: Boolean = false): PcodePolynomialTree {
    val tree = if (!isNotOutput && dst == pcode[opIdx].output) PcodePolynomialTree(pcode[opIdx])
    else PcodePolynomialTree(dst)
    while (true) {
        val uniqueVarnodes = tree.allVarnodeArguments().filter { it.isUnique }
        // The loop cannot be dead because unique varnode cannot be rootless
        // (unique varnodes cannot be used as input before it is assigned as the output of a P-code)
        if (uniqueVarnodes.isEmpty())
            break
        uniqueVarnodes.forEach { uv ->
            (0..<opIdx).reversed().first { pcode[it].output == uv }
                .let { idx -> tree.expandOp(getPolynomialTree(uv, idx)) }
        }
    }
    return tree
}

/**
 * Get all memory accesses of this instruction. Mechanism: For each p-code of this instruction, build a list of
 * polynomial tree to express the calculation process of varnodes, then find all load/store instructions and record
 * their memory accesses. Because `LOAD` and `STORE` pcodeop(lower representation) only accept 2 input for address
 * space id and offset, so by analyzing the trees, we can get the calculation process of the address value. Also,
 * varnodes include size so we don't need to handle different size by our own.
 *
 * @param context The emulator context to execute the instruction, to work out the polynomial expressions
 * @param snapshot The snapshot of the memory accesses conditions and registers
 * @return A list of MemoryAccessEntry to represent multiple memory accesses
 *
 * NOTE: Those memory data who are loaded but changed in registers will be discarded.
 */
@Throws(RuntimeException::class, AssertionError::class)
fun Instruction.analyzeMemoryDataFlow(
    context: EmulatorHelper,
    snapshot: DataflowManager = DataflowManager()
) {
    val program = context.program
    val trees = PcodePolynomialTreeset()
    snapshot.willJump = false
    snapshot.jumpTarget = null
    pcode.forEachIndexed { idx, it ->
        when (it.opcode) {
            PcodeOp.COPY -> {
                val (dst, src) = it.output to it.inputs[0]
                if (dst == src)
                    return@forEachIndexed
                when {
                    dst.isRegister && src.isAddress -> {
                        snapshot.registersWithMemoryData[program.getRegister(dst)] = src
                    }
                    dst.isAddress && src.isRegister -> {
                        snapshot.registersWithMemoryData[program.getRegister(src)] ?.let { addrVarnode ->
                            snapshot.dataFlowRecorder.add(addrVarnode to dst)
                        }
                    }
                    else -> trees.addPcode(it)
                }
            }
            PcodeOp.LOAD -> {
                val (src, dst) = PcodeUtil.parseLoad(program, it)
                val addrValue = trees.calculate(context, src)
                val addrVarnode = Varnode(FlatProgramAPI(program).toAddr(addrValue.first), addrValue.second)
                assert(dst.isRegister)
                snapshot.registersWithMemoryData[program.getRegister(dst)] = addrVarnode
            }
            PcodeOp.STORE -> {
                val (src, dst) = PcodeUtil.parseStore(program, it)
                val addrValue = trees.calculate(context, dst)
                val addrVarnode = Varnode(FlatProgramAPI(program).toAddr(addrValue.first), addrValue.second)
                if (src.isRegister)
                    snapshot.registersWithMemoryData[program.getRegister(src)]?.let { srcAddr ->
                        snapshot.dataFlowRecorder.add(srcAddr to addrVarnode)
                    } ?: run {
                        // Record store instructions that store zeros, this is useful for getting uninitialized global var
                        // memory areas
                        if (context.readRegister(program.getRegister(src)) == 0.toBigInteger())
                            snapshot.zeroStoreRecorder.add(addrVarnode)
                    }
                else {
                    val srcAddr = trees.calculate(context, src)
                    if (srcAddr.first == 0L)
                        snapshot.zeroStoreRecorder.add(addrVarnode)
                }
            }
            PcodeOp.CBRANCH -> {
                val conditionValue = trees.calculate(context, it.inputs[1])
                if (conditionValue.first != 0L) {
                    snapshot.willJump = true
                    snapshot.jumpTarget = FlatProgramAPI(program).toAddr(trees.calculate(context, it.inputs[0]).first)
                    return
                }
            }
            else -> {
                if (idx == pcode.indices.last) {
                    if (it.opcode in PcodeConstants.JUMP_OPCODES) {
                        snapshot.willJump = true
                        snapshot.jumpTarget =
                            FlatProgramAPI(program).toAddr(trees.calculate(context, it.inputs[0]).first)
                    }
                    return@forEachIndexed
                }
                else {
                    try { trees.addPcode(it) } catch (_: Exception) {}
                }
            }
        }
    }
}