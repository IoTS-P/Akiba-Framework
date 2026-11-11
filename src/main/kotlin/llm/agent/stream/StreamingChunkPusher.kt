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
 * [InternalToken.file].  Queued chunks are coalesced into ONE batched POST per
 * drain (and that batch is retried once), because a per-token round-trip makes
 * the tail of a fast response — including its `done` marker — lose the race
 * with the worker's exit.
 */
object StreamingChunkPusher {

    private val logger = LogManager.getLogger(StreamingChunkPusher::class.java)
    private val mapper = AkibaJson.mapper
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    /** Chunks carried by one POST; keeps a burst to a single round-trip. */
    private const val MAX_BATCH = 256

    /** One queued chunk, in producer order. */
    private data class Queued(
        val sessionId: String,
        val delta: String,
        val chunkCount: Int,
        val byteCount: Int,
        val done: Boolean,
        val error: String?,
        val instanceName: String?,
        val newGeneration: Boolean,
        val kind: String,
    )

    /**
     * Serial sender queue for cross-process POSTs: chunks MUST reach
     * the server in publish order (the bus appends in arrival order and
     * the poller only moves its cursor forward).  Every batch is sent by
     * a single daemon thread; `offer` never blocks the worker.
     */
    private val sendQueue = java.util.concurrent.LinkedBlockingQueue<Queued>()
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
                    val first = try {
                        sendQueue.take()
                    } catch (_: InterruptedException) {
                        continue
                    }
                    // Counted before the batch is assembled so `awaitQuiescence`
                    // can never observe "queue empty, nothing in flight" while a
                    // chunk sits in a local list.
                    inFlightSends.incrementAndGet()
                    try {
                        val batch = ArrayList<Queued>(MAX_BATCH)
                        batch.add(first)
                        sendQueue.drainTo(batch, MAX_BATCH - 1)
                        if (!sendBatch(batch)) {
                            // One retry, then give up loudly: a rejected push
                            // used to be invisible, and the UI simply stopped
                            // streaming with no error anywhere.
                            Thread.sleep(50)
                            if (!sendBatch(batch)) {
                                logger.warn(
                                    "stream-chunk batch dropped after retry: {} chunk(s), session {}",
                                    batch.size, batch.first().sessionId
                                )
                            }
                        }
                    } catch (e: InterruptedException) {
                        logger.warn("stream-chunk sender interrupted: ${e.message}")
                    } finally {
                        inFlightSends.decrementAndGet()
                    }
                }
            }, "stream-chunk-sender").apply { isDaemon = true; start() }
        }
    }

    /** POST one batch; false on any transport failure or non-2xx answer. */
    private fun sendBatch(batch: List<Queued>): Boolean {
        val port = serverPort() ?: run {
            // The server port vanished (in-process publish is handled by
            // [publish]); keep the batch rather than dropping it silently.
            logger.debug("stream-chunk batch held: server port unavailable")
            return true
        }
        val body = try {
            mapper.writeValueAsString(
                mapOf(
                    "sessionId" to batch.first().sessionId,
                    "chunks" to batch.map { c ->
                        mapOf(
                            "sessionId" to c.sessionId,
                            "delta" to c.delta,
                            "chunkCount" to c.chunkCount,
                            "byteCount" to c.byteCount,
                            "done" to c.done,
                            "error" to c.error,
                            "newGeneration" to c.newGeneration,
                            "kind" to c.kind,
                        )
                    },
                )
            )
        } catch (e: Exception) {
            logger.warn("stream-chunk batch could not be serialised: ${e.message}")
            return true
        }
        // The receiving route accepts the shared secret from
        // ~/.akiba/akiba_internal.token instead of a user JWT: a worker
        // JVM has no session of its own, and environment variables only
        // reach workers the server spawned directly.
        val resolvedInstance = batch.firstOrNull { it.instanceName != null }?.instanceName
            ?: System.getenv("AKIBA_MANUAL_AGENT_INSTANCE")?.takeIf { it.isNotBlank() }
            ?: "akiba-instance"
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:$port/api/agent/internal/stream-chunk"))
            .header("Content-Type", "application/json")
            .header("X-Akiba-Instance", resolvedInstance)
            .timeout(Duration.ofSeconds(5))
        val token = internalToken()
        if (token != null) {
            builder.header(InternalToken.HEADER, token)
        } else {
            warnMissingTokenOnce()
        }
        return try {
            val request = builder
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
            if (response.statusCode() !in 200..299) {
                logger.warn(
                    "stream-chunk POST rejected: HTTP {} ({})",
                    response.statusCode(), response.uri().path
                )
                false
            } else {
                true
            }
        } catch (e: Exception) {
            logger.warn("stream-chunk POST failed: ${e.message}")
            false
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
            Thread.sleep(10)
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
     * @param done       true on the terminal chunk.
     * @param error      optional error message (terminal chunks only).
     * @param instanceName optional X-Akiba-Instance header value — required
     *                   by the internal route.  When null we fall back to
     *                   "akiba-instance" (the default container's instance).
     * @param newGeneration true on the first chunk of an LLM call, so the bus
     *                   opens a generation before any text arrives.
     */
    fun publish(
        sessionId: String,
        delta: String,
        chunkCount: Int,
        byteCount: Int,
        done: Boolean = false,
        error: String? = null,
        instanceName: String? = null,
        newGeneration: Boolean = false,
        kind: String = "text",
    ) {
        if (sessionId.isBlank()) return
        val port = serverPort()
        if (port == null) {
            // In-process: just publish to the bus directly.
            StreamChunkBus.publish(
                StreamChunkBus.Chunk(
                    sessionId = sessionId,
                    delta = delta,
                    chunkCount = chunkCount,
                    byteCount = byteCount,
                    done = done,
                    error = error,
                    newGeneration = newGeneration,
                    kind = kind,
                )
            )
            return
        }
        // Cross-process: enqueue for the serial sender, which coalesces the
        // queue into one POST (see [sendQueue]).  Never blocks the caller.
        ensureSenderThread()
        sendQueue.offer(
            Queued(
                sessionId = sessionId,
                delta = delta,
                chunkCount = chunkCount,
                byteCount = byteCount,
                done = done,
                error = error,
                instanceName = instanceName,
                newGeneration = newGeneration,
                kind = kind,
            )
        )
    }
}
