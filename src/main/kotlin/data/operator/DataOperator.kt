package org.iotsplab.akiba.data.operator

import org.iotsplab.akiba.data.database.DatabaseClient
import org.iotsplab.akiba.managers.BinaryMetadata
import java.nio.file.Path

interface DataOperator {
    class DataOperatorException(val msg: String? = null): Exception(msg)

    fun testConnection(): Boolean

    fun getIdInSQL(sql: String): List<Long>
    fun getMetadata(id: Long): BinaryMetadata
    fun getModuleData(id: Long, tableName: String, columns: List<String>?): Map<String, Any?>

    fun checkMD5Duplicate(md5: String): Boolean
    fun checkMD5Duplicate(path: Path): Boolean
    fun insertBinary(data: DatabaseClient.InsertData): Long

    fun createModuleTable(tableName: String, columns: Map<String, String>)
    fun createView(viewName: String, sql: String, overwrite: Boolean)
    fun tableLock(tableName: String)
    fun tableUnlock(tableName: String)
    fun updateData(tableName: String, id: Long, data: Map<String, Any?>)
    fun startTask(tableName: String, id: Long)
    fun finishTask(tableName: String, id: Long)

    fun enableRoute(route: String)
    fun disableRoute(route: String)
    fun sendHeartbeat()

    fun login(userName: String, password: String)
    fun logout()
    fun createInstance(instanceName: String)
    fun connectToInstance(instanceName: String)
    fun disconnectToInstance(instanceName: String)
    fun shutdownInstance(instanceName: String)
    fun deleteInstance(instanceName: String)

    fun createBackup(isFull: Boolean, instanceName: String, alias: String?, description: String?): String
    fun peekBackups(instanceName: String): List<DatabaseClient.BackupNode>
    fun restoreBackup(instanceName: String, aliasOrLabel: String)
}