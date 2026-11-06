package org.iotsplab.akiba.llm.agent.stream

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.apache.logging.log4j.LogManager
import org.iotsplab.akiba.utils.AkibaJson
import org.iotsplab.akiba.utils.InternalToken

/**
 * Fire-and-forget publisher that forwards live LLM streaming chunks from a
 * worker to the server's [StreamChunkBus], which the browser drains via polls.
 *
 * In-process workers publish straight to the bus; cross-process workers POST
 * to `http://127.0.0.1:$PORT/api/agent/internal/stream-chunk`, discovering the
 * port from env or the port file and authenticating with the shared secret in
 * [InternalToken.file].  Failures are dropped so a dead server never stalls
 * the consumer.
 */
object StreamingChunkPusher {

    private val logger = LogManager.getLogger(StreamingChunkPusher::class.java)
    private val mapper = AkibaJson.mapper
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    /**
     * Serial sender queue for cross-process POSTs: chunks MUST reach
     * the server in publish order (the poll endpoint only moves its
     * `since` cursor forward, and a late generation-start chunk would
     * wipe newer history).  Every request is funneled through a single
     * daemon thread that sends them one at a time; `offer` never
     * blocks the worker.
     */
    private val sendQueue = java.util.concurrent.LinkedBlockingQueue<HttpRequest>()
    private val inFlightSends = java.util.concurrent.atomic.AtomicInteger(0)

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
                    inFlightSends.incrementAndGet()
                    try {
                        val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
                        if (response.statusCode() !in 200..299) {
                            // A rejected push used to be invisible: the UI simply
                            // stopped streaming, with no error anywhere.
                            logger.warn(
                                "stream-chunk POST rejected: HTTP {} ({})",
                                response.statusCode(), response.uri().path
                            )
                        }
                    } catch (e: Exception) {
                        logger.warn("stream-chunk POST failed: ${e.message}")
                    } finally {
                        inFlightSends.decrementAndGet()
                    }
                }
            }, "stream-chunk-sender").apply { isDaemon = true; start() }
        }
    }

    /**
     * Block until the cross-process send queue has drained (queue empty
     * and no POST in flight), at most [maxMs].
     *
     * Worker JVMs MUST call this before exiting: the sender thread is a
     * daemon, so a plain JVM exit silently discards whatever is still
     * queued — typically the response TAIL and the `done` marker, which
     * the browser then never sees (the "final answer arrives truncated"
     * bug).
     */
    fun awaitQuiescence(maxMs: Long = 3_000) {
        val deadline = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < deadline) {
            if (sendQueue.isEmpty() && inFlightSends.get() == 0) return
            Thread.sleep(25)
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

    /** Shared secret read from disk; resolved once per worker JVM. */
    @Volatile private var cachedInternalToken: String? = null
    @Volatile private var internalTokenResolved = false
    @Volatile private var missingTokenLogged = false

    /**
     * Shared secret that authenticates this worker to the server's internal
     * chunk endpoint.  Read from `~/.akiba/akiba_internal.token`, which the
     * server rewrites on every start — the same file every launch path can
     * see, unlike an inherited environment variable.
     */
    private fun internalToken(): String? {
        if (internalTokenResolved) return cachedInternalToken
        cachedInternalToken = InternalToken.readOrNull()
        internalTokenResolved = true
        return cachedInternalToken
    }

    private fun warnMissingTokenOnce() {
        if (missingTokenLogged) return
        missingTokenLogged = true
        logger.warn(
            "no internal token at {} — cross-process stream chunks will be rejected",
            InternalToken.file
        )
    }

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
            // The receiving route accepts the shared secret from
            // ~/.akiba/akiba_internal.token instead of a user JWT: a worker
            // JVM has no session of its own, and environment variables only
            // reach workers the server spawned directly.
            val resolvedInstance = instanceName
                ?: System.getenv("AKIBA_MANUAL_AGENT_INSTANCE")?.takeIf { it.isNotBlank() }
                ?: "akiba-instance"
            val builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:$port/api/agent/internal/stream-chunk"))
                .header("Content-Type", "application/json")
                .header("X-Akiba-Instance", resolvedInstance)
                .timeout(Duration.ofSeconds(2))
            val token = internalToken()
            if (token != null) {
                builder.header(InternalToken.HEADER, token)
            } else {
                warnMissingTokenOnce()
            }
            val request = builder
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            ensureSenderThread()
            sendQueue.offer(request)
        } catch (e: Exception) {
            logger.debug("stream-chunk publish failed for session $sessionId: ${e.message}")
        }
    }
}
