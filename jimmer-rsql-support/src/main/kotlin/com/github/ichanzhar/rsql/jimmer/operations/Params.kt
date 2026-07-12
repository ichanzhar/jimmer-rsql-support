package com.github.ichanzhar.rsql.jimmer.operations

import org.babyfish.jimmer.meta.ImmutableProp
import org.babyfish.jimmer.sql.kt.ast.expression.KPropExpression

public data class Params(
    public val expression: KPropExpression<Any>?,
    public val prop: ImmutableProp,
    public val args: List<Any>,
    public val argument: Any?,
)
