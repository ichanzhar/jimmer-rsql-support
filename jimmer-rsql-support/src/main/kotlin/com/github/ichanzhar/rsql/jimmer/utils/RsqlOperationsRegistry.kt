package com.github.ichanzhar.rsql.jimmer.utils

import com.github.ichanzhar.rsql.jimmer.RsqlOperation
import com.github.ichanzhar.rsql.jimmer.operations.Params
import com.github.ichanzhar.rsql.jimmer.operations.Processor
import cz.jirutka.rsql.parser.ast.ComparisonOperator
import java.util.concurrent.ConcurrentHashMap

public typealias ProcessorParamsBuilder = (Params) -> Processor

public object RsqlOperationsRegistry {
    private val processors: ConcurrentHashMap<ComparisonOperator, ProcessorParamsBuilder> =
        ConcurrentHashMap(
            mapOf(
                RsqlOperation.EQUAL.operator to phase2Stub("=="),
                RsqlOperation.NOT_EQUAL.operator to phase2Stub("!="),
                RsqlOperation.GREATER_THAN.operator to phase2Stub(">"),
                RsqlOperation.GREATER_THAN_OR_EQUAL.operator to phase2Stub(">="),
                RsqlOperation.LESS_THAN.operator to phase2Stub("<"),
                RsqlOperation.LESS_THAN_OR_EQUAL.operator to phase2Stub("<="),
                RsqlOperation.IN.operator to phase2Stub("=in="),
                RsqlOperation.NOT_IN.operator to phase2Stub("=out="),
                RsqlOperation.IS_NULL.operator to phase2Stub("=isNull="),
                RsqlOperation.EQUAL_CI.operator to phase2Stub("=eqci="),
                RsqlOperation.IS_EMPTY.operator to phase2Stub("=isEmpty="),
            ),
        )

    public val operationProcessors: Map<ComparisonOperator, ProcessorParamsBuilder>
        get() = processors.toMap()

    public val operations: Set<ComparisonOperator>
        get() = processors.keys.toSet()

    public fun registerOperation(
        operator: ComparisonOperator,
        processor: ProcessorParamsBuilder,
    ) {
        processors[operator] = processor
    }

    public fun initDefaultPostgresOperation() {
        registerOperation(RsqlOperation.JSON_EQ.operator, phase2Stub("=jsoneq="))
        registerOperation(RsqlOperation.JSONB_EQ.operator, phase2Stub("=jsonbeq="))
    }

    private fun phase2Stub(symbol: String): ProcessorParamsBuilder =
        { Processor { TODO("Processor for '$symbol' arrives in phase 2") } }
}
