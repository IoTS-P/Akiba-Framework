package org.iotsplab.akiba.module

import org.iotsplab.akiba.utils.AkibaJson
import java.nio.file.Files
import java.nio.file.Path

/** A module broke its own contract: it returned something its table cannot hold. */
class ModuleContractViolation(message: String) : IllegalStateException(message)

/**
 * What one module run produced.
 *
 * [result] is what the module chose to return — small, JSON-shaped data the caller can
 * inspect, store or pass on. [status] is the module's exit code: [AkibaModule.SUCCESS]
 * means it worked, anything else is something the caller decides about.
 */
data class ModuleRun(
    val result: Map<String, Any?>?,
    val status: Int,
    val statusName: String,
    val durationMs: Long,
) {
    val isSuccess: Boolean
        get() = status == AkibaModule.SUCCESS
}

/**
 * Checks a module's [AkibaModule.result] against the columns it declared, and decides
 * how a result reaches a human: inline when it is small, a file when it is not.
 */
object ModuleResults {
    /** Results up to this many bytes are printed; beyond that they go to a file. */
    const val INLINE_LIMIT: Int = 1024

    /**
     * A key the table does not have, or a value the column cannot hold, is a bug in the
     * module — not something a caller should tolerate or work around, so this throws.
     */
    fun check(module: String, result: Map<String, Any?>, columns: Map<String, String>, hasTable: Boolean) {
        if (!hasTable)
            throw ModuleContractViolation(
                "$module returned a result, but it declares @DoNotCreateTable and so has no table to hold one")

        result.forEach { (key, value) ->
            val type = columns[key]
                ?: throw ModuleContractViolation(
                    "$module returned '$key', which its table does not have. " +
                            "Declared columns: ${columns.keys.sorted().joinToString()}")
            if (!fits(type, value))
                throw ModuleContractViolation(
                    "$module returned '$key' as ${value?.let { it::class.simpleName } ?: "null"}, " +
                            "but the column is declared $type")
        }
    }

    /** Whether [value] can be stored in a column declared as [type]. Null always can. */
    fun fits(type: String, value: Any?): Boolean = when (value) {
        null -> true
        else -> when (type.lowercase()) {
            "integer" -> value is Int || value is Short || value is Byte
            "bigint" -> value is Int || value is Long || value is Short || value is Byte
            "double precision" -> value is Number
            "text", "timestamptz", "interval" -> value is String
            "boolean" -> value is Boolean
            "jsonb" -> value is String || value is Map<*, *> || value is List<*>
            "bytea" -> value is ByteArray
            else -> false
        }
    }

    /**
     * Small results come back as JSON text; anything bigger goes to [directory] and the
     * message says where, so nothing large ends up in a log or a transcript.
     */
    fun render(module: String, result: Map<String, Any?>, directory: Path): String {
        val json = AkibaJson.mapper.writeValueAsString(result)
        val bytes = json.toByteArray(Charsets.UTF_8).size
        if (bytes <= INLINE_LIMIT) return json

        Files.createDirectories(directory)
        val file = directory.resolve("$module-${System.currentTimeMillis()}.json")
        Files.writeString(file, json, Charsets.UTF_8)
        val kb = String.format("%.1f", bytes / 1024.0)
        return "Result is $kb KB, too large to print. Saved to $file"
    }
}
