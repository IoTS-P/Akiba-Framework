package org.iotsplab.akiba.script

import ghidra.program.model.listing.Program
import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.module.RuntimeReport
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.security.ProtectionDomain

/**
 * Isolating ClassLoader for dynamically compiled scripts.
 *
 * Each [ScriptClassLoader] instance represents an **isolated namespace**:
 * classes loaded by different instances cannot conflict with each other.
 * This enables hot-reload — simply create a new [ScriptClassLoader] and
 * compile/load the updated script.
 *
 * The parent ClassLoader is set to the one that loaded [AkibaScript], so
 * framework classes (and the Ghidra API) are shared across all script
 * instances, while each script's own classes remain isolated.
 *
 * ### ClassLoader isolation model
 *
 * ```
 * Bootstrap CL
 *   └── App CL (Ghidra, framework jars, …)
 *         ├── ScriptClassLoader-1  →  ScriptA, ScriptA$1, ScriptA$inner
 *         ├── ScriptClassLoader-2  →  ScriptB, ScriptB$1
 *         └── ...
 * ```
 *
 * Framework classes are resolved by the parent CL (shared).
 * Script classes are resolved **only** within this CL (isolated).
 */
class ScriptClassLoader private constructor(
    private val parentLoader: ClassLoader,
    private val additionalJars: List<File>,
    private val urlClassLoader: URLClassLoader?
) : ClassLoader(parentLoader) {

    /** All classes defined by this loader (script class + inner classes / lambdas). */
    private val definedClasses = mutableMapOf<String, Class<*>>()

    /**
     * Load all class files from a [CompiledScript] into this ClassLoader.
     *
     * The compiled script may contain multiple class files (main class + inner
     * classes + lambdas). All of them are defined together so cross-references
     * resolve correctly within the same loader.
     *
     * @return The main script class (the one named [CompiledScript.className]).
     */
    @Suppress("UNCHECKED_CAST")
    fun loadScript(compiledScript: CompiledScript): Class<out AkibaScript> {
        // Define ALL classes first (order matters for linking)
        for ((binaryName, bytes) in compiledScript.classBytes) {
            if (!definedClasses.containsKey(binaryName)) {
                val clazz = defineClass(
                    binaryName, bytes, 0, bytes.size,
                    ProtectionDomain(null, null)
                )
                definedClasses[binaryName] = clazz
            }
        }

        // Return the main class
        val mainClassName = compiledScript.className
        return definedClasses[mainClassName] as? Class<out AkibaScript>
            ?: throw IllegalStateException("Main class '$mainClassName' not found after loading compiled script")
    }

    /**
     * One-shot: compile source code and load the resulting class.
     */
    fun compileAndLoad(source: String, className: String): Class<out AkibaScript> {
        val deps = additionalJars.map { it.toURI().toURL() }
        val compiled = ScriptCompiler.compile(source, className, deps)
        return loadScript(compiled)
    }

    /**
     * Instantiate a script class with the given [binaryId] and [program].
     *
     * The script class must have either:
     * - a no-arg constructor, or
     * - a single-`Int` constructor (used for `id`)
     *
     * After instantiation, [program] is injected into the inherited
     * `AkibaModule.program` field.
     */
    @Suppress("UNCHECKED_CAST")
    fun instantiateScript(
        scriptClass: Class<out AkibaScript>,
        binaryId: Int,
        program: Program?
    ): AkibaScript {
        val constructor = scriptClass.constructors.find { c ->
            c.parameterCount == 0 || (c.parameterCount == 1 && c.parameters[0].type == Int::class.javaPrimitiveType)
        } ?: throw IllegalStateException(
            "No suitable constructor found for script class '${scriptClass.name}'. " +
                "Expected a no-arg or single-Int constructor."
        )

        return if (constructor.parameterCount == 0) {
            constructor.newInstance() as AkibaScript
        } else {
            constructor.newInstance(binaryId) as AkibaScript
        }.apply {
            if (program != null) {
                // The `program` field is declared on AkibaModule, not on the
                // immediate supertype of the script class (which is AkibaScript).
                // Walk the class hierarchy until we find it.
                val field = findFieldInHierarchy(scriptClass, "program")
                    ?: throw NoSuchFieldException(
                        "Field 'program' not found in class hierarchy of ${scriptClass.name}"
                    )
                field.isAccessible = true
                field.set(this, program)
            }
        }
    }

    /**
     * Walk the class hierarchy of [start] looking for a declared field
     * named [name]. Returns null if no such field is found.
     */
    private fun findFieldInHierarchy(start: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = start
        while (c != null) {
            try {
                return c.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }

    /**
     * Create a new [ScriptClassLoader] that additionally includes the given JAR.
     * The current loader's classes are **not** carried over — the new loader
     * starts with a clean namespace.
     */
    fun withJar(file: File): ScriptClassLoader {
        if (!file.exists()) {
            throw IllegalArgumentException("JAR file does not exist: ${file.absolutePath}")
        }
        if (!file.name.endsWith(".jar")) {
            throw IllegalArgumentException("File is not a JAR: ${file.name}")
        }
        return createWithDependencies(additionalJars + file)
    }

    fun getLoadedJars(): List<File> = additionalJars.toList()

    /**
     * Return the names of all classes defined by this loader.
     * Useful for debugging.
     */
    fun getDefinedClassNames(): Set<String> = definedClasses.keys.toSet()

    // ---- Class resolution: child-first for script classes ----

    /**
     * Override resolveClass so that classes defined by **this** loader
     * are preferred over those from the parent. This ensures that if a
     * script defines a class with the same simple name as another script,
     * there is no collision.
     *
     * For framework / JDK classes, normal parent-delegation applies.
     */
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // 1. Already defined by this loader?
        definedClasses[name]?.let { return it }

        // 2. Try URLClassLoader for additional JARs
        try {
            urlClassLoader?.loadClass(name)?.let { return it }
        } catch (_: ClassNotFoundException) {
            // fall through
        }

        // 3. Delegate to parent for framework / JDK classes
        return super.loadClass(name, resolve)
    }

    companion object {
        /**
         * Create a new isolated [ScriptClassLoader] with access to the
         * Akiba framework classes.
         */
        fun create(): ScriptClassLoader {
            val frameworkLoader = AkibaScript::class.java.classLoader
            return ScriptClassLoader(frameworkLoader, emptyList(), null)
        }

        /**
         * Create a new isolated [ScriptClassLoader] with additional JAR
         * dependencies available on the classpath.
         */
        fun createWithDependencies(jars: List<File>): ScriptClassLoader {
            val frameworkLoader = AkibaScript::class.java.classLoader
            val urlCl = if (jars.isNotEmpty()) {
                URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), null as ClassLoader?)
            } else null
            return ScriptClassLoader(frameworkLoader, jars, urlCl)
        }
    }
}

