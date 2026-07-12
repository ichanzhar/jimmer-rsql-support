package com.github.ichanzhar.rsql.jimmer.operations

internal fun isLikeExpression(argument: Any?): Boolean =
    (argument as? String)?.let { it.startsWith("*") || it.endsWith("*") } ?: false

internal fun likePattern(argument: Any?): String = argument.toString().replace('*', '%')
