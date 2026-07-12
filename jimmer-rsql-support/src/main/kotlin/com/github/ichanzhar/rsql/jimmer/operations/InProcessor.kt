package com.github.ichanzhar.rsql.jimmer.operations

import org.babyfish.jimmer.sql.kt.ast.expression.KNonNullExpression
import org.babyfish.jimmer.sql.kt.ast.expression.valueIn

internal class InProcessor(
    private val params: Params,
) : Processor {
    override fun process(): KNonNullExpression<Boolean> {
        val expression = requireNotNull(params.expression) { "'=in=' requires a property expression" }
        return expression.valueIn(params.args)
    }
}
