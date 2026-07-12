# Phase 2: Resolver + Scalar Processors + Spring Boot 4 Example Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** RSQL strings become executable Jimmer predicates for scalar, reference-join, and embedded selectors, proven by a Spring Boot 4 + Postgres example with Testcontainers integration tests.

**Architecture:** `SelectorResolver` (internal, stateless) walks dotted selectors against `ImmutableType`/`ImmutableProp` metadata into a sealed `ResolvedSelector.Scalar`; `JimmerRsqlVisitor` folds the rsql-parser AST via null-ignoring `and`/`or` and dispatches processors from the registry; `Api.kt` exposes `Node.toPredicate` and `KSqlClient.createRsqlQuery`. The example is an independent Gradle build consuming the library via `includeBuild`. Spec: `docs/superpowers/specs/2026-07-12-phase2-resolver-processors-design.md` (its "Verified Jimmer 0.9.96 API facts" section is normative for every signature used below).

**Tech Stack:** Kotlin 2.3.20/JDK 21, jimmer-sql-kotlin 0.9.96 (compileOnly in library), rsql-parser 2.1.0; example: Spring Boot 4.1.0, jimmer-spring-boot-starter 0.9.96, KSP plugin 2.3.10, Testcontainers postgres:16-alpine.

## Global Constraints

- Library tasks (1-4) run from the repo root `/Users/user/IdeaProjects/jimmer-rsql-support`; cycle is `./gradlew apiDump build` green (compile + ktlintCheck + apiCheck), commit includes `jimmer-rsql-support/api/jimmer-rsql-support.api` when it changed.
- Example tasks (5-7) run from `examples/spring-boot4-postgres-example` with its own wrapper; Docker required for tasks 6-7.
- The library module has no test source set (parent spec rule); classic TDD applies only to the example's integration tests. Library verification is compile + the example suite.
- `explicitApi()` strict in the library: explicit `public`/`internal` and return types everywhere.
- If ktlintCheck fails on formatting, run `./gradlew ktlintFormat` and re-run; never hand-format.
- No comments in code; KDoc on public API only. Plain hyphens, no em dashes. No wildcard imports.
- No Spring dependencies in the library. The example may use anything.
- `@Suppress("UNCHECKED_CAST")` at the smallest scope for the casts the spec mandates; no `!!` anywhere; `requireNotNull(x) { message }` for contract guarantees.
- Commit messages end with:

```
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
```

---

### Task 1: Phase 2 exceptions

**Files:**
- Create: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/exception/UnknownPropertyException.kt`
- Create: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/exception/UnsupportedSelectorException.kt`

**Interfaces:**
- Consumes: `JimmerRsqlSupportException(message: String)` base (Phase 1)
- Produces: `UnknownPropertyException(selector: String, property: String, entityName: String)` and `UnsupportedSelectorException(selector: String, reason: String)` - thrown by Task 2's resolver

- [ ] **Step 1: Write `UnknownPropertyException.kt`**

```kotlin
package com.github.ichanzhar.rsql.jimmer.exception

public class UnknownPropertyException(selector: String, property: String, entityName: String) :
    JimmerRsqlSupportException("Unknown property '$property' in selector '$selector' on entity '$entityName'")
```

- [ ] **Step 2: Write `UnsupportedSelectorException.kt`**

```kotlin
package com.github.ichanzhar.rsql.jimmer.exception

public class UnsupportedSelectorException(selector: String, reason: String) :
    JimmerRsqlSupportException("Unsupported selector '$selector': $reason")
```

- [ ] **Step 3: Dump API and build**

Run: `./gradlew apiDump build`
Expected: BUILD SUCCESSFUL; api dump gains the two classes.

- [ ] **Step 4: Commit**

```bash
git add jimmer-rsql-support/src jimmer-rsql-support/api
git commit -m "feat: UnknownPropertyException and UnsupportedSelectorException"
```

---

### Task 2: SelectorResolver

**Files:**
- Create: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/ResolvedSelector.kt`
- Create: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/SelectorResolver.kt`

**Interfaces:**
- Consumes: Task 1 exceptions; Jimmer 0.9.96 verified APIs: `KProps.get<X>(String)`, `KProps.getAssociatedId<X>(String)`, `KProps.outerJoin<X>(String): KNullableTable<X>`, `(kProps as TableSelection).immutableType`, `ImmutableType.props: Map<String, ImmutableProp>`, `ImmutableType.get(Class<*>)`, `ImmutableProp.isReference/isReferenceList(TargetLevel.ENTITY)`, `isEmbedded(EmbeddedLevel.SCALAR)`, `isScalarList`, `returnClass`, `targetType`, `elementClass`, `KEmbeddedPropExpression.get(prop: ImmutableProp)`
- Produces: `internal object SelectorResolver { fun resolve(table: KProps<*>, selector: String): ResolvedSelector.Scalar }` and `internal sealed interface ResolvedSelector` with `data class Scalar(val expression: KPropExpression<Any>, val prop: ImmutableProp, val castTarget: Class<out Any>)` - consumed by Task 4's visitor. Both types are internal: the api dump must NOT change in this task.

- [ ] **Step 1: Write `ResolvedSelector.kt`**

