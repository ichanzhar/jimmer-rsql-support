package com.github.ichanzhar.rsql.jimmer.operations

import org.babyfish.jimmer.sql.kt.ast.expression.KNonNullExpression
import org.babyfish.jimmer.sql.kt.ast.expression.sql

internal class JsonEqualProcessor(
    private val params: Params,
) : Processor {
    override fun process(): KNonNullExpression<Boolean> {
        val expression = requireNotNull(params.expression) { "'=jsoneq=' requires a property expression" }
        val argument = parseJsonArgument("=jsoneq=", params.argument)
        val keyPlaceholders = argument.keys.joinToString(", ") { "%v" }
        return sql(Boolean::class, "json_extract_path_text(%e::json, $keyPlaceholders) = %v") {
            expression(expression)
            argument.keys.forEach { value(it) }
            value(argument.value)
        }
    }
}
