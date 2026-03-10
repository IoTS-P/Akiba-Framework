package org.iotsplab.akiba.utils.memory

import ghidra.program.model.address.Address

data class MemorySection(
    var fileOffset: Long?,
    var size: Long,
    var mapStart: Address,
    var description: String = "",
    var name: String = "",
    var prot: Int = PROT_WRITE or PROT_READ or PROT_EXEC,
) {
    override fun toString(): String {
        return """
            File offset: (${ fileOffset ?. let { "${it.toString(16)}~${(it + size).toString(16)}" } 
                                        ?: run { "Not mapped to any file section" }})
            Size: 0x${size.toString(16)}
            Map to: ${mapStart.addressSpace.name}:${mapStart.offset.toString(16)}~${(mapStart.offset + size).toString(16)}
            name: $name
            Description: $description
        """.trimIndent()
    }

    companion object {
        fun sectionListString(sections: List<MemorySection>): String {
            return if (sections.isEmpty()) "Empty" else sections.sortedBy { it.mapStart } .mapIndexed { idx, s ->
                "Section #$idx:\n$s"
            }.joinToString("\n\n")
        }

        const val PROT_WRITE = 0x1
        const val PROT_READ = 0x2
        const val PROT_EXEC = 0x4
    }
}