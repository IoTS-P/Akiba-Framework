package org.iotsplab.akiba.managers

import ghidra.program.model.listing.Program
import kotlinx.serialization.Serializable

/**
 * What a module knows about the binary it is working on.
 *
 * The database row is the richer source, but it is not the only one: a program a plan or
 * an agent opened without importing it has no row, and the program can still answer most
 * of these questions itself.
 */
@Serializable
data class BinaryMetadata(
    val id: Int,
    val originalPath: String,
    val processedPath: String? = null,
    val arch: String? = null,           // Binary arch (x86, ARM, MIPS, ...)
    val format: String? = null,         // Binary format (ELF, Microcode, PE, ...)
    val compilerSpec: String? = null,   // Compiler spec (Visual Studio, eabi, ...)
    val checksum: String,               // File checksum
    val processedChecksum: String? = null
) {
    /** True when this describes a program that was never written to the binaries table. */
    val isRegistered: Boolean
        get() = id > 0

    companion object {
        /** The id of a program that has no row to point at. */
        const val NOT_REGISTERED: Int = -1

        /**
         * Describe [program], filling in from [row] whatever the program cannot answer.
         *
         * Passing no row is the honest case for a program that was opened directly: the id
         * is [NOT_REGISTERED], and callers that write to the database must skip the write
         * rather than invent a row.
         */
        fun of(program: Program, row: BinaryMetadata? = null): BinaryMetadata = BinaryMetadata(
            id = row?.id ?: NOT_REGISTERED,
            originalPath = row?.originalPath ?: runCatching { program.domainFile?.pathname }
                .getOrNull() ?: program.name,
            processedPath = row?.processedPath,
            arch = row?.arch ?: runCatching { program.languageID.toString() }.getOrNull(),
            format = row?.format ?: runCatching { program.executableFormat }.getOrNull(),
            compilerSpec = row?.compilerSpec ?: runCatching { program.compiler }.getOrNull(),
            checksum = row?.checksum ?: "",
            processedChecksum = row?.processedChecksum,
        )
    }
}