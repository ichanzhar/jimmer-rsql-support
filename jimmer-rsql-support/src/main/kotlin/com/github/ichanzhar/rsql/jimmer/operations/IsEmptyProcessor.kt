package com.github.ichanzhar.rsql.jimmer.operations

import com.github.ichanzhar.rsql.jimmer.exception.JimmerRsqlSupportException
import org.babyfish.jimmer.sql.kt.ast.expression.KNonNullExpression
import org.babyfish.jimmer.sql.kt.ast.expression.not

internal class IsEmptyProcessor(
    private val params: Params,
) : Processor {
    override fun process(): KNonNullExpression<Boolean>? {
        if (params.expression != null) {
            throw JimmerRsqlSupportException(
                "'=isEmpty=' applies only to collection properties, '${params.prop.name}' is not one",
            )
        }
        val exists = params.table.exists<Any>(params.prop.name) { null }
        return if (params.argument.toString().toBoolean()) exists?.not() else exists
    }
}
