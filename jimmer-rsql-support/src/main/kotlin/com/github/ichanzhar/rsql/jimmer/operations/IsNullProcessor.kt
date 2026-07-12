package com.github.ichanzhar.rsql.jimmer.operations

import org.babyfish.jimmer.sql.kt.ast.expression.KNonNullExpression
import org.babyfish.jimmer.sql.kt.ast.expression.isNotNull
import org.babyfish.jimmer.sql.kt.ast.expression.isNull

internal class IsNullProcessor(
    private val params: Params,
) : Processor {
    override fun process(): KNonNullExpression<Boolean> {
        val expression = requireNotNull(params.expression) { "'=isNull=' requires a property expression" }
        return if (params.argument.toString().toBoolean()) expression.isNull() else expression.isNotNull()
    }
}
