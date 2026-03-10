package org.iotsplab.akiba.structure

import ghidra.app.emulator.EmulatorHelper
import ghidra.pcode.opbehavior.BinaryOpBehavior
import ghidra.pcode.opbehavior.UnaryOpBehavior
import ghidra.pcode.pcoderaw.PcodeOpRaw
import ghidra.program.model.listing.Program
import ghidra.program.model.pcode.PcodeOp
import ghidra.program.model.pcode.Varnode
import ghidra.util.exception.NotFoundException
import org.iotsplab.akiba.utils.varnode.toPrettyString

class PcodePolynomialTree(var output: Varnode) {
    var root: PcodePolynomialNode = PcodePolynomialNode(output)
    private val wordSize: Int
        get() = output.size

    @Throws(IllegalArgumentException::class)
    constructor(op: PcodeOp) : this(op.output) {
        require(PcodeOpRaw(op).behavior is BinaryOpBehavior || PcodeOpRaw(op).behavior is UnaryOpBehavior) {
            "Unexpected op ${op.opcode} for polynomial tree"
        }
        when {
            op.opcode == PcodeOp.COPY -> {
                root = PcodePolynomialNode(op.inputs[0])
            }
            PcodeOpRaw(op).behavior is UnaryOpBehavior -> {
                root = PcodePolynomialNode(op)
                root.children.add(PcodePolynomialNode(op.inputs[0], root))
            }
            PcodeOpRaw(op).behavior is BinaryOpBehavior -> {
                root = PcodePolynomialNode(op)
                root.children.add(PcodePolynomialNode(op.inputs[0], root))
                root.children.add(PcodePolynomialNode(op.inputs[1], root))
            }
            else -> throw IllegalArgumentException("Unexpected op ${op.mnemonic} for polynomial tree")
        }
    }

    fun allVarnodeArguments(): List<Varnode> {
        return traverse(root)
    }

    @Throws(IllegalArgumentException::class)
    fun addOp(op: PcodeOp, anotherTree: PcodePolynomialTree? = null) {
        addOp(op, anotherTree?.root)
    }

    /**
     * Add a new p-code to change the output of the tree.
     * e.g. Original tree: c(output) = a + b
     *      After addOp(d = c - p): d(output) = (a + b) - p
     */
    @Throws(IllegalArgumentException::class)
    fun addOp(op: PcodeOp, anotherNode: PcodePolynomialNode? = null) {
        require(PcodeOpRaw(op).behavior is BinaryOpBehavior || PcodeOpRaw(op).behavior is UnaryOpBehavior) {
            "Unexpected op ${op.mnemonic} for polynomial tree"
        }
        when (PcodeOpRaw(op).behavior) {
            is UnaryOpBehavior -> {
                require(anotherNode == null) { "No other tree needed for unary op" }
                require(output == op.inputs[0]) { "New op's input must be the original output for unary op" }
                val opNode = PcodePolynomialNode(op)
                opNode.children.add(root)
                root = opNode
            }
            is BinaryOpBehavior -> {
                require(anotherNode != null) { "Another tree needed to be another input of binary op" }
                val opNode = PcodePolynomialNode(op)
                opNode.children.add(root)
                opNode.children.add(anotherNode)
                root = opNode
            }
        }
        output = op.output
    }

    @Throws(IllegalArgumentException::class)
    fun replace(node: PcodePolynomialNode, newNode: PcodePolynomialNode) {
        root.replace(node, newNode)
    }

    fun replace(node: Varnode, newNode: PcodePolynomialNode) {
        root.replace(PcodePolynomialNode(node), newNode)
    }

    /**
     * Expand the tree with a new p-code, not change the output, but change the arguments
     * e.g. Original tree: c(output) = a + b
     *      After expandOp(b = p + 1): c(output) = a + (p + 1)
     *
     * @param op The p-code to expand. NOTE: The output of `op` must be present in this tree
     */
    @Throws(IllegalArgumentException::class)
    fun expandOp(op: PcodeOp) {
        val outputNodes: List<PcodePolynomialNode> = findVarnode(op.output)
        if (outputNodes.isEmpty())
            throw IllegalArgumentException("Output varnode $op not found in this tree")
        outputNodes.forEach {
            it.parent?.swapChildren(it, PcodePolynomialNode(op)) ?: run {
                // We need to swap the root node
                if(root != it || outputNodes.size != 1)
                    throw RuntimeException("Broken polynomial tree")
                root = PcodePolynomialNode(op)
            }
        }
    }

