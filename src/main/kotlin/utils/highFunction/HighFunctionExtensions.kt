package org.iotsplab.akiba.utils.highFunction

import ghidra.app.decompiler.ClangTokenGroup
import ghidra.app.decompiler.DecompileResults
import ghidra.program.model.address.Address
import ghidra.program.model.listing.Function
import ghidra.program.model.pcode.HighFunction
import ghidra.program.model.pcode.PcodeBlockBasic
import ghidra.util.task.TaskMonitor
import ghidra.util.task.TimeoutTaskMonitor
import java.util.concurrent.TimeUnit

fun HighFunction.getDirectAncestorMap(): Map<PcodeBlockBasic, Set<PcodeBlockBasic>> {
    val result: MutableMap<PcodeBlockBasic, MutableSet<PcodeBlockBasic>> = mutableMapOf()
    basicBlocks.forEach { b ->
        // There could be some blocks that has no out, such as return blocks.
        if (b.trueOut != null)
            result[b.trueOut] ?.add(b) ?: run { result[b.trueOut as PcodeBlockBasic] = mutableSetOf(b) }
        if (b.falseOut != null)
            result[b.falseOut] ?.add(b) ?: run { result[b.falseOut as PcodeBlockBasic] = mutableSetOf(b) }
    }
    return result.mapValues { it.value.toSet() }
}

@Throws(IllegalArgumentException::class)
fun HighFunction.getBlocksReachableTo(target: PcodeBlockBasic): Set<PcodeBlockBasic> {
    val ancestorMap = getDirectAncestorMap()
    var result: MutableSet<PcodeBlockBasic> = mutableSetOf()
    val nextRoundResult: MutableSet<PcodeBlockBasic> = ancestorMap[target] ?.toMutableSet() ?: return setOf()

    while (result.size != nextRoundResult.size) {
        result = nextRoundResult.toMutableSet()
        nextRoundResult.clear()
        result.forEach { nextRoundResult.addAll(ancestorMap[it] ?: setOf()) }
    }

    return result
}

fun HighFunction.getBlockAt(addr: Address): PcodeBlockBasic? {
    // There may have some special basic block that can't even occupy a single instruction
    // E.g. cmovxx in x86 arch, which is a conditional move instruction that will create a mini basic block in this
    // instruction. But this mini basic block doesn't have any impact on other blocks, so we simply ignore it.
    return basicBlocks.firstOrNull { it.start == addr && it.start != it.stop }
}

fun HighFunction.getBlockEndsWith(addr: Address): PcodeBlockBasic? {
    // Same as getBlockAt
    return basicBlocks.firstOrNull { it.stop == addr && it.start != it.stop }
}

fun Function.getDefaultDecompResult(): DecompileResults {
    val decompiler = HighFunctionUtil.getDefaultDecompiler(program)
    val result = decompiler.decompileFunction(this, 10, TimeoutTaskMonitor.timeoutIn(11, TimeUnit.SECONDS))
    decompiler.closeProgram()
    return result
}

fun Function.getCCodeStructure(): ClangTokenGroup {
    return getDefaultDecompResult().cCodeMarkup
}

fun Function.getCCode(): String {
    return getDefaultDecompResult().decompiledFunction.c
}