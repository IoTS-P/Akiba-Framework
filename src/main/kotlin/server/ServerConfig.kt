package org.iotsplab.akiba.server

data class ServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val jwtSecret: String = "change-me-in-production-use-long-random-string",
    val dbHost: String = "127.0.0.1",
    val dbPort: Int = 5432,
    val dbName: String = "akiba_users",
    val dbUser: String = "akiba",
    val dbPassword: String = "akiba",
    val daemonHost: String = "127.0.0.1",
    val daemonPort: Int = 31777
)