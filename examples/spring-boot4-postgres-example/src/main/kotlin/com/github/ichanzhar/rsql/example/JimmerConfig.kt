package com.github.ichanzhar.rsql.example

import javax.sql.DataSource
import org.babyfish.jimmer.sql.dialect.PostgresDialect
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.newKSqlClient
import org.babyfish.jimmer.sql.runtime.ConnectionManager
import org.babyfish.jimmer.sql.runtime.Executor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class JimmerConfig {
    @Bean
    fun sqlClient(dataSource: DataSource, executorProvider: ObjectProvider<Executor>): KSqlClient = newKSqlClient {
        setConnectionManager(ConnectionManager.simpleConnectionManager(dataSource))
        setDialect(PostgresDialect())
        executorProvider.ifAvailable { setExecutor(it) }
    }
}
