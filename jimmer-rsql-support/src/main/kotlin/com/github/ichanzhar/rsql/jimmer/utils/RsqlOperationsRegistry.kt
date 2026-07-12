package com.github.ichanzhar.rsql.jimmer.utils

import com.github.ichanzhar.rsql.jimmer.RsqlOperation
import com.github.ichanzhar.rsql.jimmer.operations.EqualCiProcessor
import com.github.ichanzhar.rsql.jimmer.operations.EqualProcessor
import com.github.ichanzhar.rsql.jimmer.operations.GtProcessor
import com.github.ichanzhar.rsql.jimmer.operations.GteProcessor
import com.github.ichanzhar.rsql.jimmer.operations.InProcessor
import com.github.ichanzhar.rsql.jimmer.operations.IsEmptyProcessor
import com.github.ichanzhar.rsql.jimmer.operations.IsNullProcessor
import com.github.ichanzhar.rsql.jimmer.operations.LtProcessor
import com.github.ichanzhar.rsql.jimmer.operations.LteProcessor
import com.github.ichanzhar.rsql.jimmer.operations.NotEqualProcessor
import com.github.ichanzhar.rsql.jimmer.operations.NotInProcessor
import com.github.ichanzhar.rsql.jimmer.operations.Params
import com.github.ichanzhar.rsql.jimmer.operations.Processor
import cz.jirutka.rsql.parser.ast.ComparisonOperator
import java.util.concurrent.ConcurrentHashMap

public typealias ProcessorParamsBuilder = (Params) -> Processor

public object RsqlOperationsRegistry {
    private val processors: ConcurrentHashMap<ComparisonOperator, ProcessorParamsBuilder> =
        ConcurrentHashMap(
            mapOf<ComparisonOperator, ProcessorParamsBuilder>(
                RsqlOperation.EQUAL.operator to { EqualProcessor(it) },
                RsqlOperation.NOT_EQUAL.operator to { NotEqualProcessor(it) },
                RsqlOperation.GREATER_THAN.operator to { GtProcessor(it) },
                RsqlOperation.GREATER_THAN_OR_EQUAL.operator to { GteProcessor(it) },
                RsqlOperation.LESS_THAN.operator to { LtProcessor(it) },
                RsqlOperation.LESS_THAN_OR_EQUAL.operator to { LteProcessor(it) },
                RsqlOperation.IN.operator to { InProcessor(it) },
                RsqlOperation.NOT_IN.operator to { NotInProcessor(it) },
                RsqlOperation.IS_NULL.operator to { IsNullProcessor(it) },
                RsqlOperation.EQUAL_CI.operator to { EqualCiProcessor(it) },
                RsqlOperation.IS_EMPTY.operator to { IsEmptyProcessor(it) },
            ),
        )

    public val operationProcessors: Map<ComparisonOperator, ProcessorParamsBuilder>
        get() = processors.toMap()

    public val operations: Set<ComparisonOperator>
        get() = processors.keys.toSet()

    internal fun processorFor(operator: ComparisonOperator): ProcessorParamsBuilder? = processors[operator]

    public fun registerOperation(
        operator: ComparisonOperator,
        processor: ProcessorParamsBuilder,
    ) {
        processors[operator] = processor
    }

    public fun initDefaultPostgresOperation() {
        registerOperation(RsqlOperation.JSON_EQ.operator, futureStub("=jsoneq=", "phase 4"))
        registerOperation(RsqlOperation.JSONB_EQ.operator, futureStub("=jsonbeq=", "phase 4"))
    }

    private fun futureStub(
        symbol: String,
        phase: String,
    ): ProcessorParamsBuilder = { Processor { TODO("Processor for '$symbol' arrives in $phase") } }
}
