package com.github.ichanzhar.rsql.jimmer

import cz.jirutka.rsql.parser.ast.ComparisonOperator
import cz.jirutka.rsql.parser.ast.RSQLOperators

public enum class RsqlOperation(
    public val operator: ComparisonOperator,
    public val context: ParserContext,
) {
    /** Equality, `*` wildcards translate to SQL LIKE: `title==Kotlin`, `title==*SQL*`. */
    EQUAL(RSQLOperators.EQUAL, ParserContext.COMMON),

    /** Inequality, `*` wildcards translate to SQL NOT LIKE: `title!=Kotlin`, `title!=*SQL*`. */
    NOT_EQUAL(RSQLOperators.NOT_EQUAL, ParserContext.COMMON),

    /** Greater than: `year>2000`. */
    GREATER_THAN(RSQLOperators.GREATER_THAN, ParserContext.COMMON),

    /** Greater than or equal: `year>=2000`. */
    GREATER_THAN_OR_EQUAL(RSQLOperators.GREATER_THAN_OR_EQUAL, ParserContext.COMMON),

    /** Less than: `year<2000`. */
    LESS_THAN(RSQLOperators.LESS_THAN, ParserContext.COMMON),

    /** Less than or equal: `year<=2000`. */
    LESS_THAN_OR_EQUAL(RSQLOperators.LESS_THAN_OR_EQUAL, ParserContext.COMMON),

    /** Membership: `year=in=(2000,2001)`. */
    IN(RSQLOperators.IN, ParserContext.COMMON),

    /** Exclusion: `year=out=(2000,2001)`. */
    NOT_IN(RSQLOperators.NOT_IN, ParserContext.COMMON),

    /** Null check: `rating=isNull=true` or `rating=isNull=false`. */
    IS_NULL(ComparisonOperator("=isNull="), ParserContext.COMMON),

    /** Case insensitive equality: `lastName=eqci=smith`. */
    EQUAL_CI(ComparisonOperator("=eqci="), ParserContext.COMMON),

    /** Empty collection check: `reviews=isEmpty=true` or `reviews=isEmpty=false`. */
    IS_EMPTY(ComparisonOperator("=isEmpty="), ParserContext.COMMON),

    /** PostgreSQL jsonb path equality, argument is `path|value`: `attributes=jsonbeq=color|red`. */
    JSONB_EQ(ComparisonOperator("=jsonbeq="), ParserContext.POSTGRESQL),

    /** PostgreSQL json path equality, argument is `path|value`: `attributes=jsoneq=color|red`. */
    JSON_EQ(ComparisonOperator("=jsoneq="), ParserContext.POSTGRESQL),
    ;

    public companion object {
        public fun from(operator: ComparisonOperator): RsqlOperation? = entries.firstOrNull { it.operator === operator }
    }
}
