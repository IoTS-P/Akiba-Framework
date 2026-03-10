package org.iotsplab.akiba.utils.string

import ghidra.program.model.address.Address
import ghidra.program.model.listing.Program
import org.iotsplab.akiba.utils.abstractFinder.SearchResult

class StringSearchResult(value: String, val address: Address)
    : SearchResult<String>(value), Comparable<StringSearchResult> {
    val size: Int
        get() = value.length

    override fun compareTo(other: StringSearchResult): Int {
        return address.compareTo(other.address)
    }

    override fun equals(other: Any?): Boolean {
        return super.equals(other) && address == (other as StringSearchResult).address
    }

    override fun toString(): String {
        return "$address, size $size: $value"
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + address.hashCode()
        result = 31 * result + size
        return result
    }

    companion object {
        /**
         * This method is used for selecting the primary matches while discarding overlapping matches
         * E.g. There are 3 matches at 0x0~0x10, 0x9~0x15, 0x12~0x20, then the second one will be discarded.
         */
        fun removeOverlapMatches(results: List<StringSearchResult>) : List<StringSearchResult> {
            // Sort the results first
            val sortedResults: MutableList<StringSearchResult> = results.sorted().toMutableList()
            val handledResults: MutableList<StringSearchResult> = mutableListOf()

            sortedResults.forEachIndexed {
                index, result -> run {
                    if (index == 0)
                        return@forEachIndexed
                    val lastUpperBound = sortedResults[index - 1].address.offset + sortedResults[index - 1].size
                    if (lastUpperBound <= result.address.offset)
                        handledResults.add(result)
                }
            }
            return handledResults
        }

        /**
         * This method is used for removing conflict matches.
         * The match result may have conflicts with: codes, other data, etc.
         * Will remove all matches that are not filled with "undefined" data (including null, code has no data)
         */
        fun removeConflictMatches(program: Program, results: List<StringSearchResult>) : List<StringSearchResult> {
            val handledResults: MutableList<StringSearchResult> = mutableListOf()
            results.forEach {
                r -> run {
                    for (off in 0..<r.size) {
                        val addr: Address = r.address.add(off.toLong())
                        if (program.listing.getDataContaining(addr)?.dataType.toString() != "undefined"
                            || program.listing.getDataContaining(addr) == null)
                            return@run
                    }
                    handledResults.add(r)
                }
            }
            return handledResults
        }
    }
}