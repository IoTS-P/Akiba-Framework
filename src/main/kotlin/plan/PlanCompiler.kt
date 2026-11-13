package org.iotsplab.akiba.plan

import kotlinx.coroutines.runBlocking
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.host.StringScriptSource
import kotlin.script.experimental.jvm.dependenciesFromClassContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Compiles and runs a `.kts` plan against a [PlanScope].
 *
 * Compiling is the expensive half, so a compiled script is kept per source hash and
 * re-used when the same plan runs again in this process. The only entry point is
 * [run]: the caller either gets a finished run or a [PlanCompileException] carrying
 * the compiler's own diagnostics.
 */
class PlanCompiler {

    private val host = BasicJvmScriptingHost()
    private val compiled = ConcurrentHashMap<String, CompiledScript>()

    private val compilation = ScriptCompilationConfiguration {
        jvm { dependenciesFromClassContext(PlanScope::class, wholeClasspath = true) }
        defaultImports("org.iotsplab.akiba.plan.*")
        implicitReceivers(PlanScope::class)
    }

    /** Runs [script] with [scope] as the implicit receiver. */
    fun run(script: File, scope: PlanScope) = runBlocking {
        val source = script.readText()
        val unit = compiled[sha256(source)] ?: compile(script.name, source)
            .also { compiled[sha256(source)] = it }

        val evaluated = host.evaluator.invoke(
            unit,
            ScriptEvaluationConfiguration { implicitReceivers(scope) },
        )
        if (evaluated is ResultWithDiagnostics.Failure) {
            throw PlanException(
                "Plan script '${script.name}' stopped: " + evaluated.reports
                    .filter { it.severity >= ScriptDiagnostic.Severity.ERROR }
                    .joinToString("; ") { it.message },
            )
        }
        evaluated
    }

    private suspend fun compile(name: String, source: String): CompiledScript {
        val result = host.compiler.invoke(StringScriptSource(source, name), compilation)
        return when (result) {
            is ResultWithDiagnostics.Success -> result.value
            is ResultWithDiagnostics.Failure -> throw PlanCompileException(
                script = name,
                diagnostics = result.reports
                    .filter { it.severity >= ScriptDiagnostic.Severity.ERROR }
                    .map { report ->
                        val where = report.location?.let { " (line ${it.start.line})" } ?: ""
                        "${report.message}$where"
                    },
            )
            else -> throw PlanCompileException(name, listOf("unknown compiler result: $result"))
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
