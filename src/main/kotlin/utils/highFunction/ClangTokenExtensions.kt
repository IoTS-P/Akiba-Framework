package org.iotsplab.akiba.utils.highFunction

import ghidra.app.decompiler.ClangNode
import ghidra.app.decompiler.ClangStatement
import ghidra.app.decompiler.ClangTokenGroup
import ghidra.app.decompiler.ClangVariableToken
import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.address.Address
import ghidra.program.model.listing.Function
import ghidra.program.model.listing.Program
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.iotsplab.akiba.utils.memory.tryParseToLong
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.Executors.newFixedThreadPool
import java.util.concurrent.ForkJoinPool

/**
 * ClangTokenGroup.searchRecursive: Search clang token recursively by given predicate.
 *
 * @param stopRecWhileMatched Stop deeper search while matched, i.a. if the parent is matched, will not search its
 *                            children
 * @param predicate           Predicate to match
 * @return                    List of matched tokens
 */
fun ClangNode.searchRecursive(
    stopRecWhileMatched: Boolean = false,
    predicate: (ClangNode) -> Boolean): List<ClangNode> {
    val ret = mutableListOf<ClangNode>()
    if (predicate(this))
        ret.add(this)
    if (!stopRecWhileMatched)
        ret.addAll((0..<numChildren()).flatMap {
            Child(it).searchRecursive(stopRecWhileMatched, predicate) })
    return ret
}

fun ClangTokenGroup.allStatements(): List<ClangStatement> {
    return searchRecursive { it is ClangStatement }.map { it as ClangStatement }
}

fun ClangTokenGroup.allUnmappedDataAddress(program: Program): List<Address> {
    val api = FlatProgramAPI(program)
    return searchRecursive { Regex("_?DAT_[0-9a-f]+").matches(it.toString()) }
        .map { api.toAddr(it.toString().split("_").last()) }
        .filter { !program.memory.contains(it) }
        .distinct().sortedBy { it.offset }
}

/**
 * Program.allUnmappedDataAddress: Get all unmapped data addresses in the program.
 * Note: This function will decompile all functions in the program, so it may take a long time, so that we offer
 *       `threadNumber` parameter to control the number of threads to decompile.
 *
 * @param threadNumber Number of threads to decompile
 * @return Pair of all unmapped addresses and failed functions (those failed in decompilation)
 */
suspend fun Program.allUnmappedDataAddressInC(threadNumber: Int = 1): Pair<List<Address>, List<Function>> {
    val failedFunctions = ConcurrentSkipListSet<Function> { func1: Function, func2: Function ->
        func1.entryPoint.offset.compareTo(func2.entryPoint.offset)
    }
    val addresses = ConcurrentSkipListSet<Address>()
    val dispatcher = ForkJoinPool(threadNumber).asCoroutineDispatcher()
    coroutineScope {
        functionManager.getFunctions(true).map { func -> async(dispatcher) {
            // In some cases, the decompiling process may fail due to bad function definitions,
            // so we need to check if it is null
            addresses.addAll(func.getDefaultDecompResult().cCodeMarkup
                ?.allUnmappedDataAddress(this@allUnmappedDataAddressInC)
                ?: run {
                failedFunctions.add(func)
                listOf()
            })
        } }.awaitAll()
    }
    return addresses.toList() to failedFunctions.toList()
}

fun ClangTokenGroup.allScalars(): List<Long> {
    return searchRecursive {
        it is ClangVariableToken && tryParseToLong(it.toString()) != null
    }.map { tryParseToLong(it.toString())!! }.distinct()
}

/**
 * Program.allScalars: Get all scalars in the program.
 * Note: This function will decompile all functions in the program, so it may take a long time, so that we offer
 *       `threadNumber` parameter to control the number of threads to decompile.
 *
 * @param threadNumber Number of threads to decompile
 * @return Pair of all scalars and failed functions (those failed in decompilation)
 */
suspend fun Program.allScalarsInC(threadNumber: Int = 1): Pair<List<Long>, List<Function>> {
    val failedFunctions = ConcurrentSkipListSet<Function> { func1: Function, func2: Function ->
        func1.entryPoint.offset.compareTo(func2.entryPoint.offset)
    }
    val scalars = ConcurrentSkipListSet<Long>()
    val pool = newFixedThreadPool(threadNumber)
    val dispatcher = pool.asCoroutineDispatcher()
    coroutineScope {
        functionManager.getFunctions(true).map { func -> async(dispatcher) {
            // In some cases, the decompiling process may fail due to bad function definitions,
            // so we need to check if it is null
            scalars.addAll(func.getDefaultDecompResult().cCodeMarkup
                ?.allScalars()
                ?: run {
                failedFunctions.add(func)
                listOf()
            })
        } }.awaitAll()
    }
    dispatcher.close()
    return scalars.toList() to failedFunctions.toList()
}