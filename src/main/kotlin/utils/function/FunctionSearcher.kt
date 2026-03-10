package org.iotsplab.akiba.utils.function

import ghidra.program.model.listing.Function
import ghidra.program.model.listing.FunctionIterator
import ghidra.program.model.listing.Program
import org.iotsplab.akiba.utils.abstractFinder.AdvancedAbstractSearcher
import java.util.function.Predicate

class FunctionSearcher(private val program: Program, private val predicate: Predicate<Function>)
    : AdvancedAbstractSearcher<Function>() {

    @Throws(Exception::class)
    override fun search() : List<Function> {
        val functions: FunctionIterator = program.listing.getFunctions(true)
        return functions.filter { p -> predicate.test(p) }
    }
}