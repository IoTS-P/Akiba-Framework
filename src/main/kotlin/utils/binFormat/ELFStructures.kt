package org.iotsplab.akiba.utils.binFormat

import ghidra.app.util.bin.BinaryReader
import ghidra.app.util.bin.ByteArrayProvider
import ghidra.app.util.bin.format.elf.ElfHeader
import ghidra.app.util.bin.format.elf.ElfProgramHeader
import ghidra.app.util.bin.format.elf.ElfProgramHeaderConstants
import ghidra.app.util.bin.format.elf.ElfSectionHeader
import ghidra.app.util.bin.format.elf.ElfSectionHeaderConstants
import ghidra.app.util.bin.format.elf.ElfSectionHeaderType
import ghidra.features.base.memsearch.bytesource.AddressableByteSource
import ghidra.features.base.memsearch.bytesource.ProgramByteSource
import ghidra.features.base.memsearch.format.SearchFormat
import ghidra.features.base.memsearch.gui.SearchSettings
import ghidra.features.base.memsearch.matcher.RegExByteMatcher
import ghidra.features.base.memsearch.searcher.MemoryMatch
import ghidra.features.base.memsearch.searcher.MemorySearcher
import ghidra.program.model.address.Address
import ghidra.program.model.listing.Program
import ghidra.util.datastruct.ListAccumulator
import ghidra.util.task.TaskMonitor
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.utils.memory.MemoryUtil
import org.iotsplab.akiba.utils.string.RegexSearcher.Companion.REGEX_MAX_SEARCH_COUNT
import java.nio.charset.StandardCharsets

