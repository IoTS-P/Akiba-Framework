package org.iotsplab.akiba.server.db

import java.sql.Connection
import java.sql.SQLException
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.postgresql.ds.PGSimpleDataSource

data class ServerDbConfig(
    val host: String,
    val port: Int,
    val dbName: String,
    val user: String,
    val password: String
)

object ServerDatabase {
    private val logger: Logger = LogManager.getLogger("ServerDatabase")

    lateinit var config: ServerDbConfig
    private lateinit var dataSource: PGSimpleDataSource
    private lateinit var connection: Connection

    fun init(config: ServerDbConfig) {
        this.config = config
        dataSource = PGSimpleDataSource().apply {
            setServerName(config.host)
            setPortNumber(config.port)
            setDatabaseName(config.dbName)
            user = config.user
            setPassword(config.password)
        }
        connection = dataSource.connection
        initSchema()
    }

    /**
     * Initialize the server-level schema (the default DB on port 5432).
     *
     * This DB stores **user accounts and sessions** — data that is shared
     * across all per-user PG instances.  Script / script-execution tables
     * live inside each per-user instance and are created by
     * `akiba_db_daemon` (see `agent_database_init.sql`).
     */
    private fun initSchema() {
        connection.createStatement().use { stmt ->
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id SERIAL PRIMARY KEY,
                    username VARCHAR(255) UNIQUE NOT NULL,
                    password_hash VARCHAR(255) NOT NULL,
                    is_admin BOOLEAN NOT NULL DEFAULT FALSE,
                    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
                    last_failed_login_at TIMESTAMPTZ,
                    locked_until TIMESTAMPTZ,
                    created_at TIMESTAMPTZ DEFAULT now()
                )
            """.trimIndent())

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS user_sessions (
                    id SERIAL PRIMARY KEY,
                    user_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
                    token VARCHAR(512) UNIQUE NOT NULL,
                    created_at TIMESTAMPTZ DEFAULT now(),
                    expires_at TIMESTAMPTZ NOT NULL
                )
            """.trimIndent())
        }

        ensureSchemaUpgrades()
    }

    /**
     * Add the columns that newer versions need to databases created by older
     * ones.
     *
     * `ALTER TABLE` requires **ownership** of the table, and deployments created
     * by older images had `users` built by the `postgres` superuser (the
     * entrypoint used to run the DDL itself), which made the server die at
     * start-up with `must be owner of table users`. Two consequences:
     *
     *  - a column is only added when it is actually missing (so a normal start
     *    performs no DDL at all), and
     *  - an ownership failure is reported as one actionable line naming the exact
     *    SQL to run, instead of a bare stack trace.
     *
     * `entrypoint.sh` additionally hands ownership of the pre-existing tables to
     * the role the server connects with, which is what makes upgrades work
     * without operator intervention.
     */
    private fun ensureSchemaUpgrades() {
        // Administrators (older deployments had a single, equal-rights user table).
        ensureColumn("is_admin", "BOOLEAN NOT NULL DEFAULT FALSE")
        // Brute-force protection state (see LoginProtection).
        ensureColumn("failed_login_attempts", "INTEGER NOT NULL DEFAULT 0")
        ensureColumn("last_failed_login_at", "TIMESTAMPTZ")
        ensureColumn("locked_until", "TIMESTAMPTZ")
    }

    private fun ensureColumn(column: String, definition: String) {
        val hasColumn = connection.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT 1 FROM information_schema.columns WHERE table_schema = 'public'" +
                    " AND table_name = 'users' AND column_name = '$column'"
            ).use { rs -> rs.next() }
        }
        if (hasColumn) return

        try {
            connection.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE users ADD COLUMN $column $definition")
            }
            logger.info("Added the missing 'users.$column' column")
        } catch (e: SQLException) {
            logger.error("Could not add 'users.$column': ${e.message}")
            throw IllegalStateException(
                "The 'users' table has no '$column' column and it cannot be added by the " +
                    "role '${config.user}' (SQLState ${e.sqlState}: ${e.message}).\n" +
                    "Run the following as a PostgreSQL superuser, then start Akiba again:\n" +
                    "  ALTER TABLE users OWNER TO ${config.user};",
                e
            )
        }
    }

    fun getConnection(): Connection = connection

    fun close() {
        connection.close()
    }
}
