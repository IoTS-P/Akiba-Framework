package org.iotsplab.akiba.utils.string

import ghidra.features.base.memsearch.bytesource.AddressableByteSource
import ghidra.features.base.memsearch.bytesource.ProgramByteSource
import ghidra.features.base.memsearch.format.SearchFormat
import ghidra.features.base.memsearch.gui.SearchSettings
import ghidra.features.base.memsearch.matcher.RegExByteMatcher
import ghidra.features.base.memsearch.searcher.MemoryMatch
import ghidra.features.base.memsearch.searcher.MemorySearcher
import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.listing.Program
import ghidra.util.datastruct.ListAccumulator
import org.iotsplab.akiba.utils.abstractFinder.AdvancedAbstractSearcher
import org.iotsplab.akiba.utils.memory.MemoryUtil.Companion.readProgramBytesToUTF8String
import java.util.regex.Pattern

open class RegexSearcher(protected val program: Program, private val predicate: Pattern,
                 private val clearOverlap: Boolean = true, private val clearConflict: Boolean = true,
                 private val postMapHandler: (MemoryMatch) -> StringSearchResult? = {
                     r ->
                     StringSearchResult(readProgramBytesToUTF8String(program.memory, r), r.address)
                 })
    : AdvancedAbstractSearcher<StringSearchResult>() {

    private val api: FlatProgramAPI = FlatProgramAPI(program)

    @Throws(Exception::class)
    override fun search() : List<StringSearchResult> {
        // We receive Pattern so the user cannot pass an invalid regex string
        val byteSource: AddressableByteSource = ProgramByteSource(program)
        val searcher = MemorySearcher(byteSource, RegExByteMatcher(predicate.toString(),
            SearchSettings().withSearchFormat(SearchFormat.REG_EX)),
            program.memory.allInitializedAddressSet, REGEX_MAX_SEARCH_COUNT)
        val results = ListAccumulator<MemoryMatch>()
        searcher.findAll(results, api.monitor)
        val resultList = results.asList()

        var result = resultList.map { r -> postMapHandler(r) } .requireNoNulls()

        if (clearOverlap)
            result = StringSearchResult.removeOverlapMatches(result)

        if (clearConflict)
            result = StringSearchResult.removeConflictMatches(program, result)

        return result
    }

    companion object {
        const val REGEX_MAX_SEARCH_COUNT = 10000
        const val DEFAULT_ASCII_SEARCH_MIN_LENGTH = 5
        const val DEFAULT_ASCII_SEARCH_MAX_LENGTH = 10000

        @JvmStatic
        val UNIX_COLOR_ASCII_REGEX: Pattern =
            Pattern.compile("\u001b\\[([0-9]+;)*([0-9]+)m([\\x20-\\x7e]|\\t|\\n|\\r|(\u001b\\[([0-9]+;)*([0-9]+)m))*")
        // Ghidra memory search allows \x00, so we can use regex directly to recognize strings
        // default length larger than 5
        @JvmStatic
        val DEFAULT_ASCII_REGEX: Pattern = Pattern.compile("\\x00([\\x20-\\x7e]|\\t|\\n|\\r){5,}\\x00")

        @JvmStatic
        fun asciiRegexOfLength(noShorterThan: Int) : Pattern {
            val re: String = "\\x00([\\x20-\\x7e]|\\t|\\n|\\r){$noShorterThan,}\\x00"
            return Pattern.compile(re)
        }

        @JvmStatic
        fun asciiRegexOfLength(noShorterThan: Int, shorterThan: Int) : Pattern {
            val re: String = "\\x00([\\x20-\\x7e]|\\t|\\n|\\r){$noShorterThan,$shorterThan}\\x00"
            return Pattern.compile(re)
        }
    }
}