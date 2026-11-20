package org.iotsplab.akiba.server.db

import org.apache.logging.log4j.LogManager
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * Reads and corrects the rows that belong to one binary.
 *
 * This talks to the instance database directly, with the server's own credentials,
 * rather than through the daemon. The daemon route for the same data answered with an
 * empty body, and a records view that fails silently is worse than one that does not
 * exist — the queries here are the daemon's own, and the result can be checked with psql.
 */
class RecordsRepository(private val config: ServerDbConfig) {

    private val logger = LogManager.getLogger(RecordsRepository::class.java)

    /** Tables that can carry a row about a binary, and the columns that may be written. */
    private val identifier = Regex("^[a-z_][a-z0-9_]*$")

    /** Identity, size and provenance: shown, never rewritten. */
    private val protectedBinaries = setOf("id", "size", "checksum", "original_path")

    private fun connect(instance: String): Connection = DriverManager.getConnection(
        "jdbc:postgresql://${config.host}:${config.port}/inst_${instance}_instance",
        config.user,
        config.password,
    )

    fun read(instance: String, id: Long): Map<String, Any?> = connect(instance).use { conn ->
        val tables = mutableListOf<Map<String, Any?>>()
        for (table in candidateTables(conn)) {
            if (!identifier.matches(table)) continue
            val columns = columnsOf(conn, table)
            if (columns.isEmpty()) continue

            val where = if (table == "binaries") "id = ?" else "binary_id = ?"
            val rows = mutableListOf<Map<String, Any?>>()
            conn.prepareStatement("SELECT * FROM $table WHERE $where LIMIT $MAX_ROWS").use { stmt ->
                stmt.setLong(1, id)
                stmt.executeQuery().use { rs ->
                    val meta = rs.metaData
                    while (rs.next()) {
                        val values = linkedMapOf<String, Any?>()
                        val lengths = linkedMapOf<String, Int>()
                        for (index in 1..meta.columnCount) {
                            val name = meta.getColumnName(index)
                            val raw = rs.getObject(index)
                            values[name] = display(raw)
                            val trueLength = when (raw) {
                                null -> 0
                                is String -> raw.length
                                is ByteArray -> raw.size
                                else -> 0
                            }
                            if (trueLength > MAX_TEXT) lengths[name] = trueLength
                        }
                        rows.add(mapOf("values" to values, "lengths" to lengths))
                    }
                }
            }
            if (rows.isEmpty()) continue
            tables.add(mapOf("name" to table, "columns" to columns, "rows" to rows))
        }
        mapOf("id" to id, "tables" to tables)
    }

    fun write(instance: String, table: String, id: Long, column: String, value: String) {
        require(identifier.matches(table)) { "Unsupported table name" }
        require(identifier.matches(column)) { "Unsupported column name" }
        require(!(table == "binaries" && column in protectedBinaries)) { "'$column' cannot be edited" }
        require(value.length <= MAX_VALUE) { "Value is too long" }

        connect(instance).use { conn ->
            val type = conn.prepareStatement(
                "SELECT data_type FROM information_schema.columns " +
                    "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?"
            ).use { stmt ->
                stmt.setString(1, table)
                stmt.setString(2, column)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            } ?: throw IllegalArgumentException("No column '$column' in '$table'")

            // The column's own type validates the text: a bad timestamp fails here, with
            // the database's wording, instead of being accepted and breaking later.
            val numeric = type.contains("int") || type.contains("numeric") ||
                type.contains("double") || type.contains("real")
            val sql = if (numeric)
                "UPDATE $table SET $column = CAST(? AS $type) WHERE id = ?"
            else
                "UPDATE $table SET $column = ? WHERE id = ?"

            val updated = conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, value)
                stmt.setLong(2, id)
                stmt.executeUpdate()
            }
            if (updated == 0) throw IllegalArgumentException("No row $id in '$table'")
        }
    }

    private fun candidateTables(conn: Connection): List<String> {
        val out = mutableListOf("binaries")
        conn.prepareStatement(
            "SELECT table_name FROM information_schema.columns " +
                "WHERE table_schema = 'public' AND column_name = 'binary_id' ORDER BY table_name"
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val name = rs.getString(1)
                    if (name != "binaries") out.add(name)
                }
            }
        }
        return out
    }

    private fun columnsOf(conn: Connection, table: String): List<Map<String, String>> {
        val columns = mutableListOf<Map<String, String>>()
        conn.prepareStatement(
            "SELECT column_name, data_type FROM information_schema.columns " +
                "WHERE table_schema = 'public' AND table_name = ? ORDER BY ordinal_position"
        ).use { stmt ->
            stmt.setString(1, table)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    columns.add(mapOf("name" to rs.getString(1), "type" to rs.getString(2)))
                }
            }
        }
        return columns
    }

    /** Long cells are cut before they reach the browser; their real length rides along. */
    private fun display(value: Any?): Any? = when (value) {
        null -> null
        is ByteArray -> "<${value.size} bytes>"
        is String -> if (value.length > MAX_TEXT) value.take(MAX_TEXT) else value
        else -> value
    }

    private companion object {
        const val MAX_ROWS = 50
        const val MAX_TEXT = 4096
        const val MAX_VALUE = 100_000
    }
}
