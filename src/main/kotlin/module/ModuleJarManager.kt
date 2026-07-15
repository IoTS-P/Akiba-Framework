package org.iotsplab.akiba.module

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * Manages module jar files stored under the `modules/` directory.
 *
 * Directory layout:
 * ```
 * modules/
 *   ├── PublicModule.jar          ← available to all users
 *   ├── AnotherPublic.jar
 *   └── <username>/
 *       ├── PrivateModule.jar     ← available only to <username>
 *       └── AnotherPrivate.jar
 * ```
 *
 * All operations enforce path-traversal protection: usernames are sanitised
 * via [safeSegment] before being used as directory names.
 */
object ModuleJarManager {
    private val logger: Logger = LogManager.getLogger("ModuleJarManager")

    private const val MAX_JAR_BYTES = 100L * 1024 * 1024 // 100 MiB

    /** Root directory for all module jars. */
    val modulesRoot: Path = Path.of("modules").toAbsolutePath().normalize()

    data class ModuleEntry(
        val mainClassName: String,
        val jarFileName: String,
        /** `null` for public modules; the owning username for private modules. */
        val owner: String?,
        /** Absolute path to the jar file. */
        val jarPath: String,
    )

    // ── Path helpers ──────────────────────────────────────────────

    /** Sanitise a username so it is always safe to use as a single path segment. */
    private fun safeSegment(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .take(64)
            .ifBlank { "default" }

    /** Per-user private module directory: `modules/<username>/`. */
    fun userModulesDir(username: String): Path =
        modulesRoot.resolve(safeSegment(username)).toAbsolutePath().normalize()

    // ── Listing ───────────────────────────────────────────────────

    /**
     * List all modules available to [username]: public modules from
     * the `modules/` root plus private modules from `modules/<username>/`.
     *
     * If a private module has the same simple class name as a public one,
     * the private one takes precedence (user-specific override).
     */
    fun listModules(username: String): List<ModuleEntry> {
        val merged = linkedMapOf<String, ModuleEntry>()

        // Public modules first (lower priority)
        scanJars(modulesRoot)?.forEach { entry ->
            merged.putIfAbsent(entry.mainClassName, entry)
        }

        // Private modules (higher priority — overrides public with same class)
        val userDir = userModulesDir(username)
        if (userDir.exists() && userDir.isDirectory()) {
            scanJars(userDir, owner = username)?.forEach { entry ->
                merged[entry.mainClassName] = entry
            }
        }

        return merged.values.toList()
    }

    /**
     * Scan a directory for `.jar` files and extract their `Main-Class`
     * manifest attribute. Returns an empty list if the directory does
     * not exist or contains no jars.
     */
    private fun scanJars(dir: Path, owner: String? = null): List<ModuleEntry> {
        if (!dir.exists() || !dir.isDirectory()) return emptyList()
        val results = mutableListOf<ModuleEntry>()
        dir.toFile().listFiles { file ->
            file.isFile && file.name.endsWith(".jar")
        }?.forEach { jarFile ->
            try {
                JarFile(jarFile).use { jar ->
                    val mainClass = jar.manifest?.mainAttributes?.getValue("Main-Class")
                    if (mainClass.isNullOrBlank()) {
                        logger.warn("Jar ${jarFile.name} in $dir has no Main-Class attribute, skipped")
                        return@forEach
                    }
                    results.add(ModuleEntry(
                        mainClassName = mainClass,
                        jarFileName = jarFile.name,
                        owner = owner,
                        jarPath = jarFile.toPath().toAbsolutePath().normalize().toString(),
                    ))
                }
            } catch (e: Exception) {
                logger.warn("Failed to read jar ${jarFile.name}: ${e.message}")
            }
        }
        return results
    }

    // ── Upload / install ──────────────────────────────────────────

    /**
     * Validate and install a module jar for [username].
     *
     * Validation steps:
     * 1. File size check
     * 2. Valid jar with a `Main-Class` manifest attribute
     * 3. The main class extends [AkibaModule]
     *
     * The jar is saved to `modules/<username>/<originalFileName>`. If a jar
     * with the same name already exists, it is overwritten.
     *
     * @return The installed [ModuleEntry].
     * @throws IllegalArgumentException If validation fails.
     */
    fun installModuleJar(username: String, jarPath: Path): ModuleEntry {
        if (!jarPath.exists()) {
            throw IllegalArgumentException("Uploaded jar file not found")
        }
        val fileSize = Files.size(jarPath)
        if (fileSize > MAX_JAR_BYTES) {
            throw IllegalArgumentException(
                "Jar file exceeds ${MAX_JAR_BYTES / (1024 * 1024)} MiB (was ${fileSize / (1024 * 1024)} MiB)"
            )
        }

        // Validate jar structure and extract Main-Class
        val mainClassName = validateJarAndGetMainClass(jarPath)

        // Validate that the main class extends AkibaModule
        validateModuleClass(jarPath, mainClassName)

        // Save to modules/<username>/
        val userDir = userModulesDir(username)
        Files.createDirectories(userDir)

        val originalName = jarPath.fileName.toString()
        // Reject path traversal in the filename itself
        if (originalName.contains('/') || originalName.contains('\\') ||
            originalName.contains("..") || originalName.contains('\u0000')) {
            throw IllegalArgumentException("Invalid jar filename: $originalName")
        }
        if (!originalName.endsWith(".jar")) {
            throw IllegalArgumentException("File must be a .jar file")
        }

        val target = userDir.resolve(originalName).toAbsolutePath().normalize()
        // Boundary check: target must be inside userDir
        if (!target.startsWith(userDir)) {
            throw IllegalArgumentException("Invalid target path")
        }

        Files.copy(jarPath, target, StandardCopyOption.REPLACE_EXISTING)
        logger.info("Installed module jar '$mainClassName' for user '$username' at ${target.fileName}")

        // Refresh the module registry so the new jar is discoverable
        refreshModuleRegistry()

        return ModuleEntry(
            mainClassName = mainClassName,
            jarFileName = originalName,
            owner = username,
            jarPath = target.toString(),
        )
    }

    /**
     * Open the jar, read the `Main-Class` manifest attribute, and verify
     * the jar is structurally valid.
     */
    private fun validateJarAndGetMainClass(jarPath: Path): String {
        try {
            JarFile(jarPath.toFile()).use { jar ->
                val manifest = jar.manifest
                    ?: throw IllegalArgumentException("Jar has no manifest")
                val mainClass = manifest.mainAttributes.getValue("Main-Class")
                    ?: throw IllegalArgumentException(
                        "Jar manifest is missing the 'Main-Class' attribute. " +
                        "Ensure the build config sets Main-Class in MANIFEST.MF."
                    )
                if (mainClass.isBlank()) {
                    throw IllegalArgumentException("Jar Main-Class attribute is blank")
                }
                return mainClass.trim()
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Not a valid jar file: ${e.message}", e)
        }
    }

    /**
     * Load the main class from the jar using a temporary [URLClassLoader]
     * and verify it is a subclass of [AkibaModule].
     *
     * If the class cannot be loaded (missing dependencies, etc.) the
     * validation fails with a descriptive error.
     */
    private fun validateModuleClass(jarPath: Path, mainClassName: String) {
        val url = jarPath.toUri().toURL()
        URLClassLoader(arrayOf(url), javaClass.classLoader).use { loader ->
            val clazz = try {
                loader.loadClass(mainClassName)
            } catch (e: ClassNotFoundException) {
                throw IllegalArgumentException(
                    "Main-Class '$mainClassName' not found in the jar", e
                )
            } catch (e: NoClassDefFoundError) {
                throw IllegalArgumentException(
                    "Failed to load '$mainClassName': missing dependency — ${e.message}", e
                )
            }
            if (!AkibaModule::class.java.isAssignableFrom(clazz)) {
                throw IllegalArgumentException(
                    "Main-Class '$mainClassName' does not extend AkibaModule. " +
                    "Module classes must inherit from org.iotsplab.akiba.module.AkibaModule."
                )
            }
            logger.debug("Validated module class: $mainClassName extends AkibaModule")
        }
    }

    // ── Delete ────────────────────────────────────────────────────

    /**
     * Delete a private module jar owned by [username].
     *
     * Only private modules under `modules/<username>/` can be deleted.
     * Public modules (directly in `modules/`) are not deletable via this API.
     *
     * @param jarFileName The jar file name to delete (e.g. `MyModule.jar`).
     * @throws IllegalArgumentException If the file doesn't exist or path traversal is detected.
     */
    fun deleteModule(username: String, jarFileName: String) {
        if (jarFileName.contains('/') || jarFileName.contains('\\') ||
            jarFileName.contains("..") || jarFileName.contains('\u0000') ||
            !jarFileName.endsWith(".jar")) {
            throw IllegalArgumentException("Invalid module file name")
        }

        val userDir = userModulesDir(username)
        val target = userDir.resolve(jarFileName).toAbsolutePath().normalize()
        if (!target.startsWith(userDir)) {
            throw IllegalArgumentException("Invalid path")
        }
        if (!target.exists()) {
            throw IllegalArgumentException("Module '$jarFileName' not found")
        }

        Files.delete(target)
        logger.info("Deleted module jar '$jarFileName' for user '$username'")

        // Refresh the module registry
        refreshModuleRegistry()
    }

    // ── Registry refresh ──────────────────────────────────────────

    /**
     * Re-scan the modules directory and update [ProcedureArgumentsDeserializer.allModules].
     *
     * Called after upload / delete so the module resolver sees the latest
     * set of available jars without requiring a server restart.
     */
    private fun refreshModuleRegistry() {
        try {
            org.iotsplab.akiba.utils.ProcedureArgumentsDeserializer.peekAllModules()
        } catch (e: Exception) {
            logger.warn("Failed to refresh module registry: ${e.message}", e)
        }
    }
}
