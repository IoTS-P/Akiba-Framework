package plan

import org.iotsplab.akiba.Main
import kotlin.test.Test
import kotlin.test.assertFailsWith
import picocli.CommandLine
import kotlin.test.assertEquals

/**
 * `--plan` shares an exclusive group with `--restore` and `--import`: a run is either a
 * plan, a restore or an import, never two of them.
 */
class PlanCliArgsTest {

    @Test
    fun `a plan script is accepted on its own`() {
        val commandLine = CommandLine(Main::class.java)
        commandLine.parseArgs("--plan", "/tmp/batch.kts")

        assertEquals("/tmp/batch.kts", Main.planScript)
        assertEquals(null, Main.importConfig)
    }

    @Test
    fun `a plan and an import config cannot be given together`() {
        val commandLine = CommandLine(Main::class.java)

        assertFailsWith<CommandLine.ParameterException> {
            commandLine.parseArgs("--plan", "/tmp/batch.kts", "--import", "/tmp/batch.json")
        }
    }
}
