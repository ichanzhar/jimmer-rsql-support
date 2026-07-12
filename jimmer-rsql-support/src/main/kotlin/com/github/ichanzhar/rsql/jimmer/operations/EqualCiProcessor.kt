package com.github.ichanzhar.rsql.jimmer.operations

import org.babyfish.jimmer.sql.ast.LikeMode
import org.babyfish.jimmer.sql.kt.ast.expression.KExpression
import org.babyfish.jimmer.sql.kt.ast.expression.KNonNullExpression
import org.babyfish.jimmer.sql.kt.ast.expression.ilike

internal class EqualCiProcessor(
    private val params: Params,
) : Processor {
    override fun process(): KNonNullExpression<Boolean> {
        val expression = requireNotNull(params.expression) { "'=eqci=' requires a property expression" }
        @Suppress("UNCHECKED_CAST")
        return (expression as KExpression<String>).ilike(params.argument.toString(), LikeMode.EXACT)
    }
}