    fun expandOp(node: Varnode, treeRoot: PcodePolynomialNode) {
        val outputNodes: List<PcodePolynomialNode> = findVarnode(node)
        if (outputNodes.isEmpty())
            throw IllegalArgumentException("Output varnode $node not found in this tree")
        outputNodes.forEach {
            it.parent?.swapChildren(it, treeRoot) ?: run {
                // We need to swap the root node
                if(root != it || outputNodes.size != 1)
                    throw RuntimeException("Broken polynomial tree")
                root = treeRoot
            }
        }
    }

    fun expandOp(tree: PcodePolynomialTree) {
        expandOp(tree.output, tree.root)
    }

    fun findVarnode(varnode: Varnode): List<PcodePolynomialNode> {
        return findVarnode(root, varnode)
    }

    private fun findVarnode(root: PcodePolynomialNode, varnode: Varnode): List<PcodePolynomialNode> {
        if (!root.isOp)
            return if (root.node == varnode) listOf(root) else listOf()
        return root.children.map { findVarnode(it, varnode) }.flatten()
    }

    private fun traverse(root: PcodePolynomialNode): MutableList<Varnode> {
        return if (root.isOp) {
            root.children.map { traverse(it) }.flatten().distinct().toMutableList()
        } else {
            val varnode = root.node!!
            if (!varnode.isConstant)
                mutableListOf(varnode)
            else
                mutableListOf()
        }
    }

    fun calculate(args: Map<Varnode, Long>): Pair<Long, Int> {
        return recursiveCalculate(root, args)
    }

    @Throws(UnsupportedOperationException::class)
    fun calculate(context: EmulatorHelper): Pair<Long, Int> {
        val allNeededVarnodes = allVarnodeArguments()
        allNeededVarnodes.forEach {
            if (!it.isAddress && !it.isRegister)
                throw UnsupportedOperationException("Unsupported varnode type: ${it.address.addressSpace.name}")
        }
        val allNeededData = allNeededVarnodes.associateWith { varnode ->
            when {
                varnode.isAddress || varnode.isRegister ->
                    context.emulator.memState.getValue(varnode)

                else -> throw UnsupportedOperationException(
                    "Unsupported varnode type: ${varnode.address.addressSpace.name}"
                )
            }
        }
        return calculate(allNeededData)
    }

    /**
     * Recursively calculate the polynomial tree.
     * @param node Root node of the tree.
     * @param args Map of varnodes to their values.
     * @return Pair of the calculated value and the output size of the node.
     */
    @Throws(NotFoundException::class, RuntimeException::class)
    private fun recursiveCalculate(node: PcodePolynomialNode, args: Map<Varnode, Long>): Pair<Long, Int> {
        if (!node.isOp)
            return if (node.node!!.isConstant) (node.node!!.offset to node.node!!.size)
                   else node.node!!.let { args[it] } ?.let { it to node.outputSize }
                                                     ?: throw NotFoundException("${node.node} not found in args")
        node.op ?: throw RuntimeException("Broken polynomial tree")
        when (val behavior = PcodeOpRaw(node.op!!).behavior) {
            is UnaryOpBehavior -> {
                val input = recursiveCalculate(node.children[0], args)
                val output = behavior.evaluateUnary(node.outputSize, input.second, input.first)
                return output to node.outputSize
            }
            is BinaryOpBehavior -> {
                val left = recursiveCalculate(node.children[0], args)
                val right = recursiveCalculate(node.children[1], args)
                val output = behavior.evaluateBinary(node.outputSize, left.second, left.first, right.first)
                return output to node.outputSize
            }
            else -> throw RuntimeException("Unexpected operation for op ${node.op}")
        }
    }

    /**
     * Convert the tree to a pretty string.
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
     * @return The pretty string, an expression.
     */
    override fun toString(): String {
        return "${output.toPrettyString()} = $root"
    }

    fun toString(program: Program): String {
        return "${output.toPrettyString(program)} = ${root.toString(program)}"
    }
}