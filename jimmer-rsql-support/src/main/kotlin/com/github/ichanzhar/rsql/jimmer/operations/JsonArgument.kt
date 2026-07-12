package com.github.ichanzhar.rsql.jimmer.operations

import com.github.ichanzhar.rsql.jimmer.exception.JimmerRsqlSupportException

internal data class JsonArgument(
    val keys: List<String>,
    val value: String,
)

internal fun parseJsonArgument(
    operatorSymbol: String,
    argument: Any?,
): JsonArgument {
    val raw =
        argument as? String
            ?: throw JimmerRsqlSupportException("'$operatorSymbol' expects <json.path>|<value>, got '$argument'")
    val separator = raw.indexOf('|')
    if (separator <= 0 || separator == raw.length - 1) {
        throw JimmerRsqlSupportException("'$operatorSymbol' expects <json.path>|<value>, got '$raw'")
    }
    val keys = raw.substring(0, separator).split('.')
    if (keys.any { it.isEmpty() }) {
        throw JimmerRsqlSupportException("'$operatorSymbol' expects <json.path>|<value>, got '$raw'")
    }
    return JsonArgument(keys, raw.substring(separator + 1))
}
