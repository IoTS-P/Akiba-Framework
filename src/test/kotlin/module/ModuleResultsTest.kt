package module

import org.iotsplab.akiba.module.ModuleContractViolation
import org.iotsplab.akiba.module.ModuleResults
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** What a module is allowed to hand back, and where a result that is too big goes. */
class ModuleResultsTest {

    private val columns = mapOf("functions" to "integer", "note" to "text", "payload" to "jsonb")

    @Test
    fun `a key the table does not have is a contract violation`() {
        val e = assertFailsWith<ModuleContractViolation> {
            ModuleResults.check("ZeroTrimmer", mapOf("removed" to 1), columns, hasTable = true)
        }
        assertTrue(e.message!!.contains("removed"), e.message)
        assertTrue(e.message!!.contains("functions"), e.message)
    }

    @Test
    fun `a value the column cannot hold is a contract violation`() {
        val e = assertFailsWith<ModuleContractViolation> {
            ModuleResults.check("ZeroTrimmer", mapOf("functions" to "many"), columns, hasTable = true)
        }
        assertTrue(e.message!!.contains("functions"), e.message)
        assertTrue(e.message!!.contains("integer"), e.message)
    }

    @Test
    fun `a module without a table cannot return a result`() {
        val e = assertFailsWith<ModuleContractViolation> {
            ModuleResults.check("AkibaUtils", mapOf("functions" to 1), emptyMap(), hasTable = false)
        }
        assertTrue(e.message!!.contains("DoNotCreateTable"), e.message)
    }

    @Test
    fun `a matching map passes, null included`() {
        ModuleResults.check("M", mapOf("functions" to 7, "note" to null, "payload" to mapOf("a" to 1)), columns, true)
        ModuleResults.check("M", emptyMap(), columns, true)
    }

    @Test
    fun `a small result is printed inline`() {
        val text = ModuleResults.render("M", mapOf("functions" to 7), createTempDirectory())
        assertEquals("""{"functions":7}""", text)
    }

    @Test
    fun `a large result goes to a file and says where`() {
        val dir = createTempDirectory("module-results")
        val big = "x".repeat(4096)
        val text = ModuleResults.render("M", mapOf("note" to big), dir)

        assertTrue(text.contains("Saved to"), text)
        val file = dir.toFile().listFiles()!!.single().toPath()
        assertTrue(text.contains(file.toString()), text)
        assertTrue(Files.readString(file).contains(big))
    }
}
