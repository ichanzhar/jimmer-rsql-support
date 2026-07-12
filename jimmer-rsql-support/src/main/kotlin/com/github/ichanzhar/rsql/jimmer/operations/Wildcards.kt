package com.github.ichanzhar.rsql.jimmer.operations

import com.github.ichanzhar.rsql.jimmer.exception.JimmerRsqlSupportException
import org.babyfish.jimmer.meta.ImmutableProp

internal fun isLikeExpression(argument: Any?): Boolean =
    (argument as? String)?.let { it.startsWith("*") || it.endsWith("*") } ?: false

internal fun likePattern(argument: Any?): String = argument.toString().replace('*', '%')

internal fun requireStringProperty(prop: ImmutableProp) {
    if (prop.returnClass != String::class.java) {
        throw JimmerRsqlSupportException(
            "wildcard '*' requires a string property, '${prop.name}' is '${prop.returnClass.simpleName}'",
        )
    }
}
