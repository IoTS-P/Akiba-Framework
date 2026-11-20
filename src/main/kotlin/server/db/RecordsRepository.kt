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

    /**
     * The instance name is what the request carries (`test_instance`), and its database
     * is the prefix plus that name. A caller that already passes the full database name
     * is accepted too — being wrong about which of the two it has is how the first
     * version asked for `inst_test_instance_instance`.
     */
    private fun databaseName(instance: String): String = when {
        instance.startsWith("inst_") -> instance
        else -> "inst_$instance"
    }

    private fun connect(instance: String): Connection = DriverManager.getConnection(
        "jdbc:postgresql://${config.host}:${config.port}/${databaseName(instance)}",
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
                "SELECT data_type, udt_name FROM information_schema.columns " +
                    "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?"
            ).use { stmt ->
                stmt.setString(1, table)
                stmt.setString(2, column)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) null else {
                        val declared = rs.getString(1)
                        val udt = rs.getString(2)
                        // An array reports its type as "ARRAY"; the cast has to name the
                        // element type, and the catalogue spells it "_text" for text[].
                        if (declared.equals("ARRAY", ignoreCase = true)) {
                            udt.removePrefix("_") + "[]"
                        } else {
                            declared
                        }
                    }
                }
            } ?: throw IllegalArgumentException("No column '$column' in '$table'")

            // Every value is cast to the column's own type: that is what validates a
            // timestamp, an array literal or jsonb, and the error comes back in the
            // database's words instead of being accepted and breaking later.
            val sql = "UPDATE $table SET $column = CAST(? AS $type) WHERE id = ?"

            val updated = try {
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, value)
                    stmt.setLong(2, id)
                    stmt.executeUpdate()
                }
            } catch (e: java.sql.SQLException) {
                // The database's own words, then what it would have accepted: "invalid
                // input syntax for type date" without an example leaves the reader
                // guessing which of the several date spellings this column takes.
                val hint = hintFor(type)
                val message = e.message?.lineSequence()?.firstOrNull().orEmpty()
                throw IllegalArgumentException(if (hint.isEmpty()) message else "$message — $hint")
            }
            if (updated == 0) throw IllegalArgumentException("No row $id in '$table'")
        }
    }

    /** What this column type accepts, in the same order a person would try it. */
    private fun hintFor(type: String): String = when {
        type.contains("timestamp") ->
            "expected ISO 8601 with an offset, e.g. 2026-11-19T12:00:00+08:00"
        type == "date" -> "expected YYYY-MM-DD, e.g. 2026-11-19"
        type.startsWith("time") -> "expected HH:MM:SS, e.g. 12:00:00"
        type.contains("uuid") -> "expected 8-4-4-4-12 hex, e.g. 8c519a5b-1604-48f5-aad7-49aef6c3bec7"
        type.equals("ARRAY", ignoreCase = true) || type.endsWith("[]") ->
            "expected a PostgreSQL array literal, e.g. {alpha,beta}"
        type.contains("json") -> "expected JSON text, e.g. {\"k\": \"v\"}"
        type.contains("bool") -> "expected true or false"
        type.contains("int") || type.contains("numeric") ||
            type.contains("double") || type.contains("real") -> "expected a number"
        else -> ""
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

    /**
     * Turns a column value into something the browser can read.
     *
     * The driver does not hand back plain values for every type: `jsonb` arrives as a
     * `PGobject` and an array as a `java.sql.Array`, and neither survives JSON
     * serialisation — which is exactly what a table with those columns looked like when
     * it silently failed to appear.
     */
    private fun display(value: Any?): Any? = when (value) {
        null -> null
        is ByteArray -> "<${value.size} bytes>"
        is java.sql.Array -> runCatching { (value.array as Array<*>).joinToString(",", "{", "}") }
            .getOrElse { value.toString() }
        is org.postgresql.util.PGobject -> value.value
        is String -> if (value.length > MAX_TEXT) value.take(MAX_TEXT) else value
        else -> value
    }

    private companion object {
        const val MAX_ROWS = 50
        const val MAX_TEXT = 4096
        const val MAX_VALUE = 100_000
    }
}
