package com.github.ichanzhar.rsql.jimmer.operations

import org.babyfish.jimmer.sql.ast.LikeMode
import org.babyfish.jimmer.sql.kt.ast.expression.KExpression
import org.babyfish.jimmer.sql.kt.ast.expression.KNonNullExpression
import org.babyfish.jimmer.sql.kt.ast.expression.like
import org.babyfish.jimmer.sql.kt.ast.expression.ne
import org.babyfish.jimmer.sql.kt.ast.expression.not

internal class NotEqualProcessor(
    private val params: Params,
) : Processor {
    override fun process(): KNonNullExpression<Boolean> {
        val expression = requireNotNull(params.expression) { "'!=' requires a property expression" }
        @Suppress("UNCHECKED_CAST")
        return if (isLikeExpression(params.argument)) {
            requireStringProperty(params.prop)
            (expression as KExpression<String>).like(likePattern(params.argument), LikeMode.EXACT).not()
        } else {
            expression.ne(params.argument)
        }
    }
}
