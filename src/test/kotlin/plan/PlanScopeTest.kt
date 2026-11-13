package plan

import org.iotsplab.akiba.plan.ImportTarget
import org.iotsplab.akiba.plan.ModuleSpec
import org.iotsplab.akiba.plan.Outcome
import org.iotsplab.akiba.plan.PlanJournal
import org.iotsplab.akiba.plan.PlanRuntime
import org.iotsplab.akiba.plan.PlanScope
import org.iotsplab.akiba.plan.ProgramHandle
import org.iotsplab.akiba.plan.ProgramMissingException
import org.iotsplab.akiba.plan.ProjectExistsException
import org.iotsplab.akiba.plan.ProjectRef
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A runtime that records calls instead of touching Ghidra, a database or disk. */
private class FakeRuntime(
    private val existing: MutableSet<String> = mutableSetOf(),
    private val failImport: Boolean = false,
) : PlanRuntime {
    val calls = mutableListOf<String>()

    override fun projectExists(name: String) = name in existing

    override fun createProject(name: String, overwrite: Boolean): ProjectRef {
        calls += "create:$name"
        existing += name
        return ProjectRef(name)
    }

    override fun openProject(name: String, createIfMissing: Boolean): ProjectRef {
        calls += "open:$name"
        if (name !in existing && !createIfMissing) error("missing")
        existing += name
        return ProjectRef(name)
    }

    override fun importPrograms(programIds: List<String>, target: ImportTarget): Int {
        calls += "import:${target.project.name}:${target.folder}:${programIds.size}"
        if (failImport) error("disk full")
        return programIds.size
    }

    override fun runModule(spec: ModuleSpec, programIds: List<String>): Int {
        calls += "run:${spec.className}:${programIds.size}"
        return programIds.size
    }
}

/**
 * The script is the program: verbs act at call time and report failure as a value, so
 * one bad import does not take the rest of the batch with it.
 */
class PlanScopeTest {

    private val progs = listOf(
        ProgramHandle("id-1", "fx20_slave.elf", arch = "x86_64"),
        ProgramHandle("id-2", "stm32_h7.elf", arch = "arm"),
    )

    @Test
    fun `a taken name is a typed failure the script can inspect`() {
        val runtime = FakeRuntime(existing = mutableSetOf("fx20_batch"))
        val scope = PlanScope(runtime)

        val result = scope.createProject("fx20_batch")
        val failure = assertIs<Outcome.Failed>(result)
        assertEquals("fx20_batch", assertIs<ProjectExistsException>(failure.error).project)
        assertTrue(runtime.calls.isEmpty(), "nothing was created")
    }

    @Test
    fun `createOrOpenProject reuses what exists`() {
        val runtime = FakeRuntime(existing = mutableSetOf("fx20_batch"))
        val scope = PlanScope(runtime)

        assertEquals("fx20_batch", scope.createOrOpenProject("fx20_batch").expect().name)
        assertEquals(listOf("open:fx20_batch"), runtime.calls)
    }

    @Test
    fun `an empty import is a failure, not a silent no-op`() {
        val result = PlanScope(FakeRuntime()).importAll(emptyList(), ImportTarget(ProjectRef("p"), "raw"))
        assertIs<ProgramMissingException>(assertIs<Outcome.Failed>(result).error)
    }

    @Test
    fun `a runtime fault becomes a plan failure the script can keep going from`() {
        val runtime = FakeRuntime(failImport = true)
        val scope = PlanScope(runtime)
        val target = ImportTarget(ProjectRef("fx20_batch"), "raw")

        assertIs<Outcome.Failed>(scope.importAll(progs, target))
        // The next verb still runs; that is the point of returning Outcome.
        assertEquals(2, scope.runAll(progs, ModuleSpec("org.iotsplab.akiba.module.AkibaUtils")).expect())
        assertTrue(runtime.calls.contains("run:org.iotsplab.akiba.module.AkibaUtils:2"))
    }

    @Test
    fun `the journal records what actually happened`() {
        val log = File.createTempFile("plan-journal", ".jsonl").also { it.deleteOnExit() }
        val scope = PlanScope(FakeRuntime(failImport = true), PlanJournal.at(log))

        scope.createProject("fx20_batch").expect()
        assertIs<Outcome.Failed>(scope.importAll(progs, ImportTarget(ProjectRef("fx20_batch"), "raw")))

        val lines = log.readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"status\":\"ok\""), lines[0])
        assertTrue(lines[1].contains("\"status\":\"failed\""), lines[1])
        assertTrue(lines[1].contains("disk full"), lines[1])
    }

    @Test
    fun `expect throws the same typed failure`() {
        val scope = PlanScope(FakeRuntime(existing = mutableSetOf("dup")))
        assertFailsWith<ProjectExistsException> { scope.createProject("dup").expect() }
    }
}