```kotlin
package com.github.ichanzhar.rsql.jimmer

import org.babyfish.jimmer.meta.ImmutableProp
import org.babyfish.jimmer.sql.kt.ast.expression.KPropExpression

internal sealed interface ResolvedSelector {
    data class Scalar(
        val expression: KPropExpression<Any>,
        val prop: ImmutableProp,
        val castTarget: Class<out Any>,
    ) : ResolvedSelector
}
```

- [ ] **Step 2: Write `SelectorResolver.kt`**

Notes for the implementer, all verified against 0.9.96: `type.javaClass` resolves to the member `ImmutableType.getJavaClass()` (the entity class), not `Any.javaClass` - member wins over extension. `prop.targetType` is null for non-associations, hence the `requireNotNull` on the reference branch where it is contractually non-null. `ImmutableType.get(...)` is the static factory for the embeddable's metadata (embedded props are not associations, so `targetType` does not apply).

```kotlin
package com.github.ichanzhar.rsql.jimmer

import com.github.ichanzhar.rsql.jimmer.exception.UnknownPropertyException
import com.github.ichanzhar.rsql.jimmer.exception.UnsupportedSelectorException
import org.babyfish.jimmer.meta.EmbeddedLevel
import org.babyfish.jimmer.meta.ImmutableProp
import org.babyfish.jimmer.meta.ImmutableType
import org.babyfish.jimmer.meta.TargetLevel
import org.babyfish.jimmer.sql.ast.impl.table.TableSelection
import org.babyfish.jimmer.sql.kt.ast.expression.KEmbeddedPropExpression
import org.babyfish.jimmer.sql.kt.ast.expression.KPropExpression
import org.babyfish.jimmer.sql.kt.ast.table.KProps

internal object SelectorResolver {

    fun resolve(table: KProps<*>, selector: String): ResolvedSelector.Scalar =
        resolveFromTable(table, selector.split('.'), selector)

    private tailrec fun resolveFromTable(
        table: KProps<*>,
        tokens: List<String>,
        selector: String,
    ): ResolvedSelector.Scalar {
        val type = (table as TableSelection).immutableType
        val token = tokens.first()
        val prop = type.props[token]
            ?: throw UnknownPropertyException(selector, token, type.javaClass.simpleName)
        val terminal = tokens.size == 1
        return when {
            prop.isReferenceList(TargetLevel.ENTITY) || prop.isScalarList ->
                throw UnsupportedSelectorException(selector, "collection property '$token' is not supported yet")
            prop.isReference(TargetLevel.ENTITY) && terminal ->
                ResolvedSelector.Scalar(
                    expression = table.getAssociatedId(token),
                    prop = prop,
                    castTarget = requireNotNull(prop.targetType) { "association '$token' has no target type" }
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
        val prop = type.props[token]
            ?: throw UnknownPropertyException(selector, token, type.javaClass.simpleName)
        @Suppress("UNCHECKED_CAST")
        val embedded = expression as? KEmbeddedPropExpression<Any>
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
```

If the compiler rejects `tailrec` on `resolveFromTable` (the embedded branch calls a different function), drop the `tailrec` modifier - selectors are short, recursion depth is trivial.

- [ ] **Step 3: Dump API and build**

Run: `./gradlew apiDump build`
Expected: BUILD SUCCESSFUL. Confirm with `git status` that `jimmer-rsql-support/api/jimmer-rsql-support.api` is UNCHANGED (both types are internal); if it changed, a visibility modifier is wrong - fix it.

- [ ] **Step 4: Commit**

```bash
git add jimmer-rsql-support/src
git commit -m "feat: SelectorResolver for scalar, reference, and embedded selectors"
```

---

### Task 3: Processors and registry wiring

**Files:**
- Create: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/operations/Wildcards.kt`
- Create (one file each, same package `operations/`): `EqualProcessor.kt`, `NotEqualProcessor.kt`, `GtProcessor.kt`, `GteProcessor.kt`, `LtProcessor.kt`, `LteProcessor.kt`, `InProcessor.kt`, `NotInProcessor.kt`, `IsNullProcessor.kt`, `EqualCiProcessor.kt`
- Modify: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/utils/RsqlOperationsRegistry.kt`

**Interfaces:**
- Consumes: Phase 1 `Processor` fun interface (`process(): KNonNullExpression<Boolean>?`), `Params(expression: KPropExpression<Any>?, prop: ImmutableProp, args: List<Any>, argument: Any?)`, `ProcessorParamsBuilder = (Params) -> Processor`, `RsqlOperation`
- Produces: 10 `internal class XProcessor(private val params: Params) : Processor`. Registry rule (controller-resolved spec ambiguity): the Phase 1 `phase2Stub` helper is renamed to `futureStub(symbol: String, phase: String)`; 10 COMMON seeds become real factories; `IS_EMPTY` stays `futureStub("=isEmpty=", "phase 3")`; `initDefaultPostgresOperation` registers `futureStub("=jsoneq=", "phase 4")` and `futureStub("=jsonbeq=", "phase 4")`. Public API unchanged; api dump must not change.

- [ ] **Step 1: Write `Wildcards.kt`**

```kotlin
package com.github.ichanzhar.rsql.jimmer.operations

internal fun isLikeExpression(argument: Any?): Boolean =
    (argument as? String)?.let { it.startsWith("*") || it.endsWith("*") } ?: false

internal fun likePattern(argument: Any?): String = argument.toString().replace('*', '%')
```

- [ ] **Step 2: Write `EqualProcessor.kt`**

