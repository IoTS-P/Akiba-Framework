package org.iotsplab.akiba.tests.llm

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ghidra.GhidraJarApplicationLayout
import ghidra.app.plugin.processors.sleigh.SleighLanguageProvider
import ghidra.base.project.GhidraProject
import ghidra.framework.Application
import ghidra.framework.HeadlessGhidraApplicationConfiguration
import ghidra.program.model.listing.Program
import org.iotsplab.akiba.llm.tool.RunScriptTool
import org.iotsplab.akiba.managers.ConfigManager
import org.iotsplab.akiba.managers.ProgramManager
import org.iotsplab.akiba.managers.WorkspaceManager
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.utils.Configs
import org.iotsplab.akiba.utils.General
import org.iotsplab.akiba.utils.SqlSource
import org.iotsplab.akiba.utils.WithGhidraProject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Functional tests for `RunScriptTool`.
 *
 * Pipeline:
 *  1. Initialize a headless Ghidra runtime in a scratch directory under `/tmp`.
 *  2. Create a temporary [GhidraProject] and import `dockerfile_needed/import_example.elf`
 *     to obtain a real [Program] instance.
 *  3. Wire just enough Akiba state (`ConfigManager.config`, `WorkspaceManager`'s lateinit
 *     fields) for an [AkibaModule] subclass to be constructible — without spinning up
 *     databases, server connections, or the full workspace.
 *  4. Build a minimal `AkibaModule` "dummy" parent that holds the imported [Program] in
 *     its `currentProgram`, hand it to [RunScriptTool], and exercise the tool.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunScriptToolTest {

    private lateinit var workDir: Path
    private lateinit var project: GhidraProject
    private lateinit var importedProgram: Program
    private lateinit var dummyParent: AkibaModule

    private val mapper = jacksonObjectMapper()

    @BeforeAll
    fun setup() {
        // 1. Scratch workspace under /tmp ----------------------------------------
        workDir = Files.createTempDirectory("akiba-run-script-test-")
        val projectsDir = workDir.resolve("projects").apply { createDirectories() }
        val binariesRoot = workDir.resolve("binaries").apply {
            createDirectories()
            resolve("original").createDirectories()
            resolve("processed").createDirectories()
        }
        val logRoot = workDir.resolve("logs").apply { createDirectories() }

        // 2. Locate the example ELF ---------------------------------------------
        val elfPath = locateExampleElf()
        assertTrue(elfPath.exists(), "import_example.elf not found at $elfPath")

        // 3. Wire ConfigManager / WorkspaceManager just enough that AkibaModule's
        //    init block (which reads mainConf.binariesRoot, WorkspaceManager.logRootDir,
        //    etc.) does not blow up.
        if (!ConfigManager.isConfigInitialized) {
            ConfigManager.config = Configs(
                general = General(
                    binariesRoot = binariesRoot.absolutePathString(),
                    importRoot = workDir.absolutePathString(),
                    autoAnalysisTimeout = 60,
                ),
                withGhidraProject = WithGhidraProject(
                    projectRoot = projectsDir.absolutePathString(),
                    name = "akiba-run-script-test",
                    mode = "new",
                    overwriteProject = true,
                    saveProject = false,
                ),
                sqlSource = SqlSource(),
            )
        }

        // WorkspaceManager has lateinit fields read from AkibaModule's constructor.
        if (!WorkspaceManager.isLogRootDirInitialized) {
            WorkspaceManager.logRootDir = logRoot
        }
        WorkspaceManager.projectName = "akiba-run-script-test"
        WorkspaceManager.binaryPath = binariesRoot.resolve("original")
        WorkspaceManager.processedBinaryPath = binariesRoot.resolve("processed")

        // 4. Initialize headless Ghidra (idempotent) ----------------------------
        if (!Application.isInitialized()) {
            val appConfig = HeadlessGhidraApplicationConfiguration().apply {
                isInitializeLogging = false
            }
            Application.initializeApplication(GhidraJarApplicationLayout(), appConfig)
        }
        WorkspaceManager.languageProvider = SleighLanguageProvider.getSleighLanguageProvider()

        // 5. Create a temporary GhidraProject and import the ELF ----------------
        project = GhidraProject.createProject(
            projectsDir.absolutePathString(),
            "akiba-run-script-test",
            /*temporary=*/ true
        )

        // Inject the project into WorkspaceManager.proj (private). RunScriptTool itself
        // doesn't use WorkspaceManager.project, but other parts of the framework that
        // AkibaModule may touch do.
        runCatching {
            val projField = WorkspaceManager::class.java.getDeclaredField("proj")
            projField.isAccessible = true
            projField.set(WorkspaceManager, project)
        }

        importedProgram = ProgramManager.loadProgram(elfPath, project)
            ?: fail("Ghidra failed to import $elfPath")

        // 6. Construct a dummy AkibaModule that owns the imported Program -------
        dummyParent = DummyParentModule(id = 0, program = importedProgram)
    }

    @AfterAll
    fun tearDown() {
        runCatching { project.close() }
        runCatching {
            workDir.toFile().deleteRecursively()
        }
    }

    /**
     * Locate `dockerfile_needed/import_example.elf` by walking up from the test's
     * working directory until the project root is found.
     */
    private fun locateExampleElf(): Path {
        val rel = "dockerfile_needed/import_example.elf"
        var cur: Path? = Path.of("").toAbsolutePath()
        repeat(6) {
            val candidate = cur?.resolve(rel)
            if (candidate != null && candidate.exists()) return candidate
            cur = cur?.parent
        }
        // Fallback: workspace path used by the dev env
        return Path.of(System.getProperty("user.dir")).resolve(rel).toAbsolutePath()
    }

    // ============================================================
    //  Tests
    // ============================================================

    @Test
    fun testToolMetadata() {
        val tool = RunScriptTool(dummyParent)
        assertEquals("run_script", tool.name)
        assertTrue(tool.description.isNotBlank())
        val paramNames = tool.parameters.map { it.name }.toSet()
        assertTrue("source" in paramNames, "tool must accept 'source' parameter")
        assertTrue("className" in paramNames, "tool must accept 'className' parameter")
        assertTrue("targetId" in paramNames, "tool must accept 'targetId' parameter")
    }

    @Test
    fun testInvokeWithMissingSourceReturnsError() {
        val tool = RunScriptTool(dummyParent)
        val result = tool.safeExecute(emptyMap())
        assertTrue(result.startsWith("Error:"),
            "Missing 'source' should yield an error string. Got: $result")
    }

    @Test
    fun testInvokeWithBlankSourceReturnsError() {
        val tool = RunScriptTool(dummyParent)
        val result = tool.safeExecute(mapOf("source" to "   "))
        assertTrue(result.startsWith("Error:"),
            "Blank source should yield an error string. Got: $result")
    }

    @Test
    fun testInvokeFailsValidationWhenNoClassDefined() {
        val tool = RunScriptTool(dummyParent)
        val result = tool.safeExecute(mapOf("source" to "// just a comment, no class"))
        assertTrue(result.startsWith("Error:"),
            "Source without a class should fail validation. Got: $result")
        assertTrue(result.contains("validation", ignoreCase = true)
            || result.contains("class definition", ignoreCase = true),
            "Error should mention validation. Got: $result")
    }

    @Test
    fun testInvokeRunsHelloWorldScript() {
        val tool = RunScriptTool(dummyParent)
        val src = """
            import org.iotsplab.akiba.script.AkibaScript

            class HelloAkibaScript : AkibaScript() {
                override suspend fun execute() {
                    appendOutput("hello from script")
                }
            }
        """.trimIndent()

        val resultStr = tool.safeExecute(mapOf("source" to src, "className" to "HelloAkibaScript"))
        assertTrue(!resultStr.startsWith("Error"),
            "Hello-world script returned an error: $resultStr")

        val node = mapper.readTree(resultStr)
        assertEquals(true, node["success"]?.asBoolean(),
            "Script should succeed. Result: $resultStr")
        val output = node["output"]?.asText() ?: ""
        assertTrue(output.contains("hello from script"),
            "Output should contain greeting. Got: $output")
        assertTrue((node["totalTimeMs"]?.asLong() ?: -1) >= 0,
            "totalTimeMs should be set. Result: $resultStr")
    }

    @Test
    fun testInvokeReadsCurrentProgram() {
        val tool = RunScriptTool(dummyParent)
        val src = """
            import org.iotsplab.akiba.script.AkibaScript

            class ProgramInfoScript : AkibaScript() {
                override suspend fun execute() {
                    val p = currentProgram
                    if (p == null) {
                        appendOutput("no-program")
                    } else {
                        appendLine("name=" + p.name)
                        appendLine("format=" + p.executableFormat)
                    }
                }
            }
        """.trimIndent()

        val resultStr = tool.safeExecute(mapOf("source" to src, "className" to "ProgramInfoScript"))
        val node = mapper.readTree(resultStr)
        assertEquals(true, node["success"]?.asBoolean(),
            "Script should succeed. Result: $resultStr")
        val output = node["output"]?.asText() ?: ""
        assertTrue(output.contains("name="),
            "Script should observe currentProgram.name. Output: $output")
        assertTrue(!output.contains("no-program"),
            "currentProgram must not be null inside the script. Output: $output")
    }

    @Test
    fun testInvokeWithMissingTargetReturnsErrorJson() {
        // targetId differs from parent.id and parent.getProgram(...) cannot resolve it,
        // so the tool must surface a structured error.
        val tool = RunScriptTool(dummyParent)
        val src = """
            import org.iotsplab.akiba.script.AkibaScript
            class NoopScript : AkibaScript() {
                override suspend fun execute() {}
            }
        """.trimIndent()

        val resultStr = tool.safeExecute(
            mapOf("source" to src, "className" to "NoopScript", "targetId" to 99999)
        )
        assertTrue(resultStr.contains("error", ignoreCase = true) ||
                   resultStr.contains("Program not found", ignoreCase = true),
            "Unknown targetId should produce an error result. Got: $resultStr")
    }

    @Test
    fun testInvokeReportsScriptRuntimeFailure() {
        val tool = RunScriptTool(dummyParent)
        val src = """
            import org.iotsplab.akiba.script.AkibaScript

            class BoomScript : AkibaScript() {
                override suspend fun execute() {
                    throw RuntimeException("intentional boom")
                }
            }
        """.trimIndent()

        val resultStr = tool.safeExecute(mapOf("source" to src, "className" to "BoomScript"))
        // The tool returns either:
        //  - a JSON object with `"success": false` (when the script's coroutine
        //    body throws and AkibaModule.startProcess() catches it), or
        //  - a plain "Error ..." string (when the exception propagates up to
        //    the tool's outer catch). Accept either shape.
        if (resultStr.startsWith("Error")) {
            assertTrue(resultStr.contains("boom", ignoreCase = true),
                "Tool error should mention the exception. Got: $resultStr")
        } else {
            val node = mapper.readTree(resultStr)
            assertEquals(false, node["success"]?.asBoolean(),
                "Failing script must produce success=false. Result: $resultStr")
        }
    }

    /**
     * Smallest possible concrete [AkibaModule] that can stand in as the "parent" for
     * `RunScriptTool`. It carries the imported [Program] so the tool can resolve
     * `parent.currentProgram` and skips all DB writes.
     */
    private class DummyParentModule(id: Int, program: Program) : AkibaModule(
        id = id,
        program = program,
        skipDbWrite = true,
    )
}
