package com.github.ichanzhar.rsql.jimmer

import com.github.ichanzhar.rsql.jimmer.exception.JimmerRsqlSupportException
import com.github.ichanzhar.rsql.jimmer.operations.Params
import com.github.ichanzhar.rsql.jimmer.utils.ArgumentConvertor
import com.github.ichanzhar.rsql.jimmer.utils.JavaTypeUtil
import com.github.ichanzhar.rsql.jimmer.utils.RsqlOperationsRegistry
import cz.jirutka.rsql.parser.ast.AndNode
import cz.jirutka.rsql.parser.ast.ComparisonNode
import cz.jirutka.rsql.parser.ast.OrNode
import cz.jirutka.rsql.parser.ast.RSQLVisitor
import org.babyfish.jimmer.sql.kt.ast.expression.KNonNullExpression
import org.babyfish.jimmer.sql.kt.ast.expression.and
import org.babyfish.jimmer.sql.kt.ast.expression.or
import org.babyfish.jimmer.sql.kt.ast.table.KProps

/**
 * Folds an RSQL AST into a nullable Jimmer predicate; stateless and thread-safe.
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
    ): KNonNullExpression<Boolean>? {
        val resolved = SelectorResolver.resolve(table, node.selector)
        val castTarget = JavaTypeUtil.getPropertyJavaType(resolved.castTarget)
        val args = node.arguments.map { ArgumentConvertor.castArgument(it, node.selector, castTarget) }
        val factory =
            RsqlOperationsRegistry.operationProcessors[node.operator]
                ?: throw JimmerRsqlSupportException("No processor registered for operator '${node.operator.symbol}'")
        return factory(Params(resolved.expression, resolved.prop, args, args.firstOrNull())).process()
    }
}
