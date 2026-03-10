package org.iotsplab.akiba.utils.assembly

import ghidra.program.model.listing.Instruction
import ghidra.program.model.listing.InstructionIterator
import ghidra.program.model.listing.Program
import org.iotsplab.akiba.utils.abstractFinder.AdvancedAbstractSearcher
import java.util.function.Predicate

class AsmSearcher(private val program: Program, private val predicate: Predicate<Instruction>)
    : AdvancedAbstractSearcher<Instruction>() {

    @Throws(Exception::class)
    override fun search() : List<Instruction> {
        val instructions: InstructionIterator = program.listing.getInstructions(true)
        return instructions.filter { i -> predicate.test(i) }
    }
}