package org.iotsplab.akiba.module

import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ghidra.program.model.lang.Language
import ghidra.program.model.listing.Program
import ghidra.util.exception.CancelledException
import ghidra.util.task.TaskMonitorAdapter
import ghidra.util.task.TimeoutTaskMonitor
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.ConsoleAppender
import org.apache.logging.log4j.core.appender.FileAppender
import org.apache.logging.log4j.core.config.LoggerConfig
import org.apache.logging.log4j.core.layout.PatternLayout
import org.iotsplab.akiba.data.database.DatabaseClient
import org.iotsplab.akiba.managers.BinaryMetadata
import org.iotsplab.akiba.managers.ConfigManager
import org.iotsplab.akiba.managers.ConfigManager.KEY_SEPARATOR
import org.iotsplab.akiba.managers.ConfigManager.mainConf
import org.iotsplab.akiba.managers.ConfigManager.parseModuleConfig
import org.iotsplab.akiba.managers.ImportManager
import org.iotsplab.akiba.managers.WorkspaceManager
import org.iotsplab.akiba.utils.*
import org.iotsplab.akiba.utils.CoroutineTaskMonitor
import org.iotsplab.akiba.utils.CoroutineTaskMonitor.Companion.asCoroutineAware
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import kotlin.coroutines.Continuation
import kotlin.coroutines.coroutineContext
import kotlin.io.path.absolutePathString
import kotlin.io.path.notExists
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor
import kotlin.system.exitProcess
import kotlin.system.measureTimeMillis

/**
 * Abstract class for auto-processing tasks, providing basic functionality for loading configurations and logging.
 * It also defines a template for starting processes and handling timeouts.
 *
 * @param configPath Path to the configuration file, optional.
 * @param defaultConfig The default configuration, optional.
 * @param id The unique identifier for the program, required.
 * @param program The Ghidra program, required.
 * @param properties Other properties of the program, optional.
 * @param consoleLogLevel The log level for the console, optional and default INFO.
 * @param fileLogLevel The log level for the file, optional and default INFO.
 * @param tableName Override for the result table name, optional.
 * @param runtimeReport Optional sink that mirrors this module's runtime side-effects
 *                      ([updateData], [updateErr], `startTask` / `finishTask` timestamps and
 *                      total execution time) into an in-memory map. The parent module that
 *                      invoked this one via [callModule] uses it to inspect what the child
 *                      did *without* round-tripping through the database. Top-level modules
 *                      launched by `ProcedureManager` receive `null`, in which case nothing
 *                      is mirrored and behavior is identical to before this parameter was
 *                      added. See [RuntimeReport] for the schema.
 */