```kotlin
package com.github.ichanzhar.rsql.jimmer.operations

import org.babyfish.jimmer.sql.ast.LikeMode
import org.babyfish.jimmer.sql.kt.ast.expression.KExpression
import org.babyfish.jimmer.sql.kt.ast.expression.KNonNullExpression
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.like

internal class EqualProcessor(private val params: Params) : Processor {
    override fun process(): KNonNullExpression<Boolean> {
        val expression = requireNotNull(params.expression) { "'==' requires a property expression" }
        @Suppress("UNCHECKED_CAST")
        return if (isLikeExpression(params.argument)) {
            (expression as KExpression<String>).like(likePattern(params.argument), LikeMode.EXACT)
        } else {
            expression.eq(params.argument)
        }
    }
}
```

- [ ] **Step 3: Write `NotEqualProcessor.kt`**

```kotlin
package com.github.ichanzhar.rsql.jimmer.operations

import org.babyfish.jimmer.sql.ast.LikeMode
import org.babyfish.jimmer.sql.kt.ast.expression.KExpression
import org.babyfish.jimmer.sql.kt.ast.expression.KNonNullExpression
import org.babyfish.jimmer.sql.kt.ast.expression.like
import org.babyfish.jimmer.sql.kt.ast.expression.ne
import org.babyfish.jimmer.sql.kt.ast.expression.not

internal class NotEqualProcessor(private val params: Params) : Processor {
    override fun process(): KNonNullExpression<Boolean> {
        val expression = requireNotNull(params.expression) { "'!=' requires a property expression" }
        @Suppress("UNCHECKED_CAST")
        return if (isLikeExpression(params.argument)) {
            (expression as KExpression<String>).like(likePattern(params.argument), LikeMode.EXACT).not()
        } else {
            expression.ne(params.argument)
        }
    }
}
```

- [ ] **Step 4: Write the four comparison processors**

`GtProcessor.kt` (GteProcessor/LtProcessor/LteProcessor are identical except the operator function `ge`/`lt`/`le`, the class name, and the symbol in the message - write all four files):

```kotlin
package com.github.ichanzhar.rsql.jimmer.operations

import org.babyfish.jimmer.sql.kt.ast.expression.KExpression
import org.babyfish.jimmer.sql.kt.ast.expression.KNonNullExpression
import org.babyfish.jimmer.sql.kt.ast.expression.gt

internal class GtProcessor(private val params: Params) : Processor {
    override fun process(): KNonNullExpression<Boolean> {
        val expression = requireNotNull(params.expression) { "'>' requires a property expression" }
        @Suppress("UNCHECKED_CAST")
        return (expression as KExpression<Comparable<Any>>).gt(params.argument as Comparable<Any>)
    }
}
```

- [ ] **Step 5: Write `InProcessor.kt` and `NotInProcessor.kt`**

```kotlin
package com.github.ichanzhar.rsql.jimmer.operations

import org.babyfish.jimmer.sql.kt.ast.expression.KNonNullExpression
import org.babyfish.jimmer.sql.kt.ast.expression.valueIn

internal class InProcessor(private val params: Params) : Processor {
    override fun process(): KNonNullExpression<Boolean> {
        val expression = requireNotNull(params.expression) { "'=in=' requires a property expression" }
        return expression.valueIn(params.args)
    }
}
```

`NotInProcessor.kt`: same shape with `valueNotIn` import/call and message `"'=out=' requires a property expression"`.

- [ ] **Step 6: Write `IsNullProcessor.kt` and `EqualCiProcessor.kt`**

```kotlin
package com.github.ichanzhar.rsql.jimmer.operations

import org.babyfish.jimmer.sql.kt.ast.expression.KNonNullExpression
import org.babyfish.jimmer.sql.kt.ast.expression.isNotNull
import org.babyfish.jimmer.sql.kt.ast.expression.isNull

internal class IsNullProcessor(private val params: Params) : Processor {
    override fun process(): KNonNullExpression<Boolean> {
        val expression = requireNotNull(params.expression) { "'=isNull=' requires a property expression" }
        return if (params.argument.toString().toBoolean()) expression.isNull() else expression.isNotNull()
    }
}
```

```kotlin
package com.github.ichanzhar.rsql.jimmer.operations

import org.babyfish.jimmer.sql.ast.LikeMode
import org.babyfish.jimmer.sql.kt.ast.expression.KExpression
import org.babyfish.jimmer.sql.kt.ast.expression.KNonNullExpression
import org.babyfish.jimmer.sql.kt.ast.expression.ilike

internal class EqualCiProcessor(private val params: Params) : Processor {
    override fun process(): KNonNullExpression<Boolean> {
        val expression = requireNotNull(params.expression) { "'=eqci=' requires a property expression" }
        @Suppress("UNCHECKED_CAST")
        return (expression as KExpression<String>).ilike(params.argument.toString(), LikeMode.EXACT)
    }
}
```

- [ ] **Step 7: Rewire the registry**

Replace the seed map and stub helper in `RsqlOperationsRegistry.kt` so the object body becomes:

