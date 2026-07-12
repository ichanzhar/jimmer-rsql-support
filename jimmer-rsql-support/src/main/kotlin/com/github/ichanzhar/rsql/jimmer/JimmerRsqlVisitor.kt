package com.github.ichanzhar.rsql.jimmer

import com.github.ichanzhar.rsql.jimmer.exception.JimmerRsqlSupportException
import com.github.ichanzhar.rsql.jimmer.exception.UnsupportedSelectorException
import com.github.ichanzhar.rsql.jimmer.operations.Params
import com.github.ichanzhar.rsql.jimmer.utils.ArgumentConvertor
import com.github.ichanzhar.rsql.jimmer.utils.JavaTypeUtil
import com.github.ichanzhar.rsql.jimmer.utils.RsqlOperationsRegistry
import cz.jirutka.rsql.parser.ast.AndNode
import cz.jirutka.rsql.parser.ast.ComparisonNode
import cz.jirutka.rsql.parser.ast.OrNode
import cz.jirutka.rsql.parser.ast.RSQLVisitor
import org.babyfish.jimmer.meta.ImmutableProp
import org.babyfish.jimmer.sql.kt.ast.expression.KNonNullExpression
import org.babyfish.jimmer.sql.kt.ast.expression.KPropExpression
import org.babyfish.jimmer.sql.kt.ast.expression.and
import org.babyfish.jimmer.sql.kt.ast.expression.or
import org.babyfish.jimmer.sql.kt.ast.table.KProps

/**
 * Folds an RSQL AST into a nullable Jimmer predicate; stateless and thread-safe.
 * Collection associations resolve to implicit EXISTS subqueries, recursively for
 * nested paths.
 */
public class JimmerRsqlVisitor<E : Any> : RSQLVisitor<KNonNullExpression<Boolean>?, KProps<E>> {
    override fun visit(
        node: AndNode,
        table: KProps<E>,
    ): KNonNullExpression<Boolean>? = and(*node.children.mapNotNull { it.accept(this, table) }.toTypedArray())

    override fun visit(
        node: OrNode,
        table: KProps<E>,
    ): KNonNullExpression<Boolean>? = or(*node.children.mapNotNull { it.accept(this, table) }.toTypedArray())

    override fun visit(
        node: ComparisonNode,
        table: KProps<E>,
    ): KNonNullExpression<Boolean>? = predicate(table, node.selector, node, node.selector)

    private fun predicate(
        table: KProps<*>,
        selector: String,
        node: ComparisonNode,
        context: String,
    ): KNonNullExpression<Boolean>? =
        when (val resolved = SelectorResolver.resolve(table, selector, context)) {
            is ResolvedSelector.Scalar ->
                dispatch(resolved.expression, resolved.prop, resolved.castTarget, table, node, context)
            is ResolvedSelector.CollectionStep ->
                table.exists<Any>(resolved.token) { predicate(this, resolved.remainder, node, context) }
            is ResolvedSelector.CollectionTerminal ->
                if (node.operator == RsqlOperation.IS_EMPTY.operator) {
                    dispatch(
                        expression = null,
                        prop = resolved.prop,
                        castTarget = String::class.java,
                        table = table,
                        node = node,
                        context = context,
                    )
                } else {
                    throw UnsupportedSelectorException(
                        context,
                        "collection property '${resolved.prop.name}' supports only =isEmpty=",
                    )
                }
        }

    private fun dispatch(
        expression: KPropExpression<Any>?,
        prop: ImmutableProp,
        castTarget: Class<out Any>,
        table: KProps<*>,
        node: ComparisonNode,
        context: String,
    ): KNonNullExpression<Boolean>? {
        val target = JavaTypeUtil.getPropertyJavaType(castTarget)
        val args = node.arguments.map { ArgumentConvertor.castArgument(it, context, target) }
        val factory =
            RsqlOperationsRegistry.processorFor(node.operator)
                ?: throw JimmerRsqlSupportException("No processor registered for operator '${node.operator.symbol}'")
        return factory(Params(expression, prop, args, args.firstOrNull(), table)).process()
    }
}
