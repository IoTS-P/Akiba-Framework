package script

import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.host.StringScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvm.dependenciesFromClassContext
import kotlin.script.experimental.jvm.jvm
import kotlin.test.Test
import kotlin.test.assertEquals

/** Stand-in for the real program handle a plan script will call. */
class ProgramHandle(val name: String, private val recorder: PlanRecorder) {
    fun importTo(target: String) = recorder.imported.add("$name -> $target")
}

/** Stand-in for the plan scope: top-level statements in the script run against this. */
class PlanRecorder {
    val imported = mutableListOf<String>()

    fun filter(sql: String): List<ProgramHandle> {
        assertEquals("WHERE arch = 'x86_64'", sql)
        return listOf(ProgramHandle("fx20_slave", this), ProgramHandle("stm32_h7", this))
    }
}

/**
 * Spike for the task DSL: proves a `.kts` with **top-level statements** compiles and
 * runs against an implicit receiver, which is what
 * `filter(sql).forEach { it.importTo(...) }` needs.
 */
class PlanScriptSpikeTest {

    private val compilation = ScriptCompilationConfiguration {
        jvm { dependenciesFromClassContext(PlanRecorder::class, wholeClasspath = true) }
        defaultImports("script.*")
        implicitReceivers(PlanRecorder::class)
    }

    @Test
    fun `a plan script runs its top level statements against the scope`() {
        val source = """
            val targets = filter("WHERE arch = 'x86_64'")
            targets.forEach { program ->
                if (program.name.startsWith("fx")) program.importTo("target:inbox")
            }
        """.trimIndent()

        val recorder = PlanRecorder()
        val result = BasicJvmScriptingHost().eval(
            StringScriptSource(source, "plan.kts"),
            compilation,
            ScriptEvaluationConfiguration { implicitReceivers(recorder) },
        )

        when (result) {
            is ResultWithDiagnostics.Success -> Unit
            is ResultWithDiagnostics.Failure -> error(
                "plan script failed:\n" + result.reports.joinToString("\n") { it.render() }
            )
        }
        assertEquals(listOf("fx20_slave -> target:inbox"), recorder.imported)
    }
}

private fun ScriptDiagnostic.render(): String =
    "${severity.name}: ${message}${if (location != null) " @ $location" else ""}"
