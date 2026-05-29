package org.iotsplab.akiba.server.db

import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection

data class ServerDbConfig(
    val host: String,
    val port: Int,
    val dbName: String,
    val user: String,
    val password: String
)

object ServerDatabase {
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
    }

    fun getConnection(): Connection = connection

    fun close() {
        connection.close()
    }
}
