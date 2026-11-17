package managers

import ghidra.program.model.lang.LanguageID
import org.iotsplab.akiba.managers.BinaryMetadata
import ghidra.program.model.listing.Program
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a module is told about its target when the database knows nothing about it.
 *
 * The program itself answers most of the questions, so a run on an un-imported program is
 * not blind — it just has no id, which is what stops anything from writing a row.
 */
class BinaryMetadataTest {

    private fun program(
        name: String = "p.elf",
        language: String = "x86:LE:32:default",
        format: String = "ELF",
        compiler: String = "gcc",
    ): Program = Proxy.newProxyInstance(
        Program::class.java.classLoader,
        arrayOf(Program::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getName" -> name
            "getLanguageID" -> LanguageID(language)
            "getExecutableFormat" -> format
            "getCompiler" -> compiler
            "toString" -> name
            "hashCode" -> name.hashCode()
            "equals" -> false
            else -> null
        }
    } as Program

    @Test
    fun `a program with no row is described by the program itself`() {
        val meta = BinaryMetadata.of(program())

        assertEquals(BinaryMetadata.NOT_REGISTERED, meta.id)
        assertFalse(meta.isRegistered)
        assertEquals("p.elf", meta.originalPath)
        assertEquals("x86:LE:32:default", meta.arch)
        assertEquals("ELF", meta.format)
        assertEquals("gcc", meta.compilerSpec)
        assertEquals("", meta.checksum)
    }

    @Test
    fun `a row wins wherever it has an answer`() {
        val row = BinaryMetadata(
            id = 7,
            originalPath = "/store/7.bin",
            arch = "ARM:LE:32:Cortex",
            format = "Raw Binary",
            compilerSpec = "default",
            checksum = "abc123",
        )

        val meta = BinaryMetadata.of(program(), row)

        assertEquals(7, meta.id)
        assertTrue(meta.isRegistered)
        assertEquals("/store/7.bin", meta.originalPath)
        assertEquals("ARM:LE:32:Cortex", meta.arch)
        assertEquals("abc123", meta.checksum)
    }

    @Test
    fun `a row that is missing a field falls back to the program`() {
        val row = BinaryMetadata(id = 9, originalPath = "/store/9.bin", checksum = "x")

        val meta = BinaryMetadata.of(program(), row)

        assertEquals(9, meta.id)
        assertEquals("ELF", meta.format)
        assertEquals("gcc", meta.compilerSpec)
    }
}
