package org.iotsplab.akiba.server

import org.apache.logging.log4j.LogManager
import org.iotsplab.akiba.server.db.ServerDbConfig
import java.sql.Connection
import java.sql.DriverManager
import kotlin.concurrent.thread

/**
 * Closes conversation rows that a dead turn left in a running state.
 *
 * The previous attempt reused [org.iotsplab.akiba.llm.agent.runtime.AgentSessionReconciler],
 * which talks to the daemon as a signed-in user — so it had nothing to say until
 * somebody logged in, and after every restart it went quiet exactly when its
 * cleanup was needed. This one talks to PostgreSQL directly with the server's own
 * credentials, so it works whether anyone is signed in or not.
 *
 * Rows are only closed after [staleMinutes] of complete silence: a live turn
 * writes messages and tool results continuously, so a live conversation is never
 * touched.
 */
object AgentSessionSweeper {

    private val logger = LogManager.getLogger(AgentSessionSweeper::class.java)

    private const val DEFAULT_INTERVAL_MINUTES = 5L
    private const val DEFAULT_STALE_MINUTES = 15L

    /** Interval between passes; 0 disables the sweeper. */
    private fun intervalMinutes(): Long =
        System.getenv("AKIBA_AGENT_SWEEP_INTERVAL_MINUTES")?.trim()?.toLongOrNull()
            ?: DEFAULT_INTERVAL_MINUTES

    /** How long a row may sit unchanged before it is treated as orphaned. */
    private fun staleMinutes(): Long =
        System.getenv("AKIBA_AGENT_SWEEP_STALE_MINUTES")?.trim()?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?: DEFAULT_STALE_MINUTES

    fun start(dbConfig: ServerDbConfig) {
        val interval = intervalMinutes()
        if (interval <= 0L) {
            logger.info("AkibaServer: session sweeper disabled (AKIBA_AGENT_SWEEP_INTERVAL_MINUTES=$interval)")
            return
        }
        val stale = staleMinutes()
        logger.info(
            "AkibaServer: session sweeper every {} min, closing rows quiet for {} min",
            interval, stale,
        )
        thread(name = "akiba-session-sweeper", isDaemon = true) {
            while (true) {
                try {
                    Thread.sleep(interval * 60_000L)
                    sweep(dbConfig, stale)
                } catch (e: InterruptedException) {
                    return@thread
                } catch (e: Exception) {
                    logger.warn("AkibaServer: session sweep failed: {}", e.message)
                }
            }
        }
    }

    private fun sweep(dbConfig: ServerDbConfig, staleMinutes: Long) {
        for (database in instanceDatabases(dbConfig)) {
            try {
                connect(dbConfig, database).use { conn ->
                    val closed = closeStaleRows(conn, staleMinutes)
                    if (closed > 0) {
                        logger.info(
                            "AkibaServer: sweep of '{}' closed {} stale session(s)", database, closed,
                        )
                    }
                }
            } catch (e: Exception) {
                // A database dropped between the listing and the connect is not an event.
                logger.debug("AkibaServer: sweep of '{}' skipped: {}", database, e.message)
            }
        }
    }

    /** Every per-instance database the deployment has; instances are databases, not schemas. */
    private fun instanceDatabases(dbConfig: ServerDbConfig): List<String> {
        val sql = "SELECT datname FROM pg_database WHERE datname LIKE 'inst\\_%\\_instance'"
        return connect(dbConfig, dbConfig.dbName).use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }
    }

    private fun closeStaleRows(conn: Connection, staleMinutes: Long): Int {
        val sql = """
            UPDATE agent_sessions
               SET status = 'closed',
                   runtime_state = 'closed',
                   closing_reason = 'reconciled:session_sweep:' ||
                       COALESCE(NULLIF(runtime_state, ''), status) || ':' || EXTRACT(EPOCH FROM now())::bigint,
                   updated_at = now()
             WHERE (runtime_state IN ('running', 'msghandle', 'cancelling')
                    OR status IN ('running', 'msghandle', 'cancelling'))
               AND updated_at < now() - (? * INTERVAL '1 minute')
        """.trimIndent()
        return conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, staleMinutes)
            ps.executeUpdate()
        }
    }

    private fun connect(dbConfig: ServerDbConfig, database: String): Connection =
        DriverManager.getConnection(
            "jdbc:postgresql://${dbConfig.host}:${dbConfig.port}/$database",
            dbConfig.user,
            dbConfig.password,
        )
}
