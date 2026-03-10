package org.iotsplab.akiba.structure

import ghidra.app.emulator.EmulatorHelper
import ghidra.program.model.pcode.PcodeOp
import ghidra.program.model.pcode.Varnode
import ghidra.util.exception.NotFoundException

class PcodePolynomialTreeset {
    private val trees: MutableList<PcodePolynomialTree> = mutableListOf()

    @Throws(IllegalArgumentException::class)
    fun addPcode(op: PcodeOp) {
        op.inputs.forEach { input ->
            if (!input.isConstant && trees.none { it.output == input })
                trees.add(PcodePolynomialTree(input))
        }

        // Find if the tree representing this output varnode exists:
        // If so: remove all its expressions and replace it with this new one
        //      - NOTE: Need to handle the case like `xxx INT_ADD xxx, yyy`, a varnode cannot be both input and output
        // If not: add a new tree
        trees.indices.firstOrNull { idx -> trees[idx].output == op.output }
            ?.let { idx ->
                val newTree = PcodePolynomialTree(op)
                newTree.allVarnodeArguments().mapNotNull { varnode -> trees.find { it.output == varnode } }
                    .forEach { newTree.replace(it.output, it.root) }
                trees[idx] = newTree
            } ?:run {
                val newTree = PcodePolynomialTree(op)
                newTree.allVarnodeArguments().mapNotNull { varnode -> trees.find { it.output == varnode } }
                    .forEach { newTree.replace(it.output, it.root) }
                if (op.opcode == PcodeOp.COPY && !op.inputs[0].isConstant) {
                    newTree.root = trees.find { it.output == op.inputs[0] }?.root ?: run {
                        trees.add(PcodePolynomialTree(op.inputs[0]))
                        trees.last().root
                    }
                }
                trees.add(newTree)
            }
    }

    @Throws(NotFoundException::class)
    fun calculate(context: EmulatorHelper, target: Varnode): Pair<Long, Int> {
        if (target.isAddress || target.isConstant)
            return Pair(target.address.offset, target.size)
        trees.find { it.output == target }?.let { tree -> return tree.calculate(context) }
            ?: throw NotFoundException("Target varnode not found")
    }
}