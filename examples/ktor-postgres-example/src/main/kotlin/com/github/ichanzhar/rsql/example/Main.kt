package com.github.ichanzhar.rsql.example

import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import javax.sql.DataSource
import org.babyfish.jimmer.sql.dialect.PostgresDialect
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.newKSqlClient
import org.babyfish.jimmer.sql.runtime.ConnectionManager
import org.babyfish.jimmer.sql.runtime.Executor

fun main() {
    val dataSource =
        HikariDataSource().apply {
            jdbcUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/rsql_example"
            username = System.getenv("DATABASE_USER") ?: "postgres"
            password = System.getenv("DATABASE_PASSWORD") ?: "postgres"
        }
    executeSchema(dataSource)
    val sqlClient = buildSqlClient(dataSource)
    embeddedServer(Netty, port = 8080) { bookModule(sqlClient) }.start(wait = true)
}

fun buildSqlClient(
    dataSource: DataSource,
    executor: Executor? = null,
): KSqlClient =
    newKSqlClient {
        setConnectionManager(ConnectionManager.simpleConnectionManager(dataSource))
        setDialect(PostgresDialect())
        executor?.let { setExecutor(it) }
    }

fun executeSchema(dataSource: DataSource) {
    val schema =
        object {}.javaClass.getResource("/schema.sql")?.readText()
            ?: error("schema.sql not found on classpath")
    dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            schema.split(';').map(String::trim).filter(String::isNotEmpty).forEach(statement::execute)
        }
    }
}
