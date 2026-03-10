package org.iotsplab.akiba.structure

import ghidra.pcode.opbehavior.BinaryOpBehavior
import ghidra.pcode.opbehavior.UnaryOpBehavior
import ghidra.pcode.pcoderaw.PcodeOpRaw
import ghidra.program.model.listing.Program
import ghidra.program.model.pcode.PcodeOp
import ghidra.program.model.pcode.Varnode
import org.iotsplab.akiba.utils.pcode.PcodeConstants.BINARY_OPCODES
import org.iotsplab.akiba.utils.pcode.PcodeConstants.FUNC_BINARY_OPCODES
import org.iotsplab.akiba.utils.pcode.PcodeConstants.FUNC_UNARY_OPCODES
import org.iotsplab.akiba.utils.pcode.PcodeConstants.FUNC_UNARY_OPCODES_WITH_SIZE
import org.iotsplab.akiba.utils.pcode.PcodeConstants.OPCODE_SYMBOLS
import org.iotsplab.akiba.utils.pcode.PcodeConstants.UNARY_OPCODES
import org.iotsplab.akiba.utils.varnode.toPrettyString

class PcodePolynomialNode {
    var parent: PcodePolynomialNode? = null
    var isOp: Boolean = false
    var op: PcodeOp? = null
    var outputSize: Int = 4
    var node: Varnode? = null
    var children: MutableList<PcodePolynomialNode> = mutableListOf()

    constructor(op: PcodeOp, parent: PcodePolynomialNode? = null) {
        require(PcodeOpRaw(op).behavior is BinaryOpBehavior || PcodeOpRaw(op).behavior is UnaryOpBehavior) {
            "Unexpected op ${op.opcode} for polynomial tree"
        }
        this.op = op
        this.parent = parent
        this.outputSize = op.output.size
        isOp = true
    }

    constructor(node: Varnode, parent: PcodePolynomialNode? = null) {
        this.node = node
        this.parent = parent
        this.outputSize = node.size
        isOp = false
    }

    @Throws(IllegalArgumentException::class)
    fun swapChildren(toBeSwapped: PcodePolynomialNode, newChild: PcodePolynomialNode) {
        children.mapIndexed { idx, it -> if (it.hashCode() == toBeSwapped.hashCode()) idx else -1 }
            .firstOrNull { it != -1 }
            ?.let { children[it] = newChild }
            ?: throw IllegalArgumentException("Child not found")
    }

    fun replace(leaf: PcodePolynomialNode, newNode: PcodePolynomialNode) {
        require(!leaf.isOp) { "Only leaf node can be replaced recursively" }
        children.forEachIndexed { idx, node ->
            if (node.isOp)
                node.replace(leaf, newNode)
            else if (node == leaf)
                children[idx] = newNode
        }
    }


    override fun toString(): String {
        return toString(this)
    }

    fun toString(program: Program): String {
        return toString(this, program)
    }

    /**
     * Convert the node and the children below to a pretty string.
     * String format:
     * 1. Each argument may be a register or a unique varnode.
     *    - register representation: R_<reg_name>_<size>
     *    - address representatino: A_<space_name>:<offset>_<size>
     *    - unique varnode representation: U_<offset>_<size>
     * 2. Arguments are connected with symbols like `+`, `-`, etc. Some representation of P-code op:
     *    - `+`/`f+`: INT_ADD, FLOAT_ADD
     *    - `-`/`f-`: INT_SUB, FLOAT_SUB
     *    - `*`/`f*`: INT_MULT, FLOAT_MULT
     *    - `/`/`f/`: INT_DIV, FLOAT_DIV
     *    - `s/`: INT_DIV
     *    - `%`/`s%`: INT_REM, INT_SREM
     *    - `==`/`f==`: INT_EQUAL, FLOAT_EQUAL
     *    - `!=`/`f!=`: INT_NOTEQUAL, FLOAT_NOTEQUAL
     *    - `<`/`f<`: INT_LESS, FLOAT_LESS
     *    - `s<`: INT_SLESS
     *    - `<=`/`f<=`: INT_LESSEQUAL, FLOAT_LESSEQUAL
     *    - `s<=`: INT_SLESSEQUAL
     *    - `-`/`f-`: INT_2COMP, FLOAT_NEG
     *    - `~`/`!`: INT_NEGATE, BOOL_NEGATE
     *    - `^`/`^^`: INT_XOR, BOOL_XOR
     *    - `&`/`&&`: INT_AND, BOOL_AND
     *    - `|`/`||`: INT_OR, BOOL_OR
     *    - `<<`: INT_LEFT
     *    - `>>`: INT_RIGHT
     *    - `s>>`: INT_SRIGHT
     *    - `int2float<[int_size]>(...)`: INT2FLOAT
     *    - `float2float<[float_size]>(...)`: FLOAT2FLOAT
     *    - `trunc<[int_size]>(...)`: FLOAT_TRUNC
     *    - `zext<[new_size]>(...)`/`sext<[new_size]>(...)`: INT_ZEXT, INT_SEXT
     *    - Other op will be represented like functions, e.g. `piece(a, b)`
     *
     * @return The pretty string, a expression.
     */
    @Throws(IllegalStateException::class)
    private fun toString(root: PcodePolynomialNode, program: Program? = null): String {
        if (!root.isOp) {
            check(root.node != null) { "Broken polynomial tree" }
            return root.node!!.toPrettyString(program)
        } else {
            check(root.op != null) { "Broken polynomial tree" }
            return when (root.op!!.opcode) {
                in UNARY_OPCODES -> {
                    check(root.children.size == 1) { "Unary op ${root.op!!} should have 1 child" }
                    "(${OPCODE_SYMBOLS[root.op!!.opcode]!!} ${root.children[0]})"
                }
                in BINARY_OPCODES -> {
                    check(root.children.size == 2) { "Binary op ${root.op!!} should have 2 children" }
                    "(${root.children[0]} ${OPCODE_SYMBOLS[root.op!!.opcode]!!} ${root.children[1]})"
                }
                in FUNC_UNARY_OPCODES -> {
                    check(root.children.size == 1) { "Unary op ${root.op!!} should have 1 child" }
                    "${OPCODE_SYMBOLS[root.op!!.opcode]!!}(${root.children[0]})"
                }
                in FUNC_BINARY_OPCODES -> {
                    check(root.children.size == 2) { "Binary op ${root.op!!} should have 2 children" }
                    "${OPCODE_SYMBOLS[root.op!!.opcode]!!}(${root.children[0]}, ${root.children[1]})"
                }
                in FUNC_UNARY_OPCODES_WITH_SIZE -> {
                    check(root.children.size == 1) { "Unary op ${root.op!!} should have 1 child" }
                    "${OPCODE_SYMBOLS[root.op!!.opcode]!!}(${root.children[0]}, ${root.op!!.output.size})"
                }
                else -> throw IllegalStateException("Unknown op ${root.op!!}")
            }
        }
    }

    @Throws(IllegalStateException::class)
    override fun equals(other: Any?): Boolean {
        return when {
            other == null -> false
            other !is PcodePolynomialNode -> false
            this.isOp != other.isOp -> false
            this.isOp && this.op == null -> throw IllegalArgumentException("Invalid node")
            this.isOp && other.isOp -> this.op == other.op
            // Below: !this.isOp && !other.isOp
            this.node == null -> throw IllegalArgumentException("Invalid node")
            else -> this.node == other.node
        }
    }
}