package com.github.ichanzhar.rsql.jimmer

import com.github.ichanzhar.rsql.jimmer.exception.UnknownPropertyException
import com.github.ichanzhar.rsql.jimmer.exception.UnsupportedSelectorException
import org.babyfish.jimmer.meta.EmbeddedLevel
import org.babyfish.jimmer.meta.ImmutableType
import org.babyfish.jimmer.meta.TargetLevel
import org.babyfish.jimmer.sql.ast.impl.table.TableSelection
import org.babyfish.jimmer.sql.kt.ast.expression.KEmbeddedPropExpression
import org.babyfish.jimmer.sql.kt.ast.expression.KPropExpression
import org.babyfish.jimmer.sql.kt.ast.table.KProps

internal object SelectorResolver {
    fun resolve(
        table: KProps<*>,
        selector: String,
    ): ResolvedSelector.Scalar = resolveFromTable(table, selector.split('.'), selector)

    private tailrec fun resolveFromTable(
        table: KProps<*>,
        tokens: List<String>,
        selector: String,
    ): ResolvedSelector.Scalar {
        val type = (table as TableSelection).immutableType
        val token = tokens.first()
        val prop =
            type.props[token]
                ?: throw UnknownPropertyException(selector, token, type.javaClass.simpleName)
        val terminal = tokens.size == 1
        return when {
            prop.isReferenceList(TargetLevel.ENTITY) || prop.isScalarList ->
                throw UnsupportedSelectorException(selector, "collection property '$token' is not supported yet")
            prop.isReference(TargetLevel.ENTITY) && terminal ->
                ResolvedSelector.Scalar(
                    expression = table.getAssociatedId(token),
                    prop = prop,
                    castTarget =
                        requireNotNull(prop.targetType) { "association '$token' has no target type" }
                            .idProp.returnClass,
                )
            prop.isReference(TargetLevel.ENTITY) ->
                resolveFromTable(table.outerJoin<Any>(token), tokens.drop(1), selector)
            prop.isEmbedded(EmbeddedLevel.SCALAR) && !terminal ->
                resolveFromExpression(
                    expression = table.get(token),
                    type = ImmutableType.get(prop.elementClass),
                    tokens = tokens.drop(1),
                    selector = selector,
                )
            terminal ->
                ResolvedSelector.Scalar(table.get(token), prop, prop.returnClass)
            else ->
                throw UnsupportedSelectorException(selector, "property '$token' is not navigable")
        }
    }

    private tailrec fun resolveFromExpression(
        expression: KPropExpression<Any>,
        type: ImmutableType,
        tokens: List<String>,
        selector: String,
    ): ResolvedSelector.Scalar {
        val token = tokens.first()
        val prop =
            type.props[token]
                ?: throw UnknownPropertyException(selector, token, type.javaClass.simpleName)

        @Suppress("UNCHECKED_CAST")
        val embedded =
            expression as? KEmbeddedPropExpression<Any>
                ?: throw UnsupportedSelectorException(selector, "property '$token' is not navigable")
        val child = embedded.get<Any>(prop)
        return when {
            tokens.size == 1 -> ResolvedSelector.Scalar(child, prop, prop.returnClass)
            prop.isEmbedded(EmbeddedLevel.SCALAR) ->
                resolveFromExpression(child, ImmutableType.get(prop.elementClass), tokens.drop(1), selector)
            else -> throw UnsupportedSelectorException(selector, "property '$token' is not navigable")
        }
    }
}