```kotlin
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
                RsqlOperation.IS_EMPTY.operator to futureStub("=isEmpty=", "phase 3"),
            ),
        )

    public val operationProcessors: Map<ComparisonOperator, ProcessorParamsBuilder>
        get() = processors.toMap()

    public val operations: Set<ComparisonOperator>
        get() = processors.keys.toSet()

    public fun registerOperation(operator: ComparisonOperator, processor: ProcessorParamsBuilder) {
        processors[operator] = processor
    }

    public fun initDefaultPostgresOperation() {
        registerOperation(RsqlOperation.JSON_EQ.operator, futureStub("=jsoneq=", "phase 4"))
        registerOperation(RsqlOperation.JSONB_EQ.operator, futureStub("=jsonbeq=", "phase 4"))
    }

    private fun futureStub(symbol: String, phase: String): ProcessorParamsBuilder =
        { Processor { TODO("Processor for '$symbol' arrives in $phase") } }
}
```

Keep the existing imports plus the new processor imports (`EqualProcessor` etc. are in `com.github.ichanzhar.rsql.jimmer.operations`, already imported via the Phase 1 `Params`/`Processor` imports' package).

- [ ] **Step 8: Dump API and build**

Run: `./gradlew apiDump build`
Expected: BUILD SUCCESSFUL; api dump UNCHANGED (all new classes internal, registry surface identical). Verify with `git status`.

- [ ] **Step 9: Commit**

```bash
git add jimmer-rsql-support/src
git commit -m "feat: scalar operator processors wired into the registry"
```

---

### Task 4: JavaTypeUtil amendment, visitor, and public API

**Files:**
- Modify: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/utils/JavaTypeUtil.kt`
- Create: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/JimmerRsqlVisitor.kt`
- Create: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/Api.kt`

**Interfaces:**
- Consumes: `SelectorResolver.resolve(table, selector): ResolvedSelector.Scalar` (Task 2), registry factories (Task 3), Phase 1 `ArgumentConvertor.castArgument(arg, property, javaType)`, `JavaTypeUtil.getPropertyJavaType(Class<out Any>?)`, `RsqlParserFactory.instance(context)`
- Produces: `public class JimmerRsqlVisitor<E : Any> : RSQLVisitor<KNonNullExpression<Boolean>?, KProps<E>>`; `public fun <E : Any> Node.toPredicate(table: KProps<E>): KNonNullExpression<Boolean>?`; `public fun <E : Any> KSqlClient.createRsqlQuery(entityType: KClass<E>, rsql: String, context: ParserContext? = null): KConfigurableRootQuery<E, E>`. Tasks 6-7 call `createRsqlQuery`.

- [ ] **Step 1: Extend `JavaTypeUtil`'s map with boxed wrapper names**

Justified deviation per spec (Jimmer does not coerce string literals like Hibernate's criteria API). Add these seven entries to the existing `primitiveWrappers` map, keeping all existing entries untouched:

```kotlin
        "java.lang.Integer" to Int::class.java,
        "java.lang.Long" to Long::class.java,
        "java.lang.Double" to Double::class.java,
        "java.lang.Float" to Float::class.java,
        "java.lang.Short" to Short::class.java,
        "java.lang.Byte" to Byte::class.java,
        "java.lang.Character" to Char::class.java,
```

The values are the Kotlin primitive class literals that `ArgumentConvertor.castArgument`'s `when` branches compare against; `castArgument` itself stays untouched.

- [ ] **Step 2: Write `JimmerRsqlVisitor.kt`**

```kotlin
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

public class JimmerRsqlVisitor<E : Any> : RSQLVisitor<KNonNullExpression<Boolean>?, KProps<E>> {

    override fun visit(node: AndNode, table: KProps<E>): KNonNullExpression<Boolean>? =
        and(*node.children.mapNotNull { it.accept(this, table) }.toTypedArray())

    override fun visit(node: OrNode, table: KProps<E>): KNonNullExpression<Boolean>? =
        or(*node.children.mapNotNull { it.accept(this, table) }.toTypedArray())

    override fun visit(node: ComparisonNode, table: KProps<E>): KNonNullExpression<Boolean>? {
        val resolved = SelectorResolver.resolve(table, node.selector)
        val castTarget = JavaTypeUtil.getPropertyJavaType(resolved.castTarget)
        val args = node.arguments.map { ArgumentConvertor.castArgument(it, node.selector, castTarget) }
        val factory = RsqlOperationsRegistry.operationProcessors[node.operator]
            ?: throw JimmerRsqlSupportException("No processor registered for operator '${node.operator.symbol}'")
        return factory(Params(resolved.expression, resolved.prop, args, args.firstOrNull())).process()
    }
}
```

Add KDoc above the class: one sentence stating it folds an RSQL AST into a nullable Jimmer predicate and is stateless/thread-safe.

- [ ] **Step 3: Write `Api.kt`**

The `KConfigurableRootQuery` import is `org.babyfish.jimmer.sql.kt.ast.query.KConfigurableRootQuery`; if the compiler disagrees, check the actual package with `javap`/IDE against jimmer-sql-kotlin 0.9.96 and use that - the return type generics `<E, E>` are normative per the spec.

```kotlin
package com.github.ichanzhar.rsql.jimmer

import com.github.ichanzhar.rsql.jimmer.utils.RsqlParserFactory
import cz.jirutka.rsql.parser.ast.Node
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.KNonNullExpression
import org.babyfish.jimmer.sql.kt.ast.query.KConfigurableRootQuery
import org.babyfish.jimmer.sql.kt.ast.table.KProps
import kotlin.reflect.KClass

public fun <E : Any> Node.toPredicate(table: KProps<E>): KNonNullExpression<Boolean>? =
    accept(JimmerRsqlVisitor(), table)

public fun <E : Any> KSqlClient.createRsqlQuery(
    entityType: KClass<E>,
    rsql: String,
    context: ParserContext? = null,
): KConfigurableRootQuery<E, E> =
    createQuery(entityType) {
        RsqlParserFactory.instance(context).parse(rsql).toPredicate(table)?.let { where(it) }
        select(table)
    }
```

KDoc both functions: `toPredicate` is the primitive usable inside any `createQuery` block; `createRsqlQuery` is parse + where + select sugar; document thrown exception types (`JimmerRsqlSupportException` subclasses, `RSQLParserException` from `parse`).

- [ ] **Step 4: Dump API and build**

Run: `./gradlew apiDump build`
Expected: BUILD SUCCESSFUL; api dump gains `JimmerRsqlVisitor` and `ApiKt` entries.

- [ ] **Step 5: Commit**

```bash
git add jimmer-rsql-support/src jimmer-rsql-support/api
git commit -m "feat: RSQL visitor and public toPredicate/createRsqlQuery API"
```

---

### Task 5: Example scaffold, domain, schema (compiles with KSP, no DB needed)

**Files (all under `examples/spring-boot4-postgres-example/`):**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`
- Copy: `gradlew`, `gradlew.bat`, `gradle/` from the repo root
- Create: `src/main/kotlin/com/github/ichanzhar/rsql/example/ExampleApplication.kt`
- Create: `src/main/kotlin/com/github/ichanzhar/rsql/example/model/` - `Book.kt`, `Author.kt`, `Review.kt`, `Chapter.kt`, `Category.kt`, `Dimensions.kt`
- Create: `src/main/resources/application.yml`, `src/main/resources/schema.sql`

**Interfaces:**
- Consumes: the library via `includeBuild` dependency substitution
- Produces: Jimmer entity interfaces (KSP-generated draft/props classes) and the schema that Tasks 6-7 build on. Entity property names are normative: `Book.title/isbn/publicationYear/author/dimensions/reviews/chapters/categories`, `Author.name/email`, `Review.rating/comment/book`, `Chapter.sequence/title/book`, `Category.name/books`, `Dimensions.widthCm/heightCm/weightGrams`.

- [ ] **Step 1: Write `settings.gradle.kts`**

```kotlin
rootProject.name = "spring-boot4-postgres-example"

includeBuild("../..") {
    dependencySubstitution {
        substitute(module("com.github.ichanzhar:jimmer-rsql-support")).using(project(":jimmer-rsql-support"))
    }
}
```

- [ ] **Step 2: Write `build.gradle.kts`**

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("org.springframework.boot") version "4.1.0"
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.spring") version "2.3.20"
    id("com.google.devtools.ksp") version "2.3.10"
}

