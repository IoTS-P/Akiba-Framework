package org.iotsplab.akiba.utils.string

import ghidra.features.base.memsearch.searcher.MemoryMatch
import ghidra.program.model.listing.Program
import org.iotsplab.akiba.utils.string.StringSearchResult
import org.iotsplab.akiba.utils.memory.MemoryUtil.Companion.readProgramBytesToUTF8String

class AsciiSequenceSearcher(program: Program,
                            minLength: Int = DEFAULT_ASCII_SEARCH_MIN_LENGTH,
                            maxLength: Int = DEFAULT_ASCII_SEARCH_MAX_LENGTH,
                            postMapHandler: (MemoryMatch) -> StringSearchResult? = {
                                r -> stripResult(program, r)
                            })
    : RegexSearcher(program, asciiRegexOfLength(minLength, maxLength), postMapHandler = postMapHandler) {

    companion object {
        @JvmStatic
        fun stripResult(program: Program, r: MemoryMatch): StringSearchResult? {
            var start = r.address
            var end = r.address.add(r.length.toLong())
            var realSize = r.length
            while (start < end) {
                if (program.memory.getByte(start) != 0.toByte())
                    break
                start = start.add(1)
                realSize -= 1
            }

            if (start == end) return null

            while (program.memory.getByte(end.subtract(1)) == 0.toByte()) {
                end = end.subtract(1)
                realSize -= 1
            }

            return StringSearchResult(readProgramBytesToUTF8String(program.memory, start, realSize), start)
        }
    }
}