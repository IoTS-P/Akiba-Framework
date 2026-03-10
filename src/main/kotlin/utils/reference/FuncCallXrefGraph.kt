package org.iotsplab.akiba.utils.reference

import ghidra.graph.GDirectedGraph
import ghidra.graph.GEdge
import ghidra.graph.GraphFactory
import ghidra.program.model.address.Address
import ghidra.program.model.listing.Function
import ghidra.program.model.listing.Program
import org.iotsplab.akiba.utils.function.allInstructions
import java.io.Closeable
import java.lang.IllegalStateException

class FuncCallXrefGraph (
    private val program: Program
): Closeable {
    // All vertexes are entry points of functions.
    // Functions are not stable and can be changed at any time, to avoid unstability, use addresses instead.
    class FuncCallXrefEdge(private val start: Address, private val end: Address): GEdge<Address> {
        override fun getStart(): Address { return start }
        override fun getEnd(): Address { return end }
    }
    private val innerGraph: GDirectedGraph<Address, FuncCallXrefEdge> = GraphFactory.createDirectedGraph()

    /**
     * getFunctionsOfNoCaller: Get functions which no one calls them.
     *
     * @return A list of functions that has no INs.
     */
    @Throws(ConcurrentModificationException::class)
    fun getFunctionsOfNoCaller(): List<Function> {
        // we don't allow functions being changed while using the graph
        try {
            return innerGraph.vertices.filter { v -> innerGraph.getPredecessors(v).isEmpty() } .map { v ->
                program.listing.getFunctionAt(v)!!
            }
        } catch (_: NullPointerException) {
            throw ConcurrentModificationException("Program function list changed, " +
                    "do not change functions while using FuncCallXrefGraph. " +
                    "Regenerate a graph if you want to update functions.")
        }
    }

    /**
     * getLeafFunctions: Get functions which don't call.
     *
     * @return A list of functions that has no OUTs.
     */
    fun getLeafFunctions(): List<Function> {
        try {
            return innerGraph.vertices.filter { v -> innerGraph.getSuccessors(v).isEmpty() } .map { v ->
                program.listing.getFunctionAt(v)!!
            }
        } catch (_: NullPointerException) {
            throw ConcurrentModificationException("Program function list changed, " +
                    "do not change functions while using FuncCallXrefGraph. " +
                    "Regenerate a graph if you want to update functions.")
        }
    }

    /**
     * rebuild: Generate a graph from a program. It can be used for update and initialization.
     *
     * Will generate a graph of calling relationship between functions.
     * Especially, if a function has no return and its last instruction is a jump instead of a call,
     * it will be treated as a call (only when it is jumped into another function).
     *
     * @param includeRecursive Whether to include recursive calls.
     * @param extraEdges Extra edges to add to the graph. If you found calls that isn't found by Ghidra through
     *                   indirect call analysis, etc., you can add here
     */
    @Throws(ConcurrentModificationException::class)
    fun rebuild(includeRecursive: Boolean, extraEdges: List<FuncCallXrefEdge> = listOf()) {
        val functions = program.listing.getFunctions(true).toList()
        functions.map { it.entryPoint }.forEach { innerGraph.addVertex(it) }
        functions.forEach { f ->
            program.referenceManager.getReferencesTo(f.entryPoint).forEach { ref ->
                if (ReferenceConstants.REFERENCE_CALL.contains(ref.referenceType)) {
                    val fromFunc = program.listing.getFunctionContaining(ref.fromAddress) ?:
                        return@forEach
                    val toFunc = program.listing.getFunctionContaining(ref.toAddress) ?:
                        throw IllegalStateException("Broken xref")
                    // Recursive call not included
                    if (fromFunc != toFunc || includeRecursive)
                        innerGraph.addEdge(FuncCallXrefEdge(fromFunc.entryPoint, ref.toAddress))
                } else if (ReferenceConstants.REFERENCE_JUMP.contains(ref.referenceType)) {
                    val fromFunc = program.listing.getFunctionContaining(ref.fromAddress)
                    if (fromFunc == null ||
                        ref.fromAddress != fromFunc.allInstructions().last().address)
                        return@forEach
                    if (fromFunc == f)
                        return@forEach
                    // If the reference is a jump, only accept those which is the last instruction of a function
                    // and target function is not source function
                    innerGraph.addEdge(FuncCallXrefEdge(fromFunc.entryPoint, ref.toAddress))
                }
            }
        }
        extraEdges.forEach { innerGraph.addEdge(it) }
    }

    /**
     * close: Close the graph and release resources.
     */
    override fun close() {
        innerGraph.removeEdges(innerGraph.edges)
        innerGraph.removeVertices(innerGraph.vertices)
    }

    companion object {
        fun fromProgram(program: Program, includeRecursive: Boolean = false): FuncCallXrefGraph {
            val graph = FuncCallXrefGraph(program)
            graph.rebuild(includeRecursive)
            return graph
        }
    }
}