group = "com.github.ichanzhar.rsql.example"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.babyfish.jimmer:jimmer-spring-boot-starter:0.9.96")
    ksp("org.babyfish.jimmer:jimmer-ksp:0.9.96")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    implementation("com.github.ichanzhar:jimmer-rsql-support:0.1.0-SNAPSHOT")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.set(listOf("-Xjsr305=strict"))
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
```

`gradle.properties`:

```properties
kotlin.code.style=official
```

- [ ] **Step 3: Copy the wrapper**

```bash
cp /Users/user/IdeaProjects/jimmer-rsql-support/gradlew /Users/user/IdeaProjects/jimmer-rsql-support/gradlew.bat examples/spring-boot4-postgres-example/
cp -R /Users/user/IdeaProjects/jimmer-rsql-support/gradle examples/spring-boot4-postgres-example/
chmod +x examples/spring-boot4-postgres-example/gradlew
```

(Run from the repo root. The repo `.gitignore`'s `!gradle/wrapper/gradle-wrapper.jar` un-ignore rule applies repo-wide, so the example's wrapper jar is committed too - verify with `git status` that it shows up.)

- [ ] **Step 4: Write the domain**

`Dimensions.kt`:

```kotlin
package com.github.ichanzhar.rsql.example.model

import org.babyfish.jimmer.sql.Embeddable

@Embeddable
interface Dimensions {
    val widthCm: Double
    val heightCm: Double
    val weightGrams: Int
}
```

`Author.kt`:

```kotlin
package com.github.ichanzhar.rsql.example.model

import org.babyfish.jimmer.sql.Entity
import org.babyfish.jimmer.sql.GeneratedValue
import org.babyfish.jimmer.sql.GenerationType
import org.babyfish.jimmer.sql.Id

@Entity
interface Author {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long
    val name: String
    val email: String?
}
```

`Category.kt`:

```kotlin
package com.github.ichanzhar.rsql.example.model

import org.babyfish.jimmer.sql.Entity
import org.babyfish.jimmer.sql.GeneratedValue
import org.babyfish.jimmer.sql.GenerationType
import org.babyfish.jimmer.sql.Id
import org.babyfish.jimmer.sql.ManyToMany

@Entity
interface Category {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long
    val name: String

    @ManyToMany(mappedBy = "categories")
    val books: List<Book>
}
```

`Review.kt`:

```kotlin
package com.github.ichanzhar.rsql.example.model

