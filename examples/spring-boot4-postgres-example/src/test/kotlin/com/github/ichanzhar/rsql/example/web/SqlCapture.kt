package com.github.ichanzhar.rsql.example.web

import java.sql.Connection
import java.util.concurrent.CopyOnWriteArrayList
import org.babyfish.jimmer.meta.ImmutableProp
import org.babyfish.jimmer.sql.runtime.DefaultExecutor
import org.babyfish.jimmer.sql.runtime.Executor
import org.babyfish.jimmer.sql.runtime.ExecutionPurpose
import org.babyfish.jimmer.sql.runtime.JSqlClientImplementor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

object CapturingExecutor : Executor {
    val statements = CopyOnWriteArrayList<String>()

    override fun <R> execute(args: Executor.Args<R>): R {
        statements.add(args.sql)
        return DefaultExecutor.INSTANCE.execute(args)
    }

    override fun executeBatch(
        con: Connection,
        sql: String,
        prop: ImmutableProp?,
        purpose: ExecutionPurpose,
        sqlClient: JSqlClientImplementor,
    ): Executor.BatchContext {
        statements.add(sql)
        return DefaultExecutor.INSTANCE.executeBatch(con, sql, prop, purpose, sqlClient)
    }
}

@TestConfiguration
class SqlCaptureConfig {
    @Bean
    fun capturingExecutor(): Executor = CapturingExecutor
}
