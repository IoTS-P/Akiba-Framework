package org.iotsplab.akiba.plan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Append-only record of what a plan actually did.
 *
 * The old preview tried to predict a run before it happened; a script that branches on
 * query results makes that guess worthless. This log is written as the run unfolds, so
 * it always matches reality and still answers "what did this batch do".
 */
class PlanJournal(private val file: File?) {

    private val json = Json { encodeDefaults = false }

    @Synchronized
    fun record(kind: String, args: Map<String, Any?>, result: Outcome<*>, elapsedMs: Long) {
        val target = file ?: return
        val line = buildJsonObject {
            put("kind", kind)
            put("ms", elapsedMs)
            when (result) {
                is Outcome.Ok -> {
                    put("status", "ok")
                    put("value", result.value?.toString() ?: "")
                }
                is Outcome.Failed -> {
                    put("status", "failed")
                    put("error", result.error.message)
                }
            }
            put("args", buildJsonObject { args.forEach { (k, v) -> put(k, v?.toString() ?: "") } })
        }
        target.parentFile?.mkdirs()
        target.appendText(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), line) + "\n")
    }

    companion object {
        fun disabled() = PlanJournal(null)
        fun at(file: File) = PlanJournal(file)
    }
}
