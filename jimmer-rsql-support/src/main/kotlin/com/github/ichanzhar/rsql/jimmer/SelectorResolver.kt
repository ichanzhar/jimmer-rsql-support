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
        context: String = selector,
    ): ResolvedSelector = resolveFromTable(table, selector.split('.'), context)

    private fun resolveFromTable(
        table: KProps<*>,
        tokens: List<String>,
        context: String,
    ): ResolvedSelector {
        val type = (table as TableSelection).immutableType
        val token = tokens.first()
        val prop =
            type.props[token]
                ?: throw UnknownPropertyException(context, token, type.javaClass.simpleName)
        val terminal = tokens.size == 1
        return when {
            prop.isScalarList ->
                throw UnsupportedSelectorException(context, "scalar collection property '$token' is not queryable")
            prop.isReferenceList(TargetLevel.ENTITY) && terminal ->
                ResolvedSelector.CollectionTerminal(prop)
            prop.isReferenceList(TargetLevel.ENTITY) ->
                ResolvedSelector.CollectionStep(token, tokens.drop(1).joinToString("."))
            prop.isReference(TargetLevel.ENTITY) && terminal ->
                ResolvedSelector.Scalar(
                    expression = table.getAssociatedId(token),
                    prop = prop,
                    castTarget =
                        requireNotNull(prop.targetType) { "association '$token' has no target type" }
                            .idProp.returnClass,
                )
            prop.isReference(TargetLevel.ENTITY) ->
                resolveFromTable(table.outerJoin<Any>(token), tokens.drop(1), context)
            prop.isEmbedded(EmbeddedLevel.SCALAR) && !terminal ->
                resolveFromExpression(
                    expression = table.get(token),
                    type = ImmutableType.get(prop.elementClass),
                    tokens = tokens.drop(1),
                    context = context,
                )
            terminal ->
                ResolvedSelector.Scalar(table.get(token), prop, prop.returnClass)
            else ->
                throw UnsupportedSelectorException(context, "property '$token' is not navigable")
        }
    }

    private tailrec fun resolveFromExpression(
        expression: KPropExpression<Any>,
        type: ImmutableType,
        tokens: List<String>,
        context: String,
    ): ResolvedSelector.Scalar {
        val token = tokens.first()
        val prop =
            type.props[token]
                ?: throw UnknownPropertyException(context, token, type.javaClass.simpleName)

        @Suppress("UNCHECKED_CAST")
        val embedded =
            expression as? KEmbeddedPropExpression<Any>
                ?: throw UnsupportedSelectorException(context, "property '$token' is not navigable")
        val child = embedded.get<Any>(prop)
        return when {
            tokens.size == 1 -> ResolvedSelector.Scalar(child, prop, prop.returnClass)
            prop.isEmbedded(EmbeddedLevel.SCALAR) ->
                resolveFromExpression(child, ImmutableType.get(prop.elementClass), tokens.drop(1), context)
            else -> throw UnsupportedSelectorException(context, "property '$token' is not navigable")
        }
    }
}
