package plan

import org.iotsplab.akiba.plan.ImportTarget
import org.iotsplab.akiba.plan.ModuleSpec
import org.iotsplab.akiba.plan.PlanCompileException
import org.iotsplab.akiba.plan.PlanCompiler
import org.iotsplab.akiba.plan.PlanRuntime
import org.iotsplab.akiba.plan.PlanScope
import org.iotsplab.akiba.plan.ProjectRef
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class RecordingRuntime : PlanRuntime {
    val calls = mutableListOf<String>()
    private val projects = mutableSetOf<String>()

    override fun projectExists(name: String) = name in projects

    override fun createProject(name: String, overwrite: Boolean): ProjectRef {
        calls += "create:$name"; projects += name; return ProjectRef(name)
    }

    override fun openProject(name: String, createIfMissing: Boolean): ProjectRef {
        calls += "open:$name"; projects += name; return ProjectRef(name)
    }

    override fun importPrograms(programIds: List<String>, target: ImportTarget): Int {
        calls += "import:${target.project.name}:${target.folder}:${programIds.size}"
        return programIds.size
    }

    override fun runModule(spec: ModuleSpec, programIds: List<String>): Int {
        calls += "run:${spec.className}:${programIds.size}"; return programIds.size
    }
}

/** A `.kts` file must be able to drive the scope with nothing but the DSL in scope. */
class PlanCompilerTest {

    private fun script(text: String): File =
        File.createTempFile("plan", ".kts").apply { writeText(text); deleteOnExit() }

    @Test
    fun `a plan script runs the verbs it calls`() {
        val file = script(
            """
            val batch = createOrOpenProject("fx20_batch").expect()
            val progs = listOf(ProgramHandle("id-1", "fx20_slave.elf"), ProgramHandle("id-2", "stm32_h7.elf"))

            importAll(progs, batch / "raw").onFailure { println("import skipped: " + it.message) }
            runAll(progs, ModuleSpec("org.iotsplab.akiba.module.AkibaUtils")).expect()
            """.trimIndent()
        )
        val runtime = RecordingRuntime()

        PlanCompiler().run(file, PlanScope(runtime))

        assertEquals(listOf("open:fx20_batch", "import:fx20_batch:raw:2", "run:org.iotsplab.akiba.module.AkibaUtils:2"), runtime.calls)
    }

    @Test
    fun `a syntax error comes back as a compile failure naming the script`() {
        val file = script("fun broken( {")

        val error = assertFailsWith<PlanCompileException> {
            PlanCompiler().run(file, PlanScope(RecordingRuntime()))
        }
        assertTrue(error.script.endsWith(".kts"), error.script)
        assertTrue(error.diagnostics.isNotEmpty(), "compiler diagnostics are carried through")
    }

    @Test
    fun `running the same plan twice reuses the compiled script`() {
        val file = script("""createOrOpenProject("batch").expect()""")
        val runtime = RecordingRuntime()
        val compiler = PlanCompiler()

        val first = System.currentTimeMillis()
        compiler.run(file, PlanScope(runtime))
        val cold = System.currentTimeMillis() - first

        val second = System.currentTimeMillis()
        compiler.run(file, PlanScope(runtime))
        val warm = System.currentTimeMillis() - second

        assertEquals(2, runtime.calls.count { it.startsWith("open:") })
        assertTrue(warm <= cold, "warm run ($warm ms) should not be slower than cold ($cold ms)")
    }
}