import org.babyfish.jimmer.sql.Entity
import org.babyfish.jimmer.sql.GeneratedValue
import org.babyfish.jimmer.sql.GenerationType
import org.babyfish.jimmer.sql.Id
import org.babyfish.jimmer.sql.ManyToOne

@Entity
interface Review {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long
    val rating: Int
    val comment: String

    @ManyToOne
    val book: Book?
}
```

`Chapter.kt`:

```kotlin
package com.github.ichanzhar.rsql.example.model

import org.babyfish.jimmer.sql.Entity
import org.babyfish.jimmer.sql.GeneratedValue
import org.babyfish.jimmer.sql.GenerationType
import org.babyfish.jimmer.sql.Id
import org.babyfish.jimmer.sql.ManyToOne

@Entity
interface Chapter {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long
    val sequence: Int
    val title: String

    @ManyToOne
    val book: Book?
}
```

`Book.kt`:

```kotlin
package com.github.ichanzhar.rsql.example.model

import org.babyfish.jimmer.sql.Entity
import org.babyfish.jimmer.sql.GeneratedValue
import org.babyfish.jimmer.sql.GenerationType
import org.babyfish.jimmer.sql.Id
import org.babyfish.jimmer.sql.JoinTable
import org.babyfish.jimmer.sql.ManyToMany
import org.babyfish.jimmer.sql.ManyToOne
import org.babyfish.jimmer.sql.OneToMany

@Entity
interface Book {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long
    val title: String
    val isbn: String?
    val publicationYear: Int

    @ManyToOne
    val author: Author?

    val dimensions: Dimensions

    @OneToMany(mappedBy = "book")
    val reviews: List<Review>

    @OneToMany(mappedBy = "book")
    val chapters: List<Chapter>

    @ManyToMany
    @JoinTable(name = "book_categories", joinColumnName = "book_id", inverseJoinColumnName = "category_id")
    val categories: List<Category>
}
```

- [ ] **Step 5: Write `schema.sql`** (Jimmer default naming: lower snake case, embedded props flatten to their own column names)

```sql
drop table if exists book_categories;
drop table if exists review;
drop table if exists chapter;
drop table if exists book;
drop table if exists category;
drop table if exists author;

create table author (
    id bigserial primary key,
    name text not null,
    email text
);

create table book (
    id bigserial primary key,
    title text not null,
    isbn text,
    publication_year int not null,
    author_id bigint references author (id),
    width_cm float8 not null,
    height_cm float8 not null,
    weight_grams int not null
);

create table review (
    id bigserial primary key,
    rating int not null,
    comment text not null,
    book_id bigint references book (id)
);

create table chapter (
    id bigserial primary key,
    sequence int not null,
    title text not null,
    book_id bigint references book (id)
);

create table category (
    id bigserial primary key,
    name text not null
);

create table book_categories (
    book_id bigint not null references book (id),
    category_id bigint not null references category (id),
    primary key (book_id, category_id)
);
```

- [ ] **Step 6: Write `ExampleApplication.kt` and `application.yml`**

```kotlin
package com.github.ichanzhar.rsql.example

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ExampleApplication

fun main(args: Array<String>) {
    runApplication<ExampleApplication>(*args)
}
```

```yaml
spring:
  application:
    name: spring-boot4-postgres-example
  sql:
    init:
      mode: always
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/rsql_example}
    username: ${SPRING_DATASOURCE_USERNAME:postgres}
    password: ${SPRING_DATASOURCE_PASSWORD:postgres}
jimmer:
  dialect: org.babyfish.jimmer.sql.dialect.PostgresDialect
  show-sql: true
```

- [ ] **Step 7: Verify KSP compilation**

Run from `examples/spring-boot4-postgres-example`: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL (KSP generates drafts, the library is substituted from the included build). No Docker needed. If Jimmer KSP errors on an annotation (e.g. `@JoinTable` attribute names differ in 0.9.96), read the generated error message and fix against the actual annotation source in the jimmer-core jar - the entity property NAMES are normative, annotation spelling is not.

- [ ] **Step 8: Commit**

```bash
git add examples/spring-boot4-postgres-example
git commit -m "feat: spring boot 4 example scaffold with jimmer domain"
```

---

### Task 6: Controller, error advice, and the compatibility-gate boot test

**Files (all under `examples/spring-boot4-postgres-example/`):**
- Create: `src/main/kotlin/com/github/ichanzhar/rsql/example/web/BookController.kt`
- Create: `src/main/kotlin/com/github/ichanzhar/rsql/example/web/BookDto.kt`
- Create: `src/main/kotlin/com/github/ichanzhar/rsql/example/web/RsqlExceptionAdvice.kt`
- Test: `src/test/kotlin/com/github/ichanzhar/rsql/example/web/BookControllerIntegrationTest.kt`

**Interfaces:**
- Consumes: `KSqlClient` bean from jimmer-spring-boot-starter; `createRsqlQuery(Book::class, query)` from Task 4; domain from Task 5
- Produces: `GET /books?query=` returning `List<BookDto>` (`BookDto(id: Long, title: String, isbn: String?, publicationYear: Int)`); 400 handling for `JimmerRsqlSupportException` + `RSQLParserException`. Task 7 extends the test class.

This task is the **compatibility gate** from the spec: the boot test proves jimmer-spring-boot-starter 0.9.96 starts under Spring Boot 4.1.0. If the context fails to boot for starter-compatibility reasons (autoconfiguration errors, missing beans), apply the spec's fallback and report it as a concern: in `build.gradle.kts` replace `implementation("org.babyfish.jimmer:jimmer-spring-boot-starter:0.9.96")` with `implementation("org.babyfish.jimmer:jimmer-sql-kotlin:0.9.96")`, delete the `jimmer:` block from `application.yml`, and add this configuration class:

```kotlin
package com.github.ichanzhar.rsql.example

