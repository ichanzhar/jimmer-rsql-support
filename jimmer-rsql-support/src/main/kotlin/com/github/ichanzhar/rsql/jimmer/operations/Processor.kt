package com.github.ichanzhar.rsql.jimmer.operations

import org.babyfish.jimmer.sql.kt.ast.expression.KNonNullExpression

public fun interface Processor {
    public fun process(): KNonNullExpression<Boolean>?
}
