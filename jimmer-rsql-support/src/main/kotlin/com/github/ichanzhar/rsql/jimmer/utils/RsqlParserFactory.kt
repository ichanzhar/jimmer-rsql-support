package com.github.ichanzhar.rsql.jimmer.utils

import com.github.ichanzhar.rsql.jimmer.ParserContext
import cz.jirutka.rsql.parser.RSQLParser

public object RsqlParserFactory {
    public fun instance(context: ParserContext? = null): RSQLParser {
        if (context == ParserContext.POSTGRESQL) {
            RsqlOperationsRegistry.initDefaultPostgresOperation()
        }
        return RSQLParser(RsqlOperationsRegistry.operations.toSet())
    }
}
