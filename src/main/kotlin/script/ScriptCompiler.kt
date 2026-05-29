package org.iotsplab.akiba.script

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URL
import java.nio.file.Files
import kotlin.reflect.KClass

/**
 * Immutable data class representing a successfully compiled script.
 */
data class CompiledScript(
    val className: String,
    val classBytes: Map<String, ByteArray>,
    val warnings: List<String> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CompiledScript) return false
        return className == other.className
    }

    override fun hashCode(): Int = className.hashCode()
}

class CompilationException(message: String) : Exception(message)

/**
 * Kotlin-only script compiler.
 *
 * Compiles Kotlin source code into JVM bytecode using the embedded
 * Kotlin compiler (`kotlin-compiler-embeddable`). The source must
 * define a class that extends [AkibaScript].
 *
 * The resulting [CompiledScript] contains **all** class files produced
 * by the compiler (the main class + any inner classes / lambdas),
 * keyed by their binary class name.
 */
object ScriptCompiler {

    /** Loaded `org.jetbrains.kotlin.cli.jvm.K2JVMCompiler` class, if available. */
    private var compilerClass: Class<*>? = null

    /** Cached `exec(PrintStream, String...)` method from `CLITool`. */
    private var execMethod: java.lang.reflect.Method? = null

    init {
        try {
            loadKotlinCompiler()
        } catch (e: Throwable) {
            System.err.println(
                "Warning: Kotlin compiler not available. Script execution will fail. ${e.message}"
            )
        }
    }

    /**
     * Locate the embedded Kotlin compiler entry point. We look up
     * [K2JVMCompiler][org.jetbrains.kotlin.cli.jvm.K2JVMCompiler]
     * via reflection so the framework still loads even if the optional
     * `kotlin-compiler-embeddable` dependency is missing at runtime.
     *
     * The `exec(PrintStream, String...)` method we want lives on
     * `CLITool` for Kotlin 1.x and on `CLICompiler` for Kotlin 2.x —
     * we probe both, walking up the class hierarchy.
     */
    private fun loadKotlinCompiler() {
        val classLoader = this::class.java.classLoader
        val k2jvm = classLoader.loadClass("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
        val exec = findExecMethod(k2jvm)
            ?: throw NoSuchMethodException(
                "Could not locate exec(PrintStream, String[]) on K2JVMCompiler or its supertypes"
            )
        compilerClass = k2jvm
        execMethod = exec
    }

    /**
     * Walk the class hierarchy looking for a public `exec(PrintStream, String[])`
     * method. The exact declaring class differs between Kotlin compiler versions.
     */
    private fun findExecMethod(start: Class<*>): java.lang.reflect.Method? {
        var c: Class<*>? = start
        while (c != null) {
            try {
                return c.getDeclaredMethod("exec", PrintStream::class.java, Array<String>::class.java)
            } catch (_: NoSuchMethodException) {
                c = c.superclass
            }
        }
        return null
    }

    /** Whether the Kotlin compiler is available. */
    fun isKotlinAvailable(): Boolean = compilerClass != null && execMethod != null

    /**
     * Compile Kotlin source code into a [CompiledScript].
     *
     * @param source           Full Kotlin source text. Must define a class extending [AkibaScript].
     * @param className        Expected simple class name (used for file naming).
     * @param additionalDependencies Extra JAR URLs to include on the compilation classpath.
     * @return A [CompiledScript] containing all emitted class files.
     * @throws CompilationException if compilation fails or the compiler is not available.
     */
    fun compile(
        source: String,
        className: String,
        additionalDependencies: List<URL> = emptyList()
    ): CompiledScript {
        if (!isKotlinAvailable()) {
            throw CompilationException("Kotlin compiler is not available. Cannot compile scripts.")
        }
        return compileKotlin(source, className, additionalDependencies)
    }

    private fun compileKotlin(
        source: String,
        className: String,
        additionalDependencies: List<URL>
    ): CompiledScript {
        val tempDir = Files.createTempDirectory("akiba_script")
        try {
            // Write source file
            val sourceFile = tempDir.resolve("$className.kt")
            Files.writeString(sourceFile, source)

            // Build classpath
            val classPath = getClassPathUrls(additionalDependencies)
            val args = arrayOf(
                "-Xjdk-release=17",
                "-cp", classPath.joinToString(File.pathSeparator),
                "-d", tempDir.toAbsolutePath().toString(),
                sourceFile.toAbsolutePath().toString()
            )

            val (ok, diagnostics) = invokeKotlinCompiler(args)
            if (!ok) {
                throw CompilationException(
                    "Kotlin compilation failed for class '$className'" +
                        if (diagnostics.isNotBlank()) ":\n$diagnostics" else ""
                )
            }

            // Collect ALL class files (main class + inner classes + lambdas etc.)
            val classBytes = mutableMapOf<String, ByteArray>()
            collectClassFiles(tempDir.toFile(), tempDir.toString().length + 1, classBytes)

            if (classBytes.isEmpty()) {
                throw CompilationException(
                    "Kotlin compilation did not produce any class files for '$className'"
                )
            }

            return CompiledScript(className, classBytes)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    /**
     * Recursively collect `.class` files from the output directory.
     * The key is the binary class name (e.g. "MyScript$1" or "MyScript$inner").
     */
    private fun collectClassFiles(dir: File, prefixLen: Int, out: MutableMap<String, ByteArray>) {
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                collectClassFiles(file, prefixLen, out)
            } else if (file.name.endsWith(".class")) {
                val binaryName = file.absolutePath
                    .substring(prefixLen)
                    .removeSuffix(".class")
                    .replace(File.separatorChar, '.')
                out[binaryName] = file.readBytes()
            }
        }
    }

    /**
     * Invoke `K2JVMCompiler#exec(PrintStream, String...)` reflectively.
     * Returns `(success, diagnostics)`. Diagnostics are the captured
     * stdout/stderr of the compiler — only useful for surfacing errors
     * back to the caller when compilation fails.
     */
    private fun invokeKotlinCompiler(args: Array<String>): Pair<Boolean, String> {
        val clazz = compilerClass
            ?: throw CompilationException("Kotlin compiler is not initialized")
        val exec = execMethod
            ?: throw CompilationException("Kotlin compiler entry point is not initialized")

        val diagnostics = ByteArrayOutputStream()
        val out = PrintStream(diagnostics, /*autoFlush=*/ true, Charsets.UTF_8)
        val instance = clazz.getDeclaredConstructor().newInstance()
        exec.isAccessible = true
        val exitCode = exec.invoke(instance, out, args)
        // ExitCode is an enum with `ordinal == 0` for OK.
        val ok = (exitCode as? Enum<*>)?.ordinal == 0
        return ok to diagnostics.toString(Charsets.UTF_8)
    }

    private fun getClassPathUrls(additionalDependencies: List<URL>): List<String> {
        val urls = mutableListOf<String>()
        additionalDependencies.forEach { urls.add(it.path) }
        urls.add(System.getProperty("java.class.path"))
        return urls
    }

    /**
     * Basic validation of script source code.
     * Returns a list of issues (empty if valid).
     */
    fun validate(source: String): List<String> {
        val issues = mutableListOf<String>()
        if (source.isBlank()) {
            issues.add("Source code is empty")
            return issues
        }
        if (!source.contains("class ") && !source.contains("object ")) {
            issues.add("Source must contain a class definition extending AkibaScript")
        }
        return issues
    }
}