import javax.sql.DataSource
import org.babyfish.jimmer.sql.dialect.PostgresDialect
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.newKSqlClient
import org.babyfish.jimmer.sql.runtime.ConnectionManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class JimmerConfig {
    @Bean
    fun sqlClient(dataSource: DataSource): KSqlClient = newKSqlClient {
        setConnectionManager(ConnectionManager.simpleConnectionManager(dataSource))
        setDialect(PostgresDialect())
    }
}
```

- [ ] **Step 1: Write the failing boot test**

```kotlin
package com.github.ichanzhar.rsql.example.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class BookControllerIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `returns all books when query is absent`() {
        mockMvc.perform(get("/books"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }
}
```

(Import style mirrors the JPA repo's Boot 4 example, including `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc` and the `org.testcontainers.postgresql` container package - these are the Boot-4/testcontainers-2 coordinates already proven in that repo.)

- [ ] **Step 2: Run it to verify it fails for the right reason**

Run: `./gradlew test --tests 'BookControllerIntegrationTest'`
Expected: FAIL with 404 on `/books` (controller missing). If it instead fails with an application-context error mentioning jimmer autoconfiguration, this is the compatibility gate firing - apply the fallback above, re-run, and only proceed once the failure is the 404.

- [ ] **Step 3: Write DTO, controller, advice**

`BookDto.kt`:

```kotlin
package com.github.ichanzhar.rsql.example.web

data class BookDto(
    val id: Long,
    val title: String,
    val isbn: String?,
    val publicationYear: Int,
)
```

`BookController.kt` (DTO mapping is deliberate: Boot 4 ships Jackson 3 while Jimmer's `ImmutableModule` targets Jackson 2, so entities are not returned raw):

```kotlin
package com.github.ichanzhar.rsql.example.web

import com.github.ichanzhar.rsql.example.model.Book
import com.github.ichanzhar.rsql.jimmer.createRsqlQuery
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/books")
class BookController(private val sqlClient: KSqlClient) {

    @GetMapping
    fun search(@RequestParam(required = false) query: String?): List<BookDto> {
        val books = if (query.isNullOrBlank()) {
            sqlClient.createQuery(Book::class) { select(table) }.execute()
        } else {
            sqlClient.createRsqlQuery(Book::class, query).execute()
        }
        return books.map { BookDto(it.id, it.title, it.isbn, it.publicationYear) }
    }
}
```

`RsqlExceptionAdvice.kt`:

```kotlin
package com.github.ichanzhar.rsql.example.web

import com.github.ichanzhar.rsql.jimmer.exception.JimmerRsqlSupportException
import cz.jirutka.rsql.parser.RSQLParserException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class RsqlExceptionAdvice {

    @ExceptionHandler(JimmerRsqlSupportException::class)
    fun handleRsql(ex: JimmerRsqlSupportException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.badRequest().body(mapOf("error" to ex.message))

    @ExceptionHandler(RSQLParserException::class)
    fun handleParse(ex: RSQLParserException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.badRequest().body(mapOf("error" to (ex.message ?: "invalid RSQL query")))
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'BookControllerIntegrationTest'`
Expected: PASS (context boots, empty DB, `[]` response).

- [ ] **Step 5: Commit**

```bash
git add examples/spring-boot4-postgres-example
git commit -m "feat: example controller with rsql query endpoint and error mapping"
```

---

### Task 7: Seed data and the full integration matrix

**Files:**
- Modify: `examples/spring-boot4-postgres-example/src/test/kotlin/com/github/ichanzhar/rsql/example/web/BookControllerIntegrationTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 1-6
- Produces: the Phase 2 acceptance evidence - the spec's full test matrix green

- [ ] **Step 1: Add seeding and the matrix tests**

Add to the test class: an autowired `JdbcTemplate` (import `org.springframework.jdbc.core.JdbcTemplate`) and this `@BeforeEach` (explicit IDs keep assertions deterministic; `restart identity` resets the sequences):

```kotlin
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun seed() {
        jdbcTemplate.execute(
            "truncate table book_categories, review, chapter, book, category, author restart identity cascade",
        )
        jdbcTemplate.execute(
            """
            insert into author (id, name, email) values
                (1, 'J.R.R. Tolkien', 'tolkien@example.com'),
                (2, 'Frank Herbert', 'herbert@example.com');
            insert into category (id, name) values (1, 'Fantasy'), (2, 'Sci-Fi');
            insert into book (id, title, isbn, publication_year, author_id, width_cm, height_cm, weight_grams) values
                (1, 'The Hobbit', '9780618260300', 1937, 1, 13.0, 20.0, 340),
                (2, 'Dune', null, 1965, 2, 15.0, 23.0, 480);
            insert into review (id, rating, comment, book_id) values
                (1, 5, 'A timeless classic', 1),
                (2, 4, 'Great start to the saga', 1),
                (3, 5, 'Genre-defining', 2);
            insert into chapter (id, sequence, title, book_id) values
                (1, 1, 'An Unexpected Party', 1),
                (2, 2, 'Roast Mutton', 1),
                (3, 1, 'Prologue', 2);
            insert into book_categories (book_id, category_id) values (1, 1), (2, 2);
            """.trimIndent(),
        )
    }
```

Update the existing no-query test to expect 2 books, and add the matrix (import `org.hamcrest.Matchers.containsString` and `MockMvcResultMatchers.content` for the 400-body assertions):

```kotlin
    @Test
    fun `returns all books when query is absent`() {
        mockMvc.perform(get("/books"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `filters by wildcard equality`() {
        mockMvc.perform(get("/books").param("query", "title==*Hobbit*"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("The Hobbit"))
    }

    @Test
    fun `filters by exact equality`() {
        mockMvc.perform(get("/books").param("query", "title==Dune"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("Dune"))
    }

    @Test
    fun `filters by inequality`() {
        mockMvc.perform(get("/books").param("query", "title!=Dune"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("The Hobbit"))
    }

    @Test
    fun `filters by greater than with fiql alias`() {
        mockMvc.perform(get("/books").param("query", "publicationYear=gt=1950"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("Dune"))
    }

    @Test
    fun `combines range bounds with logical and`() {
        mockMvc.perform(get("/books").param("query", "publicationYear>=1937;publicationYear<=1965"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `filters by in list`() {
        mockMvc.perform(get("/books").param("query", "publicationYear=in=(1937,2000)"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("The Hobbit"))
    }

    @Test
    fun `filters by not in list`() {
        mockMvc.perform(get("/books").param("query", "publicationYear=out=(1937)"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("Dune"))
    }

    @Test
    fun `filters by is null`() {
        mockMvc.perform(get("/books").param("query", "isbn=isNull=true"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("Dune"))
    }

    @Test
    fun `filters by is not null`() {
        mockMvc.perform(get("/books").param("query", "isbn=isNull=false"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("The Hobbit"))
    }

    @Test
    fun `filters by reference join path`() {
        mockMvc.perform(get("/books").param("query", "author.name==*Tolkien*"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("The Hobbit"))
    }

    @Test
    fun `filters case insensitively through a join`() {
        mockMvc.perform(get("/books").param("query", "author.email=eqci=HERBERT@EXAMPLE.COM"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("Dune"))
    }

    @Test
    fun `filters by embedded value object field`() {
        mockMvc.perform(get("/books").param("query", "dimensions.weightGrams=gt=400"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("Dune"))
    }

    @Test
    fun `combines predicates with logical or`() {
        mockMvc.perform(get("/books").param("query", "title==Dune,title==*Hobbit*"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `rejects unknown property with 400`() {
        mockMvc.perform(get("/books").param("query", "nosuchfield==1"))
            .andExpect(status().isBadRequest)
            .andExpect(content().string(containsString("nosuchfield")))
    }

    @Test
    fun `rejects collection selector with 400 until phase 3`() {
        mockMvc.perform(get("/books").param("query", "reviews.rating==5"))
            .andExpect(status().isBadRequest)
            .andExpect(content().string(containsString("reviews")))
    }

    @Test
    fun `rejects malformed rsql with 400`() {
        mockMvc.perform(get("/books").param("query", "title=="))
            .andExpect(status().isBadRequest)
    }
```

- [ ] **Step 2: Run the suite**

Run: `./gradlew test`
Expected: all tests PASS. Debug order for failures: (1) SQL errors about types - check the JavaTypeUtil amendment landed (Task 4) and `publication_year` casting; (2) empty results on `eqci` - confirm Jimmer renders `ilike`; (3) join test failures - inspect logged SQL (`jimmer.show-sql: true`) for `left join author`; (4) 400-test failures - confirm the advice catches the exact exception type. Fix the library or example accordingly; library fixes require re-running `./gradlew apiDump build` at the repo root and amending the appropriate area.

- [ ] **Step 3: Commit**

```bash
git add examples/spring-boot4-postgres-example
git commit -m "test: full phase 2 integration matrix with seeded dataset"
```

---

### Task 8: Final verification and CLAUDE.md refresh

**Files:**
- Modify: `CLAUDE.md` (repo root)

**Interfaces:**
- Consumes: everything
- Produces: Phase 2 acceptance evidence and an accurate CLAUDE.md

- [ ] **Step 1: Verify both builds from clean state**

```bash
cd /Users/user/IdeaProjects/jimmer-rsql-support && ./gradlew build
cd examples/spring-boot4-postgres-example && ./gradlew test
```

Expected: both BUILD SUCCESSFUL. Capture the test count in your report.

- [ ] **Step 2: Update CLAUDE.md's current-state line**

Replace the paragraph starting `**Current state: greenfield.**` with:

```markdown
**Current state: phases 1-2 done.** Parser layer, SelectorResolver (scalar/reference/embedded),
scalar processors, and the public `toPredicate`/`createRsqlQuery` API are implemented; the
spring-boot4-postgres-example integration suite is the acceptance evidence. Collection
selectors (EXISTS), `=isEmpty=`, and JSON operators are still stubs (phases 3-4). The
authoritative spec is `docs/jimmer-rsql-support-implementation-plan.md`.
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: mark phases 1-2 complete in CLAUDE.md"
```