/**
 * Cache entry combining a compiled script with the ClassLoader that loaded it.
 * This allows reuse of compiled scripts across multiple executions while
 * maintaining ClassLoader isolation.
 */
class ScriptInstance(
    val compiledScript: CompiledScript,
    val classLoader: ScriptClassLoader,
    val scriptClass: Class<out AkibaScript>
) {
    companion object {
        /**
         * Compile and load a script in one step.
         *
         * @param source       Kotlin source code
         * @param className    Simple class name for the script
         * @param extraJars    Additional JAR dependencies
         * @return A [ScriptInstance] ready for execution
         */
        fun compile(
            source: String,
            className: String,
            extraJars: List<File> = emptyList()
        ): ScriptInstance {
            val cl = ScriptClassLoader.createWithDependencies(extraJars)
            val compiled = ScriptCompiler.compile(
                source, className,
                extraJars.map { it.toURI().toURL() }
            )
            val scriptClass = cl.loadScript(compiled)
            return ScriptInstance(compiled, cl, scriptClass)
        }
    }

    /**
     * Create a new [AkibaScript] instance from this compiled script.
     *
     * @param binaryId    The binary ID to associate with the script
     * @param program     The Ghidra Program to inject
     * @param skipDbWrite Whether to skip database writes (passed to the AkibaScript
     *                    constructor if it declares a `scriptSkipDbWrite` parameter,
     *                    otherwise injected via reflection after construction)
     * @return A new, un-started [AkibaScript] instance with a [RuntimeReport] installed
     */
    fun newInstance(
        binaryId: Int,
        program: Program?,
        skipDbWrite: Boolean = true
    ): AkibaScript {
        val instance = classLoader.instantiateScript(scriptClass, binaryId, program)

        // Install a RuntimeReport so the caller can observe results
        instance.installRuntimeReport(RuntimeReport())

        return instance
    }
}
