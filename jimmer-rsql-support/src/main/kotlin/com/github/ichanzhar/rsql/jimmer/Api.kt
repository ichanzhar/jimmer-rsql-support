package com.github.ichanzhar.rsql.jimmer

import com.github.ichanzhar.rsql.jimmer.utils.RsqlParserFactory
import cz.jirutka.rsql.parser.ast.Node
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.KNonNullExpression
import org.babyfish.jimmer.sql.kt.ast.query.KConfigurableRootQuery
import org.babyfish.jimmer.sql.kt.ast.table.KProps
import kotlin.reflect.KClass

/**
 * Folds an RSQL AST node into a nullable Jimmer predicate, usable inside any `createQuery` block.
 *
 * Negation across collections follows EXISTS semantics: `reviews.rating!=5` matches
 * entities having at least one review whose rating differs from 5, not entities
 * having no review with rating 5.
 *
 * @throws JimmerRsqlSupportException for invalid operators or unresolvable selectors.
 */
public fun <E : Any> Node.toPredicate(table: KProps<E>): KNonNullExpression<Boolean>? =
    accept(JimmerRsqlVisitor(), table)

/**
 * Parses an RSQL string and returns a query with a where clause; sugar for parse + toPredicate + where + select.
 *
 * @throws JimmerRsqlSupportException for invalid operators or unresolvable selectors.
 * @throws RSQLParserException for parse errors.
 */
public fun <E : Any> KSqlClient.createRsqlQuery(
    entityType: KClass<E>,
    rsql: String,
    context: ParserContext? = null,
): KConfigurableRootQuery<E, E> =
    createQuery(entityType) {
        RsqlParserFactory
            .instance(context)
            .parse(rsql)
            .toPredicate(table)
            ?.let { where(it) }
        select(table)
    }
