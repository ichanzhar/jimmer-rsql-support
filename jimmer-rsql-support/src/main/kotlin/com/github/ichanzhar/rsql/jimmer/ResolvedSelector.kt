package com.github.ichanzhar.rsql.jimmer

import org.babyfish.jimmer.meta.ImmutableProp
import org.babyfish.jimmer.sql.kt.ast.expression.KPropExpression

internal sealed interface ResolvedSelector {
    data class Scalar(
        val expression: KPropExpression<Any>,
        val prop: ImmutableProp,
        val castTarget: Class<out Any>,
    ) : ResolvedSelector

    data class CollectionStep(
        val token: String,
        val remainder: String,
    ) : ResolvedSelector

    data class CollectionTerminal(
        val prop: ImmutableProp,
    ) : ResolvedSelector
}
