package org.iotsplab.akiba.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.io.File
import java.nio.file.Files

/**
 * Host resource metrics for the dashboard.
 *
 * Everything is read from `/proc`, which is what the container exposes: the
 * values describe the container's view of the machine, not the host's, and
 * that is the view the operator needs when a worker is killed for memory.
 */
fun Route.systemRoutes() {
    route("/system") {
            /** CPU, memory, disk and the Akiba JVMs that are actually running. */
            get("/metrics") {
                if (!call.requireAuthenticated()) return@get
                if (!File("/proc/stat").exists()) {
                    return@get call.respond(
                        HttpStatusCode.NotImplemented,
                        mapOf("error" to "Host metrics are only available on Linux (/proc)")
                    )
                }
                call.respond(
                    mapOf(
                        "cpu" to readCpu(),
                        "memory" to readMemory(),
                        "disk" to readDisk(call.currentUserGhidraProjectsRoot()),
                        "processes" to readAkibaProcesses(),
                        "time" to System.currentTimeMillis(),
                    )
                )
            }
    }
}

/** Two /proc/stat samples 200 ms apart give a usable utilisation figure. */
private fun readCpu(): Map<String, Any?> {
    fun sample(): Pair<Long, Long> {
        val line = File("/proc/stat").readLines().firstOrNull { it.startsWith("cpu ") } ?: return 0L to 0L
        val parts = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        val idle = (parts.getOrNull(3) ?: 0L) + (parts.getOrNull(4) ?: 0L)
        return parts.sum() to idle
    }
    val (total0, idle0) = sample()
    Thread.sleep(200)
    val (total1, idle1) = sample()
    val totalDelta = (total1 - total0).coerceAtLeast(1)
    val idleDelta = (idle1 - idle0).coerceAtLeast(0)
    val usage = ((totalDelta - idleDelta).toDouble() / totalDelta * 100.0)
    return mapOf(
        "usagePercent" to usage.coerceIn(0.0, 100.0),
        "cores" to Runtime.getRuntime().availableProcessors(),
        "loadAverage" to runCatching {
            File("/proc/loadavg").readText().trim().split(" ").take(3).map { it.toDouble() }
        }.getOrDefault(emptyList<Double>()),
    )
}

private fun readMemory(): Map<String, Any?> {
    val values = File("/proc/meminfo").readLines()
        .mapNotNull { line ->
            val parts = line.split(":")
            if (parts.size != 2) null
            else parts[0].trim() to (parts[1].trim().split(" ").firstOrNull()?.toLongOrNull() ?: 0L) * 1024
        }
        .toMap()
    val total = values["MemTotal"] ?: 0L
    val available = values["MemAvailable"] ?: values["MemFree"] ?: 0L
    val used = (total - available).coerceAtLeast(0)
    return mapOf(
        "totalBytes" to total,
        "usedBytes" to used,
        "availableBytes" to available,
        "usagePercent" to if (total > 0) used.toDouble() / total * 100.0 else 0.0,
        "swapTotalBytes" to (values["SwapTotal"] ?: 0L),
        "swapUsedBytes" to ((values["SwapTotal"] ?: 0L) - (values["SwapFree"] ?: 0L)).coerceAtLeast(0),
    )
}

/** Disk usage of the filesystem that holds the Ghidra projects. */
private fun readDisk(path: java.nio.file.Path): Map<String, Any?> = runCatching {
    val store = Files.getFileStore(path)
    val total = store.totalSpace
    val free = store.usableSpace
    mapOf(
        "path" to path.toString(),
        "totalBytes" to total,
        "freeBytes" to free,
        "usedBytes" to (total - free).coerceAtLeast(0),
        "usagePercent" to if (total > 0) (total - free).toDouble() / total * 100.0 else 0.0,
    )
}.getOrElse {
    mapOf("path" to path.toString(), "error" to (it.message ?: "unavailable"))
}

/** Every java process with a bound on its resident memory, largest first. */
private fun readAkibaProcesses(): List<Map<String, Any?>> {
    val self = ProcessHandle.current().pid()
    return File("/proc").listFiles()
        ?.filter { it.isDirectory && it.name.all(Char::isDigit) }
        ?.mapNotNull { dir ->
            val cmdline = runCatching {
                File(dir, "cmdline").readText().replace('\u0000', ' ').trim()
            }.getOrNull() ?: return@mapNotNull null
            if (!cmdline.contains("java")) return@mapNotNull null
            val status = runCatching { File(dir, "status").readLines() }.getOrNull() ?: return@mapNotNull null
            val rssKb = status.firstOrNull { it.startsWith("VmRSS:") }
                ?.let { it.split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() }
                ?: return@mapNotNull null
            val role = when {
                cmdline.contains("akiba_db_daemon") -> "database daemon"
                cmdline.contains("manual-turn") || cmdline.contains("ManualAgent") -> "agent worker"
                cmdline.contains("akiba_framework") || cmdline.contains("AkibaServer") -> "server"
                else -> "java"
            }
            mapOf(
                "pid" to dir.name.toLong(),
                "rssBytes" to rssKb * 1024,
                "role" to role,
                "self" to (dir.name.toLong() == self),
            )
        }
        ?.sortedByDescending { (it["rssBytes"] as Long) }
        ?: emptyList()
}