class ELFStructures (
    private val program: Program,
    private val logger: Logger? = null
) {
    val elfHeader: ElfHeader
    val elfProgramHeaders: List<ElfProgramHeader>
    val elfSectionHeaders: Map<String, ElfSectionHeader>

    init {
        if (!program.memory.blocks.map { it.name }.containsAll(
            listOf("_elfHeader", "_elfProgramHeaders", "_elfSectionHeaders", ".shstrtab")))
            throw IllegalArgumentException("Program doesn't contains an ELF file")

        // Get ELF Header
        val hBlock = program.memory.getBlock("_elfHeader")
        val hbContents = ByteArray(hBlock.size.toInt())
        hBlock.getBytes(hBlock.start, hbContents)
        val elfHeaderBp = ByteArrayProvider(hbContents)
        elfHeader = ElfHeader(elfHeaderBp) { err -> logger?.error(err) }

        // Get ELF Program Headers
        val phBlock = program.memory.getBlock("_elfProgramHeaders")
        val phbContents = ByteArray(ELF_PROGRAM_HEADER_ENTRY_SIZE)
        val programHeaders = mutableListOf<ElfProgramHeader>()

        (0..<phBlock.size / ELF_PROGRAM_HEADER_ENTRY_SIZE) .forEach { idx ->
            phBlock.getBytes(phBlock.start.add(idx * ELF_PROGRAM_HEADER_ENTRY_SIZE), phbContents)
            val elfProgramHeaderBp = ByteArrayProvider(phbContents)
            val phBinaryReader = BinaryReader(elfProgramHeaderBp, elfHeader.isLittleEndian)
            programHeaders.add(ElfProgramHeader(phBinaryReader, elfHeader))
        }
        elfProgramHeaders = programHeaders

        // Get ELF Section Headers
        val shBlock = program.memory.getBlock("_elfSectionHeaders")
        val shbContents = ByteArray(ELF_SECTION_HEADER_ENTRY_SIZE)
        val sectionHeaders = mutableListOf<ElfSectionHeader>()
        val shstrtab = program.memory.getBlock(".shstrtab")
        val shstrtabContents = ByteArray(shBlock.size.toInt())
        shstrtab.getBytes(shstrtab.start, shstrtabContents)
        val sectionHeaderNames = mutableListOf<String>()

        (0..<shBlock.size / ELF_SECTION_HEADER_ENTRY_SIZE) .forEach { idx ->
            shBlock.getBytes(shBlock.start.add(idx * ELF_SECTION_HEADER_ENTRY_SIZE), shbContents)
            val elfSectionHeaderBp = ByteArrayProvider(shbContents)
            val shBinaryReader = BinaryReader(elfSectionHeaderBp, elfHeader.isLittleEndian)
            val sh = ElfSectionHeader(shBinaryReader, elfHeader)
            sectionHeaders.add(sh)
            sectionHeaderNames.add(
                if (sh.type == ElfSectionHeaderType.SHT_NULL.value) ""
                else {
                    val length = (0..<shstrtabContents.size).filter {
                        shstrtabContents[it] == 0.toByte() && it > sh.name}.min() - sh.name
                    String(shstrtabContents, sh.name, length)
                }
            )
        }
        elfSectionHeaders = sectionHeaderNames.zip(sectionHeaders).toMap()
    }

    fun getBelongedProgram(section: ElfSectionHeader): ElfProgramHeader? {
        return elfProgramHeaders.firstOrNull { phdr ->
            phdr.virtualAddress <= section.address && section.address < phdr.virtualAddress + phdr.memorySize
        }
    }

    fun isDebugSection(section: ElfSectionHeader): Boolean {
        return elfProgramHeaders.none { phdr ->
            phdr.virtualAddress <= section.address && section.address < phdr.virtualAddress + phdr.memorySize
        }
    }

    fun getRealSectionPermission(section: ElfSectionHeader): Int {
        val phdrEntry = getBelongedProgram(section)!!
        var perm = phdrEntry.flags and ELF_PHDR_PF_ALL
        // Correction of write perm
        if ((section.flags and ElfSectionHeaderConstants.SHF_WRITE.toLong()) == 0L)
            perm = perm and (ElfProgramHeaderConstants.PF_X or ElfProgramHeaderConstants.PF_R)
        // Correction of exec perm
        if ((section.flags and ElfSectionHeaderConstants.SHF_EXECINSTR.toLong()) == 0L)
            perm = perm and (ElfProgramHeaderConstants.PF_R or ElfProgramHeaderConstants.PF_W)
        return perm
    }

    @Throws(IllegalStateException::class)
    fun getIvtTableStart(): Address {
        val entryPoint = program.addressFactory.defaultAddressSpace.getAddress(elfHeader.e_entry())
        val byteSource: AddressableByteSource = ProgramByteSource(program)

        val target = String(MemoryUtil.numberToBytes(entryPoint.offset).sliceArray(0..3),
            StandardCharsets.ISO_8859_1)
            .replace("\\", "\\\\")
            .replace("(", "\\(").replace(")", "\\)")
            .replace("[", "\\[").replace("]", "\\]")
            .replace("{", "\\{").replace("}", "\\}")
            .replace("+", "\\+")
            .replace("*", "\\*")
            .replace("?", "\\?")
            .replace(".", "\\.")
        val searcher = MemorySearcher(byteSource,
            RegExByteMatcher(
                target, SearchSettings().withSearchFormat(SearchFormat.BINARY)),
            program.memory.allInitializedAddressSet, REGEX_MAX_SEARCH_COUNT)
        val results = ListAccumulator<MemoryMatch>()
        searcher.findAll(results, TaskMonitor.DUMMY)
        val resultList = results.asList().filter {
            it.address.addressSpace == program.addressFactory.defaultAddressSpace }
        when (resultList.size) {
            0 -> throw IllegalStateException("Nowhere found to refer entry point")
            1 -> return resultList[0].address.subtract(4)
            // TODO: How to choose from multiple matches?
            else -> throw IllegalStateException("Multiple reference found to entry point")
        }
    }

    companion object {
        const val ELF_PROGRAM_HEADER_ENTRY_SIZE = 0x20
        const val ELF_SECTION_HEADER_ENTRY_SIZE = 0x28
        const val ELF_PHDR_PF_ALL = 7

        fun getPermissionString(perm: Int): String {
            var ret = ""
            ret += if (perm and ElfProgramHeaderConstants.PF_R != 0) "r" else "-"
            ret += if (perm and ElfProgramHeaderConstants.PF_W != 0) "w" else "-"
            ret += if (perm and ElfProgramHeaderConstants.PF_X != 0) "x" else "-"
            return ret
        }
    }
}
