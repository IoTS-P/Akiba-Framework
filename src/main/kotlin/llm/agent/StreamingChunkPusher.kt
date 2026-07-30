package org.iotsplab.akiba.llm.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.logging.log4j.LogManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Fire-and-forget publisher that forwards live LLM streaming chunks
 * from a worker to the server's [StreamChunkBus], whose per-session
 * history the browser drains via incremental polls
 * (`GET /agent/sessions/{id}/stream-chunks`).
 *
 * Two transport modes:
 *  - **In-process** (worker runs inside the server JVM, e.g. the
 *    AgentRuntime's own coroutines): publishes directly to
 *    [StreamChunkBus] without going through HTTP.
 *  - **Cross-process** (manual-agent worker spawned as a separate
 *    JVM): POSTs each chunk to `http://127.0.0.1:$PORT/api/agent/internal/stream-chunk`.
 *    The server port is discovered from the
 *    `AKIBA_MANUAL_AGENT_SERVER_PORT` environment variable that
 *    `AgentRoutes.runManualAgentWorker` exports.
 *
 * Back-pressure: every public method is non-blocking.  Cross-process
 * sends use `sendAsync` with a 2-second timeout and silently drop
 * failures — a slow/dead server must not stall the LLM consumer
 * (that was the bug that caused "preview shows 1-2 chars then
 * everything dumps at once" in the previous DB-write-based
 * heartbeat).
 */
object StreamingChunkPusher {

    private val logger = LogManager.getLogger(StreamingChunkPusher::class.java)
    private val mapper = jacksonObjectMapper()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    /**
     * Serial sender queue for cross-process POSTs.  Chunks MUST
     * reach the server in publish order: the poll endpoint only ever
     * moves its `since` cursor forward, so a chunk that arrives
     * after a higher-numbered one would be skipped forever (a
     * permanently missing character in the bubble), and a
     * generation-start chunk (`chunkCount == 1`) arriving late would
     * wipe newer chunks from the history.  `sendAsync` makes no
     * ordering guarantee, so every request is funneled through a
     * single daemon thread that sends them one at a time.  The
     * queue is unbounded but self-limiting (an LLM generation
     * produces at most a few thousand small chunks), and the worker
     * side never blocks: `offer` is non-blocking and the sender
     * thread absorbs the network latency.
     */
    private val sendQueue = java.util.concurrent.LinkedBlockingQueue<HttpRequest>()

    @Volatile private var senderStarted = false
    private val senderLock = Any()

    private fun ensureSenderThread() {
        if (senderStarted) return
        synchronized(senderLock) {
            if (senderStarted) return
            senderStarted = true
            Thread({
                while (true) {
                    val request = try {
                        sendQueue.take()
                    } catch (_: InterruptedException) {
                        continue
                    }
                    try {
                        httpClient.send(request, HttpResponse.BodyHandlers.discarding())
                    } catch (e: Exception) {
                        logger.debug("stream-chunk POST failed: ${e.message}")
                    }
                }
            }, "stream-chunk-sender").apply { isDaemon = true; start() }
        }
    }

    /**
     * Well-known file the server writes its HTTP port to on startup.
     * Worker JVMs read it to discover where to push chunks — env
     * vars only reach workers the server spawns directly (manual
     * agent), while workflow module processes get nothing, so a
     * well-known file is the only mechanism that covers every
     * launch path.  Lives under the shared home dir (all co-located
     * JVMs run as the same user).
     */
    private val portFile: java.io.File by lazy {
        java.io.File(System.getProperty("user.home"), ".akiba/akiba_server.port")
    }

    /** True when this JVM IS the server (it called [publishServerPortFile]). */
    @Volatile private var inProcessServer = false

    private var cachedFilePort: Int? = null
    private var portFileChecked = false

    /**
     * Called by akiba_server at startup: records that this JVM is
     * the server (so its own publishes go straight to the bus in
     * memory) and writes the HTTP port to the well-known file for
     * co-located worker JVMs.
     */
    fun publishServerPortFile(port: Int) {
        inProcessServer = true
        runCatching {
            portFile.parentFile?.mkdirs()
            portFile.writeText(port.toString())
        }
    }

    /**
     * Resolve the server's HTTP port for cross-process pushes, or
     * null when running inside the server JVM itself (in which case
     * the caller publishes to the bus directly).
     */
    private fun serverPort(): Int? {
        // The server JVM publishes in memory — no HTTP hop.
        if (inProcessServer) return null
        System.getenv("AKIBA_MANUAL_AGENT_SERVER_PORT")?.toIntOrNull()?.let { return it }
        // Fallback for workers launched without the env var (e.g.
        // workflow module processes): read the server's port file.
        // Checked once — the server outlives any worker it spawned,
        // and its port does not change while a worker runs.
        if (!portFileChecked) {
            portFileChecked = true
            cachedFilePort = runCatching {
                portFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
            }.getOrNull()
            if (cachedFilePort == null) {
                logger.debug("no server port file at ${portFile.absolutePath} — chunks will stay in-process")
            }
        }
        return cachedFilePort
    }

    /**
     * Publish a single streaming chunk.  Never blocks the caller.
     *
     * @param sessionId the agent session this chunk belongs to.
     * @param delta     incremental text delta for this chunk.
     * @param chunkCount cumulative chunk count (1-based).
     * @param byteCount  cumulative byte count of the full response.
     * @param done       true on the terminal chunk; the SSE endpoint
     *                   closes the stream after emitting it.
     * @param error      optional error message (terminal chunks only).
     * @param instanceName optional X-Akiba-Instance header value —
     *                   required by the internal route.  When null we
     *                   fall back to "akiba-instance" (the default
     *                   docker container's instance name).
     */
    fun publish(
        sessionId: String,
        delta: String,
        chunkCount: Int,
        byteCount: Int,
        done: Boolean = false,
        error: String? = null,
        instanceName: String? = null,
    ) {
        if (sessionId.isBlank()) return
        val chunk = StreamChunkBus.Chunk(
            sessionId = sessionId,
            delta = delta,
            chunkCount = chunkCount,
            byteCount = byteCount,
            done = done,
            error = error,
        )
        val port = serverPort()
        if (port == null) {
            // In-process: just publish to the bus directly.
            StreamChunkBus.publish(chunk)
            return
        }
        // Cross-process: enqueue for the serial sender (ordering
        // guarantee, see sendQueue).  Never blocks the caller.
        try {
            val body = mapper.writeValueAsString(
                mapOf(
                    "sessionId" to sessionId,
                    "delta" to delta,
                    "chunkCount" to chunkCount,
                    "byteCount" to byteCount,
                    "done" to done,
                    "error" to error,
                )
            )
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:$port/api/agent/internal/stream-chunk"))
                .header("Content-Type", "application/json")
                .header("X-Akiba-Instance", instanceName ?: "akiba-instance")
                .timeout(Duration.ofSeconds(2))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            ensureSenderThread()
            sendQueue.offer(request)
        } catch (e: Exception) {
            logger.debug("stream-chunk publish failed for session $sessionId: ${e.message}")
        }
    }
}
