package org.iotsplab.akiba.managers

import ghidra.program.model.listing.Program
import java.util.UUID
import org.apache.logging.log4j.LogManager

/**
 * Stable identity for a program.
 *
 * The id lives in the program's own metadata, so it survives a rename, a move
 * between folders, a copy into another project and a `.gar` round trip — every
 * operation that changes the domain file's name or location but not the
 * artifact itself. The database keeps a copy for searching; the metadata is
 * what makes the pair recoverable if the two ever disagree.
 */
object ProgramIdentity {

    /** Option key inside [Program.PROGRAM_INFO]. */
    const val OPTION_KEY = "akiba.program.id"

    private val logger = LogManager.getLogger("ProgramIdentity")

    /** The stored id, or null when this program predates the scheme. */
    fun read(program: Program): String? = runCatching {
        program.getOptions(Program.PROGRAM_INFO)
            .getString(OPTION_KEY, null)
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Return the program's id, assigning one on first use.
     *
     * Must be called inside a transaction: writing options is a program change.
     */
    fun ensure(program: Program): String {
        read(program)?.let { return it }
        val id = UUID.randomUUID().toString()
        program.getOptions(Program.PROGRAM_INFO).setString(OPTION_KEY, id)
        logger.info("Assigned program id {} to '{}'", id, program.name)
        return id
    }

    /**
     * Always issue a fresh id, for a copy.
     *
     * A copy is a separate artifact: sharing the id would make two programs
     * indistinguishable to anything that tracks work by program.
     */
    fun reissue(program: Program): String {
        val id = UUID.randomUUID().toString()
        program.getOptions(Program.PROGRAM_INFO).setString(OPTION_KEY, id)
        logger.info("Reissued program id {} for copy '{}'", id, program.name)
        return id
    }
}
