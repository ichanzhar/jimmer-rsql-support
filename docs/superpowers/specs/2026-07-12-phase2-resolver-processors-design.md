# Phase 2: SelectorResolver + Scalar Processors + Spring Boot 4 Example - Design

Date: 2026-07-12
Status: Approved
Parent spec: `docs/jimmer-rsql-support-implementation-plan.md` (sections 4.2-4.5, 5.1, 7-phase-2)
Builds on: `docs/superpowers/specs/2026-07-12-phase1-skeleton-parser-port-design.md` (merged to main)

## Scope

Library additions under `com.github.ichanzhar.rsql.jimmer`:

- `SelectorResolver` (internal) resolving dotted selectors against Jimmer metadata
- `JimmerRsqlVisitor` folding the RSQL AST into `KNonNullExpression<Boolean>?`
- 10 processors replacing the Phase 1 registry stubs: `==`, `!=`, `>`, `>=`, `<`, `<=`,
  `=in=`, `=out=`, `=isNull=`, `=eqci=`
- `Api.kt` public entry points: `Node.toPredicate(table)`, `KSqlClient.createRsqlQuery(...)`
- New exceptions `UnknownPropertyException`, `UnsupportedSelectorException`
- `JavaTypeUtil` boxed-wrapper amendment (justified deviation, below)

Example: `examples/spring-boot4-postgres-example` - independent Gradle build consuming the
library via `includeBuild("../..")`, Testcontainers-based integration tests.

Out of scope: collection-path resolution and `=isEmpty=` (Phase 3; resolver throws
`UnsupportedSelectorException` - decided), `=jsonbeq=`/`=jsoneq=` (Phase 4), Ktor example
(Phase 5), tags/labels element-collection modeling (deferred to Phase 3 - decided).

## Verified Jimmer 0.9.96 API facts (drive all signatures below)

- `KProps<E>` has string-based `get<X>(prop: String): KPropExpression<X>`,
  `getAssociatedId<X>(prop: String): KPropExpression<X>`,
  `outerJoin<X>(prop: String): KNullableTable<X>`,
  `exists(prop: String, block): KNonNullExpression<Boolean>?`.
- `ImmutableType` is obtained via `(kProps as TableSelection).immutableType`
  (`org.babyfish.jimmer.sql.ast.impl.table.TableSelection`, public; Jimmer's own Kotlin
  DSL uses exactly this cast internally).
- `ImmutableType.getProp(name)` throws bare `IllegalArgumentException` on unknown names;
  `type.props` is a `Map<String, ImmutableProp>` - use nullable lookup instead.
- Prop classification requires parameterized predicates: `isReference(TargetLevel.ENTITY)`,
  `isReferenceList(TargetLevel.ENTITY)`, `isEmbedded(EmbeddedLevel.SCALAR)`,
  `isScalarList()`; `prop.category: ImmutablePropCategory` gives the coarse class.
- `prop.targetType` is null for non-associations; embedded child metadata comes from
  `ImmutableType.get(prop.elementClass)`.
- `KEmbeddedPropExpression<T>` has NO `get(String)` - only `get(prop: ImmutableProp)` and
  `get(prop: KProperty1)`. Embedded chaining resolves the child `ImmutableProp` first.
  `table.get<X>("embeddedProp")` returns an expression that is a
  `KEmbeddedPropExpression` at runtime (safe-cast per hop).
- `gt/ge/lt/le` require `T : Comparable<*>`; `eq(right: T?)` routes null to `isNull()`;
  `like`/`ilike` take `(pattern, LikeMode)` - default mode ANYWHERE auto-wraps `%`, so
  the library always passes `LikeMode.EXACT` with its own pre-translated pattern.
- `and(vararg KNonNullExpression<Boolean>?)` / `or(...)` ignore nulls and return null when
  all args are null.
- `KSqlClient.createQuery(entityType, block)` returns `KConfigurableRootQuery<E, R>`.
  The parent plan's `KConfigurableRootQuery<KNonNullTable<E>, E>` generics are wrong;
  the correct public return type for `createRsqlQuery` is `KConfigurableRootQuery<E, E>`.
  Inside the block, `table: KNonNullTable<E>` (a `KProps<E>`) - string access works directly.

