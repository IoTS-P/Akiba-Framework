package org.iotsplab.akiba.utils.memory

import ghidra.features.base.memsearch.format.SearchFormat
import ghidra.features.base.memsearch.gui.SearchSettings
import ghidra.features.base.memsearch.matcher.ByteMatcher
import ghidra.features.base.memsearch.matcher.RegExByteMatcher
import ghidra.features.base.memsearch.searcher.MemoryMatch
import ghidra.program.disassemble.Disassembler
import ghidra.program.model.address.AddressSet
import ghidra.program.model.listing.Program
import ghidra.program.model.mem.Memory
import ghidra.util.task.TaskMonitor
import java.util.function.Predicate
import java.util.regex.Pattern

class RegexPattern(private val memory: Memory, private val pattern: Pattern) : Predicate<MemoryMatch> {
    override fun test(matchTarget: MemoryMatch): Boolean {
        val memContent: String = MemoryUtil.readProgramBytesToUTF8String(memory, matchTarget)
        return pattern.matcher(memContent).matches()
    }

    fun toMatcher(program: Program): ByteMatcher {
        return RegExByteMatcher(pattern.toString(), SearchSettings().withSearchFormat(SearchFormat.REG_EX))
    }

    companion object {
        @JvmStatic
        fun smallStringsPattern(memory: Memory): RegexPattern {
            return RegexPattern(memory, Pattern.compile("[\\x00\t\n\r\\x20-\\x7e]+"))
        }
    }
}

class DisasmAttemptPattern(private val memory: Memory, private val monitor: TaskMonitor) : Predicate<MemoryMatch> {
    override fun test(matchTarget: MemoryMatch): Boolean {
        // We won't use the 'size' element in 'matchTarget'
        val disassembler = Disassembler.getDisassembler(memory.program, monitor, null)
        val targetSet = AddressSet(matchTarget.address)
        disassembler.disassemble(matchTarget.address, targetSet, false)

        return true
    }
}