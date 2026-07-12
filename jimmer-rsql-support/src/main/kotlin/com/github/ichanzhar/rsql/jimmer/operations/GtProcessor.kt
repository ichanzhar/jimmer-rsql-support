package com.github.ichanzhar.rsql.jimmer.operations

import org.babyfish.jimmer.sql.kt.ast.expression.KExpression
import org.babyfish.jimmer.sql.kt.ast.expression.KNonNullExpression
import org.babyfish.jimmer.sql.kt.ast.expression.gt

internal class GtProcessor(
    private val params: Params,
) : Processor {
    override fun process(): KNonNullExpression<Boolean> {
        val expression = requireNotNull(params.expression) { "'>' requires a property expression" }
        @Suppress("UNCHECKED_CAST")
        return (expression as KExpression<Comparable<Any>>).gt(params.argument as Comparable<Any>)
    }
}
