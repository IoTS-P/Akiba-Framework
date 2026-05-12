package org.iotsplab.akiba.data.operator

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.iotsplab.akiba.managers.BinaryMetadata
import org.iotsplab.akiba.managers.ImportManager
import org.iotsplab.akiba.utils.DataTarget
import org.iotsplab.akiba.utils.DataTargetType
import org.iotsplab.akiba.utils.TempFileSource
import java.nio.file.Files
import java.nio.file.Path
import java.io.File

class FileDataOperator(private val tempFileSource: TempFileSource) : DataOperator {

    private val mapper = ObjectMapper().registerKotlinModule()
    private val dataFile: File by lazy {
        File(tempFileSource.dir, tempFileSource.fileName).also {
            if (!it.parentFile.exists()) {
                it.parentFile.mkdirs()
            }
            if (!it.exists()) {
                it.createNewFile()
            }
        }
    }

    private val dataCache = mutableMapOf<String, MutableMap<Long, MutableMap<String, Any?>>>()

    init {
        loadData()
    }

    private fun loadData() {
        try {
            val content = dataFile.readText()
            if (content.isNotBlank()) {
                val loaded: Map<String, Map<Long, Map<String, Any?>>> = mapper.readValue(content, Map::class.java) as Map<String, Map<Long, Map<String, Any?>>>
                dataCache.clear()
                dataCache.putAll(loaded.mapValues { (_, v) ->
                    v.mapValues { (_, v2) -> v2.toMutableMap() }.toMutableMap()
                })
            }
        } catch (_: Exception) {
            dataCache.clear()
        }
    }

    private fun saveData() {
        dataFile.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(dataCache))
    }

    override fun testConnection(): Boolean = true

    override fun getIdInSQL(sql: String): List<Long> {
        return dataCache.values.flatMap { it.keys }.distinct().sorted()
    }

    override fun getMetadata(id: Long): BinaryMetadata {
        return dataCache["binaries"]?.get(id)?.let {
            @Suppress("UNCHECKED_CAST")
            BinaryMetadata(
                id = (it["id"] as? Number)?.toInt() ?: id.toInt(),
                originalPath = it["originalPath"] as? String ?: "",
                processedPath = it["processedPath"] as? String,
                arch = it["arch"] as? String,
                format = it["format"] as? String,
                compilerSpec = it["compilerSpec"] as? String,
                loadProperties = listOf(),
                checksum = it["checksum"] as? String ?: "",
                processedChecksum = it["processedChecksum"] as? String
            )
        } ?: throw IllegalStateException("Metadata for id $id not found")
    }

    override fun getModuleData(id: Long, tableName: String, columns: List<String>?): Map<String, Any?> {
        val tableData = dataCache[tableName]?.get(id) ?: return emptyMap()
        return if (columns != null) {
            tableData.filterKeys { it in columns }
        } else {
            tableData
        }
    }

    override fun checkMD5Duplicate(md5: String): Boolean {
        return dataCache["binaries"]?.values?.any {
            (it["checksum"] as? String) == md5
        } ?: false
    }

    override fun checkMD5Duplicate(path: Path): Boolean {
        return try {
            checkMD5Duplicate(calculateMD5(path))
        } catch (_: Exception) {
            false
        }
    }

    private fun calculateMD5(path: Path): String {
        return java.security.MessageDigest.getInstance("MD5").digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }
    }

    override fun insertBinary(data: org.iotsplab.akiba.data.database.DatabaseClient.InsertData): Long {
        val id = System.currentTimeMillis()
        @Suppress("UNCHECKED_CAST")
        dataCache.getOrPut("binaries") { mutableMapOf() }[id] = mutableMapOf(
            "id" to id,
            "originalPath" to data.originalPath,
            "processedPath" to data.processedPath,
            "checksum" to data.checksum,
            "processedChecksum" to data.processedChecksum,
            "size" to data.size,
            "processedSize" to data.processedSize,
            "loadProperties" to data.loadProperties,
            "arch" to data.arch,
            "format" to data.format,
            "compilerSpec" to data.compilerSpec
        )
        saveData()
        return id
    }

    override fun createModuleTable(tableName: String, columns: Map<String, String>) {
        dataCache.getOrPut(tableName) { mutableMapOf() }
    }

    override fun createView(viewName: String, sql: String, overwrite: Boolean) {}

    override fun tableLock(tableName: String) {}

    override fun tableUnlock(tableName: String) {}

    override fun updateData(tableName: String, id: Long, data: Map<String, Any?>) {
        dataCache.getOrPut(tableName) { mutableMapOf() }
            .getOrPut(id) { mutableMapOf() }
            .putAll(data.filterValues { it != null })
        saveData()
    }

    override fun startTask(tableName: String, id: Long) {
        dataCache.getOrPut(tableName) { mutableMapOf() }
            .getOrPut(id) { mutableMapOf() }["start_timestamp"] = java.time.OffsetDateTime.now().toString()
    }

    override fun finishTask(tableName: String, id: Long) {
        val table = dataCache[tableName]?.get(id) ?: return
        val startTime = table["start_timestamp"] as? String ?: return
        val finishTime = java.time.OffsetDateTime.now().toString()
        table["finish_timestamp"] = finishTime
        val start = java.time.OffsetDateTime.parse(startTime)
        val finish = java.time.OffsetDateTime.parse(finishTime)
        table["execute_time"] = java.time.Duration.between(start, finish).toString()
        saveData()
    }

    override fun enableRoute(route: String) {}
    override fun disableRoute(route: String) {}
    override fun sendHeartbeat() {}

    override fun login(userName: String, password: String) {}
    override fun logout() {}
    override fun createInstance(instanceName: String) {}
    override fun connectToInstance(instanceName: String) {}
    override fun disconnectToInstance(instanceName: String) {}
    override fun shutdownInstance(instanceName: String) {}
    override fun deleteInstance(instanceName: String) {}

    override fun createBackup(isFull: Boolean, instanceName: String, alias: String?, description: String?): String = ""

    override fun peekBackups(instanceName: String): List<org.iotsplab.akiba.data.database.DatabaseClient.BackupNode> = emptyList()

    override fun restoreBackup(instanceName: String, aliasOrLabel: String) {}

    companion object {
        fun fromDataTarget(dataTarget: DataTarget): FileDataOperator? {
            return when (dataTarget.type) {
                DataTargetType.TEMP_FILE -> dataTarget.tempFile?.let { FileDataOperator(it) }
                else -> null
            }
        }
    }
}
