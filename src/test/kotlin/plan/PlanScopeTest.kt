package plan

import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.module.ModuleRun
import org.iotsplab.akiba.plan.BinaryExistsException
import org.iotsplab.akiba.plan.BinaryHandle
import org.iotsplab.akiba.plan.BinaryQueryException
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
    private val programs: List<ProgramHandle> = listOf(
        ProgramHandle("p-1", "one.elf", arch = "x86"),
        ProgramHandle("p-2", "two.elf", arch = "arm"),
    ),
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

    override fun copyPrograms(programs: List<ProgramHandle>, target: ImportTarget): Int {
        calls += "copy:${target.project.name}:${target.folder}:${programs.size}"
        if (failImport) error("disk full")
        return programs.size
    }

    override fun runModule(programs: List<ProgramHandle>, specFor: (ProgramHandle) -> ModuleSpec): Map<String, ModuleRun> {
        calls += "run:${programs.firstOrNull()?.let { specFor(it).className } ?: "?"}:${programs.size}"
        // Remember what each program was told, so a test can see the per-program settings.
        programs.forEach { p -> specFor(p).config?.let { calls += "cfg:${p.name}:${it["output"]}" } }
        return programs.associate {
            it.id to ModuleRun(
                result = mapOf("seen" to it.name),
                status = AkibaModule.SUCCESS,
                statusName = "SUCCESS",
                durationMs = 1L,
                programId = it.id,
                // Half the programs were imported from the binaries table, half were not.
                binaryId = if (it.id.endsWith("1")) 41L else null,
            )
        }
    }

    override fun saveResult(spec: ModuleSpec, run: ModuleRun): Boolean {
        calls += "save:${spec.className}:${run.binaryId}"
        return (run.binaryId ?: 0) > 0 && run.result != null
    }

    private val binaries = mutableListOf(
        BinaryHandle(id = 1L, path = "/store/1.bin", sizeBytes = 16L, arch = "x86"),
        BinaryHandle(id = 2L, path = "/store/2.bin", sizeBytes = 32L, arch = "arm"),
    )

    override fun findBinaries(filter: String): List<BinaryHandle> {
        calls += "binaries:$filter"
        if (filter.contains("boom")) throw BinaryQueryException(filter, IllegalStateException("bad sql"))
        return binaries
    }

    override fun importBinary(path: String): BinaryHandle {
        calls += "import:$path"
        if (path.contains("dup")) throw BinaryExistsException(path, "abc123")
        return BinaryHandle(id = binaries.size + 1L, path = path)
    }

    override fun findPrograms(filter: String): List<ProgramHandle> {
        calls += "find:$filter"
        if (filter.contains("boom")) error("bad sql")
        return if (filter.isBlank()) programs else programs.filter { it.arch == filter }
    }
}

/**
 * The script is the program: verbs act at call time and report failure as a value, so
 * one bad import does not take the rest of the batch with it.
 */
class PlanScopeTest {

    @Test
    fun `binaries asks the table with the filter it was given`() {
        val runtime = FakeRuntime()
        val result = PlanScope(runtime).binaries("arch = 'arm'").expect()

        assertEquals(2, result.size)
        assertEquals(listOf("binaries:arch = 'arm'"), runtime.calls)
    }

    @Test
    fun `a broken filter is a typed failure, not a crash`() {
        val failure = assertIs<Outcome.Failed>(PlanScope(FakeRuntime()).binaries("boom"))

        assertIs<BinaryQueryException>(failure.error)
    }

    @Test
    fun `importBinary hands back the row it registered`() {
        val runtime = FakeRuntime()
        val handle = PlanScope(runtime).importBinary("/incoming/fw.bin").expect()

        assertEquals("/incoming/fw.bin", handle.path)
        assertEquals(listOf("import:/incoming/fw.bin"), runtime.calls)
    }

    @Test
    fun `a duplicate checksum is a failure the script can inspect`() {
        val failure = assertIs<Outcome.Failed>(PlanScope(FakeRuntime()).importBinary("/incoming/dup.bin"))

        assertEquals("abc123", assertIs<BinaryExistsException>(failure.error).checksum)
    }

    @Test
    fun `a batch stops at the file that failed`() {
        val runtime = FakeRuntime()
        val failure = assertIs<Outcome.Failed>(
            PlanScope(runtime).importBinaries(listOf("/a.bin", "/dup.bin", "/c.bin")),
        )

        assertIs<BinaryExistsException>(failure.error)
        assertEquals(
            listOf("import:/a.bin", "import:/dup.bin"),
            runtime.calls,
            "the file after the failure must not be imported behind the script's back",
        )
    }

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
        assertEquals(2, scope.runAll(progs, ModuleSpec("org.iotsplab.akiba.module.AkibaUtils")).expect().size)
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

    @Test
    fun `programs returns what the runtime matched`() {
        val scope = PlanScope(FakeRuntime())

        val all = scope.programs().expect()
        val arm = scope.programs("arm").expect()

        assertEquals(listOf("one.elf", "two.elf"), all.map { it.name })
        assertEquals(listOf("two.elf"), arm.map { it.name })
    }

    @Test
    fun `a failing query is an outcome, not an exception`() {
        val scope = PlanScope(FakeRuntime())

        val failed = scope.programs("arch = boom").let { it as Outcome.Failed }

        assertTrue(failed.error.message!!.contains("bad sql"))
    }

    @Test
    fun `each program can be told something different`() {
        val runtime = FakeRuntime()
        val scope = PlanScope(runtime)

        val runs = scope.runAll(progs) { p -> ModuleSpec("FirmXRay").withConfig(mapOf("output" to "${p.name}.fxr")) }.expect()

        assertEquals(progs.size, runs.size)
        assertTrue(runtime.calls.any { it.startsWith("run:FirmXRay") })
        progs.forEach { assertTrue(runtime.calls.contains("cfg:${it.name}:${it.name}.fxr"), runtime.calls.toString()) }
    }

    @Test
    fun `a run hands its data back and the script decides what to do with it`() {
        val scope = PlanScope(FakeRuntime())
        val runs = scope.runAll(progs, ModuleSpec("ZeroTrimmer")).expect()

        assertEquals(progs.map { it.id }.toSet(), runs.keys)
        assertTrue(runs.values.all { it.result != null && it.isSuccess })

        // Only the program that came from a binaries row can be stored.
        val stored = runs.getValue(progs.first().id)
        val detached = runs.getValue(progs.last().id)
        assertTrue(scope.saveResult(ModuleSpec("ZeroTrimmer"), stored).expect())
        assertTrue(!scope.saveResult(ModuleSpec("ZeroTrimmer"), detached).expect())
    }
}
