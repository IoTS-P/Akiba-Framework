package org.iotsplab.akiba.utils.string

import ghidra.program.model.data.StringDataType
import ghidra.program.model.listing.DataIterator
import ghidra.program.model.listing.Program
import org.iotsplab.akiba.utils.memory.MemoryUtil

fun Program.allStrings(): List<StringSearchResult> {
    val ret = mutableListOf<StringSearchResult>()
    val di: DataIterator = listing.getData(true)
    for (data in di) {
        if (data.dataType !is StringDataType)
            continue
        ret.add(
            StringSearchResult(
                MemoryUtil.readProgramBytesToUTF8String(memory, data.address, data.length), data.address
            )
        )
    }
    return ret
}