## SelectorResolver

`internal object SelectorResolver`, stateless. Result type:

```kotlin
internal sealed interface ResolvedSelector {
    data class Scalar(
        val expression: KPropExpression<Any>,
        val prop: ImmutableProp,
        val castTarget: Class<*>,
    ) : ResolvedSelector
}
```

(Phase 3 adds a `CollectionPath` variant carrying the EXISTS wrapper; sealed now so the
visitor's `when` is exhaustive from day one.)

Algorithm for `resolve(table: KProps<*>, selector: String): ResolvedSelector.Scalar`,
tokens = `selector.split('.')`, walked as a fold with two modes:

1. **Table mode** (current is `KProps<*>`): metadata from
   `(current as TableSelection).immutableType`; `prop = type.props[token]` or throw
   `UnknownPropertyException(selector, token, type.javaClass.simpleName)`.
   - Reference (`isReference(TargetLevel.ENTITY)`), tokens remain:
     `current = current.outerJoin<Any>(token)`, stay in table mode. Jimmer merges
     duplicate joins and optimizes ID-only access automatically.
   - Reference, terminal token: `Scalar(current.getAssociatedId(token), prop,
     castTarget = prop.targetType.idProp.returnClass)` - `author==5` and
     `author=isNull=true` work FK-side without a join.
   - Embedded (`isEmbedded(EmbeddedLevel.SCALAR)`): `expr = current.get<Any>(token)`,
     switch to expression mode with `embeddedType = ImmutableType.get(prop.elementClass)`.
     If terminal, return `Scalar(expr, prop, prop.returnClass)`.
   - Collection (`isReferenceList(TargetLevel.ENTITY)` or `isScalarList`): throw
     `UnsupportedSelectorException(selector, "collection association '$token' is not
     supported yet")` (Phase 3 replaces this branch with the EXISTS wrapper).
   - Scalar, terminal: `Scalar(current.get<Any>(token), prop, prop.returnClass)`.
   - Scalar, tokens remain: throw `UnsupportedSelectorException(selector,
     "'$token' is not navigable")`.
2. **Expression mode** (current is `KPropExpression<Any>` over embeddable type `T`):
   `childProp = embeddedType.props[token]` or `UnknownPropertyException`; descend via
   `(current as? KEmbeddedPropExpression<Any>)?.get<Any>(childProp)` (a non-embedded
   current with tokens remaining throws `UnsupportedSelectorException`). Terminal token
   returns `Scalar(childExpr, childProp, childProp.returnClass)`. Nested embeddables
   recurse naturally.

## Visitor and public API

```kotlin
public class JimmerRsqlVisitor<E : Any> :
    RSQLVisitor<KNonNullExpression<Boolean>?, KProps<E>> {
    override fun visit(node: AndNode, table: KProps<E>) =
        and(*node.children.mapNotNull { it.accept(this, table) }.toTypedArray())
    override fun visit(node: OrNode, table: KProps<E>) =
        or(*node.children.mapNotNull { it.accept(this, table) }.toTypedArray())
    override fun visit(node: ComparisonNode, table: KProps<E>) = /* below */
}
```

Comparison visit: `resolve` the selector; cast every argument via
`ArgumentConvertor.castArgument(arg, node.selector,
JavaTypeUtil.getPropertyJavaType(resolved.castTarget))`; build
`Params(resolved.expression, resolved.prop, castArgs, castArgs.firstOrNull())`;
dispatch `RsqlOperationsRegistry.operationProcessors[node.operator]` (missing operator
cannot happen for parser-produced nodes since the parser is built from the same registry;
a hand-built node with an unregistered operator throws `JimmerRsqlSupportException`);
return `factory(params).process()`.

`Api.kt` (top-level, KDoc'd):

```kotlin
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

`Params.expression` tightens from `KPropExpression<Any>?` to non-null? No - Phase 3's
`=isEmpty=` needs expression-less params, so `Params` keeps its Phase 1 shape
(`expression: KPropExpression<Any>?`); Phase 2 always fills it.

## Processors

Package `operations/`, one file per processor, each `internal class XProcessor(private
val params: Params) : Processor`. Wildcard helpers live in a small internal
`WildcardSupport` (top-level internal functions in `operations/Wildcards.kt`):
`isLikeExpression(argument: Any?): Boolean` (string starting or ending with `*`) and
`likePattern(argument: Any?): String` (`replace('*', '%')`) - ported from
`AbstractProcessor` minus the join branching, which the resolver has eliminated.

| Processor | Implementation (params.expression as `expr`, params.argument as `arg`) |
|---|---|
| `EqualProcessor` | wildcard: `(expr as KExpression<String>).like(likePattern(arg), LikeMode.EXACT)`; else `expr.eq(arg)` |
| `NotEqualProcessor` | wildcard: `like(...).not()`; else `expr.ne(arg)` |
| `GtProcessor` | `(expr as KExpression<Comparable<Any>>).gt(arg as Comparable<Any>)` |
| `GteProcessor` | same with `ge` |
| `LtProcessor` | same with `lt` |
| `LteProcessor` | same with `le` |
| `InProcessor` | `expr.valueIn(params.args)` |
| `NotInProcessor` | `expr.valueNotIn(params.args)` |
| `IsNullProcessor` | `if (arg.toString().toBoolean()) expr.isNull() else expr.isNotNull()` |
| `EqualCiProcessor` | `(expr as KExpression<String>).ilike(arg.toString(), LikeMode.EXACT)` |

Registry wiring: the Phase 1 `phase2Stub` entries and the private `phase2Stub` helper are
deleted; the seed map registers real factories (`{ EqualProcessor(it) }`, ...). Public
registry API unchanged; `.api` dump unchanged.

Each unchecked cast is annotated `@Suppress("UNCHECKED_CAST")` at the smallest scope.
`requireNotNull(params.expression)` with a message mentioning the operator guards the
Phase 1 nullable field.

## JavaTypeUtil amendment (justified deviation from strict parity)

The JPA original's map lacks boxed wrapper names (only `java.lang.Boolean` is present),
so boxed numeric metamodel types fell through `castArgument` to the raw-string fallback
and Hibernate's criteria API coerced the strings at bind time. Jimmer performs no such
coercion - a String literal against an int column is a Postgres type error - so the map
gains boxed names mapping to the same class instances the `castArgument` branches match:

```
"java.lang.Integer" -> Int::class.java
"java.lang.Long" -> Long::class.java
"java.lang.Double" -> Double::class.java
"java.lang.Float" -> Float::class.java
"java.lang.Short" -> Short::class.java
"java.lang.Byte" -> Byte::class.java
"java.lang.Character" -> Char::class.java
```

`castArgument` itself is untouched. Existing primitive-name entries stay. Public
signatures unchanged, `.api` dump unaffected.

## Exceptions

```kotlin
public class UnknownPropertyException(selector: String, property: String, entityName: String) :
    JimmerRsqlSupportException("Unknown property '$property' in selector '$selector' on entity '$entityName'")

public class UnsupportedSelectorException(selector: String, reason: String) :
    JimmerRsqlSupportException("Unsupported selector '$selector': $reason")
```

## Example: examples/spring-boot4-postgres-example

Independent Gradle build (own 9.6.1 wrapper copied from the root repo,
own `settings.gradle.kts` with `includeBuild("../..")`), NOT in the root build or CI.

- Dependencies: Spring Boot 4.x (latest GA), `jimmer-spring-boot-starter:0.9.96`,
  KSP plugin matching Kotlin 2.3.20, `spring-boot-starter-web` + Jackson,
  Testcontainers postgres + `spring-boot-testcontainers`, JUnit 5.
- **Compatibility gate (first task of the example):** verify
  `jimmer-spring-boot-starter` 0.9.96 boots under Spring Boot 4. Fallback if it does
  not: drop the starter, keep `jimmer-sql-kotlin` + KSP, and declare a manual
  `KSqlClient` bean (`newKSqlClient { setConnectionManager(...); setDialect(PostgresDialect()) }`
  over the Boot-managed DataSource). The controller and tests are written against
  `KSqlClient` directly so both wiring modes look identical downstream.
- Domain (Jimmer interfaces + KSP, package `com.github.ichanzhar.rsql.example.model`):
  `Book` (id, title, isbn nullable, publicationYear, author many-to-one nullable,
  dimensions embedded, reviews one-to-many, chapters one-to-many, categories
  many-to-many), `Author` (id, name, email nullable), `Review` (id, rating, comment),
  `Chapter` (id, sequence, title), `Category` (id, name),
  `@Embeddable Dimensions` (widthCm Double, heightCm Double, weightGrams Int).
  No tags/labels (deferred - decided).
- `schema.sql` executed by Spring SQL init (Jimmer does not create schemas).
- `BookController`: `GET /books?query=<rsql>` - blank query returns all; else
  `sqlClient.createRsqlQuery(Book::class, query).execute()`.
- `@RestControllerAdvice` mapping `JimmerRsqlSupportException` and `RSQLParserException`
  to 400 with a problem-details body.
- Jackson: register Jimmer's `ImmutableModule` (the starter does this automatically;
  the fallback wiring registers it explicitly).

### Integration test matrix (Testcontainers postgres:16-alpine, @ServiceConnection, MockMvc)

Seeded with the two-book dataset ported from the JPA example (minus tags/labels).

| Query | Expectation |
|---|---|
| `title==*Hobbit*` | 1 result, The Hobbit (wildcard LIKE) |
| `title==Dune` | 1 result, exact equality |
| `title!=Dune` | 1 result, The Hobbit |
| `publicationYear=gt=1950` | 1 result, Dune (also covers `=gt=` FIQL alias) |
| `publicationYear>=1937;publicationYear<=1965` | 2 results (AND fold) |
| `publicationYear=in=(1937,2000)` | 1 result, The Hobbit |
| `publicationYear=out=(1937)` | 1 result, Dune |
| `isbn=isNull=true` | 1 result, Dune |
| `isbn=isNull=false` | 1 result, The Hobbit |
| `author.name==*Tolkien*` | 1 result (reference outerJoin) |
| `author.email=eqci=HERBERT@EXAMPLE.COM` | 1 result, Dune (ILIKE) |
| `dimensions.weightGrams=gt=400` | 1 result, Dune (embedded chaining) |
| `title==Dune,title==*Hobbit*` | 2 results (OR fold) |
| `nosuchfield==1` | 400, body mentions `nosuchfield` |
| `reviews.rating==5` | 400, unsupported-selector message (Phase 3 flips this to 200) |
| malformed RSQL (`title==`) | 400 (RSQLParserException) |

Assert one representative query generates no `DISTINCT` and the join query uses a left
join - via Jimmer `Executor` SQL capture if cheap, otherwise deferred to Phase 3 where
SQL-shape assertions are an explicit acceptance criterion.

## Acceptance criteria (parent plan phase 2)

1. `./gradlew build` green at the repo root (library compiles, ktlint, apiCheck with
   updated dump for the new public API).
2. `cd examples/spring-boot4-postgres-example && ./gradlew test` green (Docker required):
   full matrix above, including the parent plan's canonical queries
   (`title==*...*` wildcard, `author.<field>=eqci=...`, `<field>=isNull=true`).

## Risks

- `jimmer-spring-boot-starter` 0.9.96 on Spring Boot 4: unverified; mitigated by the
  compatibility gate + manual `KSqlClient` fallback above.
- `TableSelection` lives in an `impl` package: it is public and Jimmer's own DSL relies
  on it, but pin 0.9.96 in the example and re-verify on any Jimmer upgrade.
- Boxed-vs-primitive `returnClass` reality on generated Jimmer entities: covered by the
  JavaTypeUtil amendment; the `publicationYear`/`weightGrams` (non-null Int) and
  `isbn`/`email` (nullable) tests exercise both sides.