abstract class AkibaModule (
    private val configPath: String? = null,
    private val defaultConfig: Any? = null,
    val id: Int = -1,
    protected val program: Program? = null,
    protected val properties: Map<String, String?> = mapOf(),
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = null,
    runtimeReport: RuntimeReport? = null,
) : AutoCloseable {
    /**
     * Internal sink used to mirror this module's runtime side-effects for the parent module
     * that invoked it. Initialized from the constructor parameter; [callModule] also writes
     * it directly via [installRuntimeReport] right after construction so that subclasses
     * which do not (yet) forward `runtimeReport` to their `super(...)` call still benefit
     * from the mirroring.
     */
    private var runtimeReport: RuntimeReport? = runtimeReport
    val logDir: Path = WorkspaceManager.logRootDir.resolve(id.toString())
    var logger: Logger = initLogger(logDir, consoleLogLevel, fileLogLevel)
    private val hasTable: Boolean = this::class.annotations.none { it is DoNotCreateTable }
    internal val hasResultTable: Boolean
        get() = hasTable
    protected var config: Any? = null
    private var loggerConfig: LoggerConfig? = null
    // failureSign is used to skip all latter tasks. If it is true, then all latter tasks will be skipped.
    var failureSign: Int = SUCCESS

    /**
     * Read-only view of this module's [RuntimeReport], if one was supplied at construction
     * time (typically by [callModule]). Returns `null` for top-level modules launched
     * directly by `ProcedureManager`, since they have no parent to report to.
     *
     * Reading this map *before* `startProcess` returns is supported but the contents are
     * still being filled in; the parent should normally consume it only after the
     * [callModule] coroutine call has resumed. See [RuntimeReport] for the schema.
     */
    val runtimeReportView: Map<String, Any?>?
        get() = runtimeReport?.view

    protected val dbTableName: String =
        tableName ?: "${pascalToSnake(this.javaClass.simpleName)}_results"
    protected val allDefinedDbColumns: Map<String, String> = this.javaClass.kotlin.annotations
        .filter{ it is WithTableColumn }
        .map{ it as WithTableColumn }
        .associate{ it.name to it.type }

    val configClass: KClass<*>?
        get() = this::class.findAnnotation<WithConfigClass>() ?.clazz

    val originalFile: File = File("${mainConf.binariesRoot}/original/$id.bin")
    val processedFile: File? = File("${mainConf.binariesRoot}/processed/$id.bin").let {
        if (it.exists()) it else null
    }
    val usingFile: File = processedFile ?: originalFile

    lateinit var taskGlobalMonitor: CoroutineTaskMonitor

    /**
     * Initializes the logger and attempts to load the configuration if a configuration path and class are provided.
     */
    init {
        // Initialize config
        try {
            configClass ?. let {
                config = if (configPath != null && configClass != null) {
                    val confPathAndKey = if (configPath.startsWith(KEY_SEPARATOR.repeat(2))) {
                        ConfigManager.mainConfigFile.path + KEY_SEPARATOR + configPath.substring(2)
                    } else configPath

                    parseModuleConfig(confPathAndKey, getDeserializerMapper(this::class), it.java)
                } else
                    defaultConfig
            }
        } catch (e: Exception) {
            logger.error("Failed to load config file $configPath")
            logger.error(e.message)
            e.printStackTrace()
            exitProcess(1)
        }
    }

    protected suspend fun callTaskAPI(function: KFunction<*>, vararg args: Any?): Any? {
        return if (function.isSuspend)
            coroutineContext[ModuleContext]!!.call(function, *args,
                Continuation<Any?>(coroutineContext[ModuleContext.Key]!!) { result ->
                    result.getOrThrow()
                }
            )
        else
            coroutineContext[ModuleContext.Key]!!.call(function, *args)
    }

    @Throws(NoSuchElementException::class)
    open suspend fun getTaskData(key: String?): Any? {
        return if (key == null)
            coroutineContext[ModuleContext.Key]!!.data
        else
            coroutineContext[ModuleContext.Key]!!.data.getValue(key)
    }

    open suspend fun setTaskData(key: String, value: Any?) {
        coroutineContext[ModuleContext.Key]!!.data[key] = value
    }

    open suspend fun getMetadata(): BinaryMetadata {
        return coroutineContext[ModuleContext.Key]!!.metadata
    }

    /**
     * Override the config object that was loaded during construction.
     *
     * `AkibaModule`'s constructor only knows how to read configs from disk (via [configPath] /
     * [parseModuleConfig]) or from the [defaultConfig] fallback. When a module is invoked
     * dynamically by another module via [callModule], the caller usually wants to pass an
     * in-memory config object directly — without round-tripping through a temp file. This
     * method is the supported entry point for doing that.
     *
     * The new value MUST be either `null` or an instance of the class declared by the module's
     * [WithConfigClass] annotation. If [WithConfigClass] is absent (i.e. the module does not
     * declare a config class), this method is a no-op except for logging.
     *
     * Calling this after the module's `startProcess` has already started has undefined effects
     * and is not supported.
     *
     * @param newConfig The replacement config. Pass `null` to clear the config.
     * @throws IllegalArgumentException If [newConfig] is not assignable to the module's declared
     *                                  config class.
     */
    @Throws(IllegalArgumentException::class)
    open fun replaceConfig(newConfig: Any?) {
        val declared = configClass
        if (declared == null) {
            logger.debug("replaceConfig() ignored: module has no @WithConfigClass")
            return
        }
        if (newConfig != null) {
            require(declared.java.isInstance(newConfig)) {
                "replaceConfig: provided config is of type ${newConfig.javaClass.name}, " +
                "but module ${this.javaClass.simpleName} declares config class " +
                "${declared.qualifiedName}"
            }
        }
        config = newConfig
    }

    /**
     * Install (or replace) the [RuntimeReport] that mirrors this module's runtime
     * side-effects for the parent module.
     *
     * This is called by [callModule] right after constructing the child instance, so that a
     * subclass which does not (yet) thread the `runtimeReport` constructor parameter
     * through to `super(...)` still gets the mirroring for free. It is **not** intended for
     * end-user code; modules should declare a `runtimeReport` constructor parameter and
     * forward it to `super(...)` instead, which is more explicit.
     *
     * Calling this after `startProcess` has already completed is supported but pointless —
     * the side-effects worth mirroring have already happened.
     */
    internal fun installRuntimeReport(report: RuntimeReport?) {
        this.runtimeReport = report
    }

    /**
     * Dynamically invoke another module on the binary currently being analyzed by this module
     * (or on an arbitrary binary id, if [targetId] is supplied).
     *
     * Unlike the static task list in `config.tasks` (which is resolved before any analysis
     * starts), this method allows a running module to schedule another module on demand. The
     * called module shares this module's coroutine context — including the [ModuleContext] that
     * holds the per-binary task data and the registered task APIs — so that data exchange via
     * [setTaskData] / [getTaskData] keeps working as expected.
     *
     * The called module's lifecycle is fully run here: it is constructed, [startProcess] is
     * invoked (subject to [timeout]), and the instance is closed unless it is annotated with
     * [NoNeedToClose]. Its task interfaces are registered into the current [ModuleContext] via
     * [ModuleContext.lookup] just as `ProcedureManager.invokeProcedure` does, so this module can
     * then call its `@TaskInterface` methods through [callTaskAPI] after the call returns.
     *
     * ### Configuration
     *
     * Three mutually-exclusive ways to provide the called module's configuration are supported,
     * listed here in priority order (the first non-null wins; later parameters are ignored):
     *
     *  1. **[config] — in-memory object.**
     *      Pass an already-constructed instance of the called module's `@WithConfigClass`. This
     *      avoids any disk I/O. The object is installed into the new module via [replaceConfig]
     *      *after* construction, replacing whatever the constructor's default-loading path
     *      produced. This is the recommended path for runtime/programmatic invocations.
     *
     *  2. **[configJson] — JSON snippet.**
     *      Pass a raw JSON string. It is parsed using the called module's own
     *      `@WithConfigDeserializer` (via [getDeserializerMapper]) into the declared config
     *      class, then installed via [replaceConfig]. Useful when the parent has a JSON tree on
     *      hand (e.g. forwarded from the user) but does not want to write it to disk first.
     *
     *  3. **[configKey] — disk-file pointer.**
     *      The traditional `<file>@<json-pointer>` form (or `@@<json-pointer>` to refer to a
     *      key in the main config file). This is forwarded to the called module's constructor
     *      and read by it the usual way. Provided for parity with the static config path.
     *
     * If all three are null, the called module is constructed with no `configPath`, falling
     * back to whatever its constructor does for the no-config case (typically `defaultConfig`,
     * i.e. `null`).
     *
     * @param mainClassName Fully-qualified class name of the [AkibaModule] subclass to invoke.
     *                      The corresponding jar must be present under `modules/`.
     * @param config        Optional in-memory config object. Highest priority. See above.
     * @param configJson    Optional JSON string. Second priority. See above.
     * @param configKey     Optional disk-file config-key path. Lowest priority. Same semantics
     *                      as `tasks[*].configKey` in the global config file.
     * @param targetId      Binary id the called module should operate on. Defaults to this
     *                      module's own [id], i.e. the binary currently under analysis.
     * @param timeout       Per-invocation timeout (seconds). 0 disables the runtime timeout.
     * @param consoleLogLevel Console log level for the spawned module.
     * @param fileLogLevel    File log level for the spawned module.
     * @param tableName     Optional override for the result table name.
     * @return The newly created [AkibaModule] instance after it has finished running. Inspect
     *         [AkibaModule.failureSign] to determine whether the call succeeded, and read
     *         [AkibaModule.runtimeReportView] to observe the child's runtime side-effects
     *         (data written via `updateData` / `updateErr`, plus the start / end timestamps
     *         and total execution time) without having to query the database.
     * @throws ClassNotFoundException   If [mainClassName] cannot be resolved as an [AkibaModule].
     * @throws IllegalArgumentException If [config] is of a type incompatible with the called
     *                                  module's declared config class.
     */
    @Throws(ClassNotFoundException::class, IllegalArgumentException::class, Exception::class)
    open suspend fun callModule(
        program: Program?,
        mainClassName: String,
        config: Any? = null,
        configJson: String? = null,
        configKey: String? = null,
        targetId: Int = this.id,
        timeout: Int = DEFAULT_TIMEOUT,
        consoleLogLevel: Level = Level.INFO,
        fileLogLevel: Level = Level.INFO,
        tableName: String? = null,
    ): AkibaModule {
        val moduleCtx = coroutineContext[ModuleContext.Key]
            ?: throw IllegalStateException(
                "callModule must be invoked from within a coroutine carrying a ModuleContext " +
                "(typically from inside startProcess of an AkibaModule)")

        // Resolve the target class. If it is not yet on the classpath, ProcedureArgumentsDeserializer
        // will locate the jar under modules/ and load all its dependencies.
        ProcedureArgumentsDeserializer.resolveModule(mainClassName)
        val mainClass: Class<*> = try {
            Class.forName(mainClassName)
        } catch (_: ClassNotFoundException) {
            ProcedureArgumentsDeserializer.loader.loadClass(mainClassName)
        }
        require(AkibaModule::class.java.isAssignableFrom(mainClass)) {
            "$mainClassName is not a subclass of AkibaModule"
        }

        @Suppress("UNCHECKED_CAST")
        val mainClassKClass = mainClass.kotlin as KClass<out AkibaModule>

        // Resolve in-memory config: object > JSON > disk-key.
        // We pre-validate `config` here (before constructing the instance) so a clear error
        // is raised on the parent's stack rather than after a successful but no-op construction.
        val resolvedInMemoryConfig: Any? = when {
            config != null -> {
                val declared = mainClassKClass.findAnnotation<WithConfigClass>()?.clazz
                require(declared != null) {
                    "Cannot pass `config` to $mainClassName: it has no @WithConfigClass"
                }
                require(declared.java.isInstance(config)) {
                    "config is of type ${config.javaClass.name}, but $mainClassName declares " +
                    "config class ${declared.qualifiedName}"
                }
                config
            }
            configJson != null -> {
                val declared = mainClassKClass.findAnnotation<WithConfigClass>()?.clazz
                require(declared != null) {
                    "Cannot pass `configJson` to $mainClassName: it has no @WithConfigClass"
                }
                getDeserializerMapper(mainClassKClass).readValue(configJson, declared.java)
            }
            else -> null
        }

        // Only forward `configKey` to the constructor when no in-memory config was provided.
        // Otherwise the constructor's disk-loading branch would run uselessly (and could fail
        // on a stale path) before we override the result via replaceConfig().
        val ctorConfigPath = if (resolvedInMemoryConfig == null) configKey else null

        // Create a fresh runtime report for the child. We pass it both via the constructor
        // arg map (in case the subclass forwards a `runtimeReport` parameter to super()) and
        // via installRuntimeReport() after construction (so the mirroring works even when the
        // subclass does not declare such a parameter — which is the common case).
        val childReport = RuntimeReport()

        val args = hashMapOf(
            "configPath" to ctorConfigPath,
            "id" to targetId,
            // Reuse the program loaded for this module if the call targets the same binary,
            // otherwise pass null and let the called module decide.
            "program" to program,
            "consoleLogLevel" to consoleLogLevel,
            "fileLogLevel" to fileLogLevel,
            "tableName" to tableName,
            "runtimeReport" to childReport,
        )

        // Construct the instance the same way ProcedureManager does, but capture it here so we
        // can return it to the caller.
        val constructor = mainClass.kotlin.primaryConstructor
            ?: throw IllegalStateException("$mainClassName has no primary constructor")
        val instance = constructor.call(
            *constructor.parameters.map { args[it.name] }.toTypedArray()
        ) as AkibaModule

        // Apply in-memory config override now that the instance exists.
        if (resolvedInMemoryConfig != null)
            instance.replaceConfig(resolvedInMemoryConfig)

        instance.installRuntimeReport(childReport)
        if (instance.hasResultTable) {
            try {
                DatabaseClient.createModuleTable(instance.dbTableName, instance.allDefinedDbColumns)
            } catch (e: DatabaseClient.DatabaseDaemonException) {
                if (e.statusCode == HttpStatusCode.Conflict)
                    logger.debug("callModule: child table ${instance.dbTableName} already exists")
                else throw e
            }
            try {
                DatabaseClient.tableLock(instance.dbTableName)
            } catch (e: DatabaseClient.DatabaseDaemonException) {
                if (e.statusCode == HttpStatusCode.Conflict)
                    logger.debug("callModule: child table ${instance.dbTableName} already locked")
                else throw e
            }
        }

        kotlinx.coroutines.withContext(coroutineContext + ModuleLogContext(instance.logger)) {
            instance.startProcess(timeout)
        }

        // Register the called module's @TaskInterface methods into the current context so that
        // after this call returns, the parent module can callTaskAPI() into the spawned one.
        moduleCtx.lookup(instance)

        if (instance.javaClass.annotations.none { it is NoNeedToClose })
            instance.close()

        if (instance.failureSign == FAILED || instance.failureSign == RUNTIME_ERROR) {
            logger.warn("callModule(${mainClassName}) finished with failureSign=" +
                "${instance.failureSign}")
        }

        return instance
    }

    /**
     * Import a new binary file at runtime and register it in the database.
     *
     * This is the runtime counterpart of the static import config consumed by `ImportManager`.
     * It is intended for modules that discover or generate additional binaries while analyzing
     * another file (e.g. unpacked payloads, embedded firmware blobs, decrypted images). The new
     * binary is recorded with provenance information — `source_id` set to this module's [id] and
     * `source_module` set to this module's simple class name — so the imported file can be
     * traced back to the analysis run that produced it.
     *
     * The file is copied to `<binariesRoot>/original/<newId>.bin` (and, if preprocessing is
     * applied, to `<binariesRoot>/processed/<newId>.bin`) just like a top-level import. The
     * caller may continue analyzing the new binary by passing the returned id to [callModule].
     *
     * @param path Absolute path to the file to import.
     * @param arch Optional language hint. If null, Ghidra format auto-detection is attempted
     *             first, then language guessing falls back if that fails.
     * @return The id assigned to the newly registered binary. The returned value is suitable
     *         for use as [callModule]'s `targetId` parameter.
     * @throws IllegalArgumentException If [path] does not point to an existing regular file.
     * @throws ImportManager.DuplicateChecksumException If a binary with the same MD5 checksum
     *         is already registered (the file is *not* re-imported in this case).
     */
    @Throws(
        IllegalArgumentException::class,
        ImportManager.DuplicateChecksumException::class,
    )
    open fun importFile(path: Path, arch: Language? = null): Int {
        val newId = ImportManager.importSingleFile(
            originalPath = path,
            arch = arch,
            sourceId = if (this.id >= 0) this.id else null,
            sourceModule = this.javaClass.simpleName,
        )
        logger.info("Imported $path as binary id=$newId " +
            "(sourceId=${this.id}, sourceModule=${this.javaClass.simpleName})")
        // The database column is INTEGER (int4); cast is safe.
        return newId.toInt()
    }

    fun initLogger(logDir: Path, consoleLogLevel: Level = Level.INFO,
                             fileLogLevel: Level = Level.WARN): Logger {
        if (logDir.notExists())
            Files.createDirectories(logDir)

        val context = LoggerContext.getContext()
        val rootConfig = context.configuration.rootLogger
        val consoleConfig = rootConfig.appenders["Console"]!!
        val patternLayout = consoleConfig.layout
        val loggerFileName = this.javaClass.simpleName
        val loggerName = "$loggerFileName--$id"

        // Create logger config
        val newLoggerConfig = LoggerConfig.createLogger(
            false,
            if (consoleLogLevel > fileLogLevel) { consoleLogLevel } else { fileLogLevel },
            UUID.randomUUID().toString(),
            "true", arrayOf(), null,
            context.configuration, null
        )

        // Create console appender
        val individualConsoleAppender = ConsoleAppender.newBuilder()
            .setLayout(patternLayout)
            .setName("Console-${this.javaClass.simpleName}-$id")
            .build()
        individualConsoleAppender.start()
        newLoggerConfig.addAppender(individualConsoleAppender, consoleLogLevel, null)

        loggerConfig = newLoggerConfig

        // Create file appender
        if (fileLogLevel != Level.OFF) {
            val logFile = logDir.resolve("$loggerFileName.log")
            if (!Files.exists(logFile))
                Files.createFile(logFile)
            val fileAppender: FileAppender = FileAppender.newBuilder()
                .setName("File-${this.javaClass.simpleName}-$id")
                .withFileName(logFile.absolutePathString())
                .setLayout(
                    PatternLayout.newBuilder()
                        .withPattern("%d %-5level [%t] %c{1.}.%M(%L): %msg%n")
                        .build()
                )
                .build()
            fileAppender.start()
            loggerConfig!!.addAppender(fileAppender, fileLogLevel, null)
        }

        context.configuration.addLogger(loggerName, loggerConfig)
        return LogManager.getLogger(loggerName)
    }

    @Throws(Exception::class)
    protected fun extractFileInJar(inPath: String, outPath: Path) {
        val moduleJarPath = ProcedureArgumentsDeserializer.allModules[this::class.qualifiedName]!!
        JarFile(moduleJarPath.toFile()).use {
            val entry = it.getEntry(inPath)
                ?: throw IllegalStateException("$inPath not found in $moduleJarPath")
            it.getInputStream(entry).use { stream ->
                val out = FileOutputStream(outPath.toFile())
                stream.copyTo(out)
                logger.debug("Copied {} to {}", inPath, outPath)
                out.close()
            }
        }
    }

    /**
     * Starts the process with an optional timeout. If a timeout is specified, it will run in a separate thread.
     *
     * @param timeout The timeout for the process in seconds. If 0, there is no timeout.
     * @throws Exception
     */
    @Throws(Exception::class)
    open suspend fun startProcess(timeout: Int = 0) {
        // Record the canonical "start" timestamp into the runtime report, regardless of
        // whether the module owns a database table — the report is an independent mirror,
        // and the time at which startProcess() was entered is meaningful in either case.
        runtimeReport?.recordStart(Instant.now())

        if (hasTable)
            DatabaseClient.startTask(dbTableName, id.toLong())

        val job = CoroutineScope(coroutineContext).launch(start = CoroutineStart.LAZY) {
            this@AkibaModule.startProcess()
        }

        // TimeoutTaskMonitor will start the timer on constructing
        taskGlobalMonitor =
            if (this.javaClass.annotations.none { it is IgnoreRuntimeTimeout } && timeout > 0)
                TimeoutTaskMonitor.timeoutIn(timeout.toLong(), TimeUnit.SECONDS)
                    .asCoroutineAware(coroutineContext.job)
            else
                TaskMonitorAdapter(true).asCoroutineAware(job)

        val executionTime = measureTimeMillis {
            try {
                job.start()
                job.join()
            } catch (_: CancelledException) {
                logger.warn("Process cancelled")
            } catch (e: Exception) {
                logger.error("Process failed: ${e.message}")
                e.printStackTrace()
                failureSign = FAILED
                if (hasTable)
                    updateErr("Process failed: ${e.message}(${e.javaClass.simpleName})")
            } catch (_: OutOfMemoryError) {
                logger.error("Process out of memory")
                failureSign = RUNTIME_ERROR
                if (hasTable)
                    updateErr("Process out of memory")
            } catch (e: Error) {
                logger.error("Process error: ${e.message}")
                failureSign = RUNTIME_ERROR
                if (hasTable)
                    updateErr("Process error: ${e.message}(${e.javaClass.simpleName})")
            }
        }

        logger.debug("execution time: $executionTime ms")
        runtimeReport?.recordExecutionTime(executionTime)

        if (taskGlobalMonitor.isCancelled) {        // Timeout occurred
            if (this.javaClass.annotations.any { it is FailOnCancelled })
                failureSign = FAILED
        } else {
            try {
                if (job.isActive)
                    taskGlobalMonitor.cancel()
            } catch (_: Exception) {}
        }

        runtimeReport?.recordEnd(Instant.now())

        if (hasTable)
            DatabaseClient.finishTask(dbTableName, id.toLong())

        if (failureSign == SUCCESS)
            clearErr()
        close()
    }

    /**
     * Abstract method to start the process without a timeout. To be implemented by subclasses.
     * @throws Exception
     */
    @Throws(Exception::class)
    open suspend fun startProcess() {}

    /**
     * Handles timeout situations, to be implemented by subclasses.
     * @throws Exception
     */
    @Throws(Exception::class)
    open suspend fun timeoutHandler() {}

    @Throws(DatabaseClient.DatabaseDaemonException::class)
    protected open fun updateData(data: Map<String, Any?>) {
        // Mirror into the runtime report first so the parent observes the data even when
        // this module has DoNotCreateTable (no DB write happens then).
        runtimeReport?.recordUpdateData(data)
        if (hasTable)
            DatabaseClient.updateData(dbTableName, id.toLong(), data)
        else
            logger.error("Update data not allowed in modules with 'DoNotCreateTable', " +
                "if you want to save data, remove this annotation")
    }

    @Throws(DatabaseClient.DatabaseDaemonException::class)
    protected open fun updateErr(msg: String) {
        runtimeReport?.recordErr(msg)
        if (hasTable)
            DatabaseClient.updateData(dbTableName, id.toLong(), mapOf("err_msg" to msg))
    }

    @Throws(DatabaseClient.DatabaseDaemonException::class)
    protected open fun clearErr() {
        runtimeReport?.recordErr(null)
        if (hasTable)
            DatabaseClient.updateData(dbTableName, id.toLong(), mapOf("err_msg" to null))
    }

    var logLevel: Level
        get() = logger.level
        set(logLevel) {
            (LogManager.getContext() as LoggerContext).configuration.getLoggerConfig(javaClass.name).level = logLevel
        }

    override fun close() {
//        loggerConfig ?. let { lc ->
//            LoggerContext.getContext().loggers.removeIf { it.name == logger.name }
//            lc.appenders.values.forEach { it.stop() }
//            lc.appenders.clear()
//            LoggerContext.getContext().configuration.removeLogger(logger.name)
//            lc.stop()
//        }
    }

    companion object {
        const val DEFAULT_TIMEOUT: Int = 180

        const val SUCCESS: Int = 0
        const val FAILED: Int = 1
        const val RUNTIME_ERROR: Int = 2

        fun pascalToSnake(pascal: String): String = buildString {
            var prevLower = false
            for (c in pascal) {
                if (c.isUpperCase()) {
                    if (isNotEmpty() && prevLower) {
                        append('_')
                    } else if (length > 1) {
                        val nextLower = getOrNull(indexOf(c) + 1)?.isLowerCase() == true
                        if (nextLower) append('_')
                    }
                    append(c.lowercaseChar())
                    prevLower = false
                } else {
                    append(c)
                    prevLower = true
                }
            }
        }

        fun getDeserializerMapper(mod: KClass<out AkibaModule>): ObjectMapper {
            val configKClass = mod.findAnnotation<WithConfigClass>() ?.clazz ?: return jacksonObjectMapper()

            val deserializerKClass = mod.findAnnotation<WithConfigDeserializer>() ?.deserializer
                ?: return jacksonObjectMapper()

            @Suppress("UNCHECKED_CAST")
            val deserializer = (deserializerKClass as KClass<JsonDeserializer<Any>>).objectInstance

            val module = SimpleModule().apply {
                addDeserializer(configKClass.java as Class<Any>, deserializer)
            }

            return jacksonObjectMapper().registerModule(module)
        }

        fun getSerializerMapper(mod: KClass<out AkibaModule>): ObjectMapper {
            val configKClass = mod.findAnnotation<WithConfigClass>() ?.clazz ?: return jacksonObjectMapper()

            val serializerKClass = mod.findAnnotation<WithConfigSerializer>() ?.serializer
                ?: return jacksonObjectMapper()

            @Suppress("UNCHECKED_CAST")
            val serializer = (serializerKClass as KClass<JsonSerializer<Any>>).objectInstance

            val module = SimpleModule().apply {
                addSerializer(configKClass.java as Class<Any>, serializer)
            }

            return jacksonObjectMapper().registerModule(module)
        }
    }
}
