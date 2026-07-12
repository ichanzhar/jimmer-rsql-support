# jimmer-rsql-support: Implementation Instructions

RSQL support for Jimmer ORM, ported from `rsql-hibernate-jpa` (https://github.com/ichanzhar/rsql-hibernate-jpa).
The library translates RSQL query strings (parsed by `cz.jirutka.rsql:rsql-parser`) into Jimmer
`KNonNullExpression<Boolean>` predicates that plug into `KSqlClient.createQuery { where(...) }`.

Target: Kotlin DSL of Jimmer first. The core resolver works against `ImmutableType` / `ImmutableProp`
metadata and weakly-typed `KProps` APIs, so a Java DSL module can be added later without rework.

---

## 1. Goals and non-goals

Goals:

- Parse RSQL strings into Jimmer predicates with join-path support (`store.name==Manning`).
- Same operator set as rsql-hibernate-jpa: `==`, `!=`, `>`, `>=`, `<`, `<=`, `=in=`, `=out=`,
  `=isNull=`, `=eqci=`, `=isEmpty=`, plus Postgres-only `=jsonbeq=` / `=jsoneq=`.
- Wildcard support: `name==*action*` translates to SQL `LIKE '%action%'`.
- Collection associations resolved via Jimmer implicit subqueries (EXISTS), not joins.
  This removes the `distinct(true)` workaround required in the JPA version entirely.
- Extensible operator registry, same as `RsqlOperationsRegistry` in the JPA library.
- Published to Maven Central as `com.github.ichanzhar:jimmer-rsql-support`.

Non-goals (for the first release):

- Java DSL entry point (design for it, do not build it yet).
- Sorting / pagination syntax (RSQL covers filtering only; callers use Jimmer `orderBy` / fetchers).
- Object fetcher integration (callers control the selection shape themselves).

---

## 2. Repository layout

New repository `jimmer-rsql-support`, mirroring the monorepo layout of rsql-hibernate-jpa:

```
jimmer-rsql-support/                        # repo root
  settings.gradle.kts               # includes ONLY the library module; nmcp plugin config
  gradle.properties
  CLAUDE.md                         # architecture + commands guide, same style as JPA repo
  README.md
  LICENSE                           # MIT
  .github/workflows/pr-ci.yaml      # ./gradlew build on JDK 21
  .github/workflows/release.yaml    # manual trigger, publishes via nmcp, tags v<version>
  jimmer-rsql-support/                      # the library module (published artifact)
    build.gradle.kts
    src/main/kotlin/com/github/ichanzhar/rsql/jimmer/...
  examples/                         # standalone builds, own wrappers, NOT in root build or CI
    spring-boot4-postgres-example/
    ktor-postgres-example/
```

Rules copied from the JPA repo and kept identical:

- Root `settings.gradle.kts` includes exactly one subproject: `jimmer-rsql-support`.
- Each example is a fully independent Gradle build (own wrapper, own `settings.gradle.kts`)
  consuming the library source via `includeBuild("../..")` until the first Maven Central release,
  then switching to the published coordinate.
- The library module has no test source set. All integration tests live in the examples,
  Testcontainers based, Docker required.
- Examples: Spring Boot 4.x and Ktor only. No Spring Boot 3 example.

---

## 3. Dependencies

Library module:

```kotlin
dependencies {
    api("cz.jirutka.rsql:rsql-parser:2.3.2")           // check latest
    compileOnly("org.babyfish.jimmer:jimmer-sql-kotlin:<latest>") // check latest release
    implementation("org.slf4j:slf4j-api:2.x")
}
```

Notes:

- `jimmer-sql-kotlin` is `compileOnly` / provided: the consuming application always brings its own
  Jimmer (via `jimmer-spring-boot-starter` or direct `jimmer-sql-kotlin`). Document the required
  minimum Jimmer version in README.
- No Spring dependencies in the library. The library must be framework-agnostic so it works
  in both Spring Boot 4 and Ktor. This is a deliberate difference from rsql-hibernate-jpa,
  which depends on `spring-data-jpa` for the `Specification` type.
- JDK 21, Kotlin 2.x, same toolchain settings as the JPA repo.

---

## 4. Architecture

Package root: `com.github.ichanzhar.rsql.jimmer`.

### 4.1 Ported unchanged from rsql-hibernate-jpa (parser layer)

These classes are ORM-agnostic. Copy and adapt package names only:

| Class | Purpose |
|---|---|
| `RsqlOperation` | Enum: operator symbol -> `ComparisonOperator` + `ParserContext` |
| `ParserContext` | `COMMON` / `POSTGRESQL` |
| `utils/RsqlOperationsRegistry` | Mutable registry driving parsing and processor dispatch; `registerOperation(operator, processorFactory)` for custom operators |
| `utils/RsqlParserFactory` | Builds `RSQLParser` from the registry; `instance(ParserContext.POSTGRESQL)` also registers JSON operators |
| `utils/JavaTypeUtil` | Primitive -> wrapper normalization |
| `utils/ArgumentConvertor` | String argument -> typed value (Int, Long, BigDecimal, Boolean, UUID, dates, enums, ...) with fallback to raw string |
| `exception/InvalidDateFormatException`, `exception/InvalidEnumValueException` | Coercion failures for dates / enums |

Only change in `ArgumentConvertor` call sites: the target Java type comes from
`ImmutableProp.returnClass` (Jimmer metadata) instead of the JPA metamodel
`path.model.bindableJavaType`.

### 4.2 New: visitor and predicate builder

Replaces `JpaRsqlVisitor` + `GenericRsqlSpecBuilder` + `JpaRsqlSpecification`.

Key difference from the JPA version: there is no `Specification<E>` closure. The Jimmer table
proxy is passed down as the visitor parameter, and the visitor returns a composed predicate.

```kotlin
class JimmerRsqlVisitor<E : Any> :
    RSQLVisitor<KNonNullExpression<Boolean>?, KProps<E>> {

    override fun visit(node: AndNode, table: KProps<E>): KNonNullExpression<Boolean>? =
        and(*node.children.mapNotNull { it.accept(this, table) }.toTypedArray())

    override fun visit(node: OrNode, table: KProps<E>): KNonNullExpression<Boolean>? =
        or(*node.children.mapNotNull { it.accept(this, table) }.toTypedArray())

    override fun visit(node: ComparisonNode, table: KProps<E>): KNonNullExpression<Boolean>? =
        SelectorResolver.resolve(table, node).let { resolved ->
            ProcessorsFactory.getProcessor(node.operator, resolved).process()
        }
}
```

`and(...)` / `or(...)` are `org.babyfish.jimmer.sql.kt.ast.expression.and/or`; both ignore nulls,
which composes naturally with nullable predicates.

Public entry points (top-level extension functions, `Api.kt`):

```kotlin
fun <E : Any> Node.toPredicate(table: KProps<E>): KNonNullExpression<Boolean>?

fun <E : Any> KSqlClient.createRsqlQuery(
    entityType: KClass<E>,
    rsql: String,
    context: ParserContext? = null,
): KConfigurableRootQuery<KNonNullTable<E>, E>
```

`toPredicate` is the primitive everyone can use inside any `createQuery` block;
`createRsqlQuery` is sugar that parses + applies `where` + `select(table)`.

### 4.3 New: SelectorResolver (replaces the join logic in JpaRsqlSpecification)

Resolves a dotted RSQL selector (`store.name`, `dimensions.width`, `reviews.rating`) against
Jimmer metadata step by step. Verified Jimmer APIs used:

- `KProps.get<X>(prop: String): KPropExpression<X>` - weakly-typed column access
- `KProps.outerJoin<X>(prop: String): KNullableTable<X>` - LEFT JOIN for reference associations
- `KProps.exists(prop: String, block: KImplicitSubQueryTable<X>.() -> KNonNullExpression<Boolean>?)` - implicit EXISTS subquery for collection associations
- `ImmutableType` / `ImmutableProp` (`table.immutableType.getProp(name)`) - metadata:
  `isAssociation`, `isReferenceList` (collection), `isEmbedded`, `returnClass`, `targetType`

Algorithm for selector `a.b.c` on root table `t`:

1. Look up `prop = currentType.getProp(token)`. Unknown token -> throw
   `UnknownPropertyException(selector, token, entityName)` (new exception type, clear message).
2. If `prop` is a **reference association** (many-to-one / one-to-one) and tokens remain:
   `current = current.outerJoin<Any>(token)`, continue. Jimmer merges duplicate joins and
   optimizes ID-only access (phantom joins) automatically, so repeated selectors are free.
3. If `prop` is a **collection association** (one-to-many / many-to-many): stop path walking here.
   Build the remainder of the predicate inside `current.exists(token) { rest }` where `rest`
   recursively resolves the remaining tokens against the subquery table. This is the EXISTS
   strategy: no row fanout, no `distinct`, and it works at any depth
   (`authors.books.name==X` becomes nested EXISTS).
4. If `prop` is **embedded**: descend via `get` chaining on the embedded expression
   (`table.get<Any>("dimensions").get(...)` - verify exact embedded API against the pinned
   Jimmer version; PropExpression has an embedded variant with nested `get`).
5. Last token must be a scalar (or an association for `=isNull=` / `=isEmpty=`): produce the
   final `KPropExpression<Any>` plus its `ImmutableProp`.

Output of resolution:

```kotlin
data class ResolvedSelector(
    val expression: KPropExpression<Any>?,   // null when the terminal prop is a collection (isEmpty case)
    val prop: ImmutableProp,                 // terminal prop, source of returnClass for casting
    val existsWrapper: ((KNonNullExpression<Boolean>?) -> KNonNullExpression<Boolean>?)?,
)
```

If the path crossed a collection, `existsWrapper` wraps the processor output in the EXISTS
subquery. Processors never need to know about it: the visitor applies the wrapper after
`process()` returns. This removes the entire `isRootJoin` / `isSetJoin` / `isListJoin` /
`isCollectionJoin` branching that every processor carried in the JPA version.

### 4.4 Processors

Package `operations/`. Interface and factory identical in spirit to the JPA version:

```kotlin
data class Params(
    val expression: KPropExpression<Any>?,
    val prop: ImmutableProp,
    val args: List<Any>,
    val argument: Any?,      // first arg, convenience
)

fun interface Processor {
    fun process(): KNonNullExpression<Boolean>?
}
```

`AbstractProcessor` keeps only two helpers (wildcard detection and `*` -> `%` translation):

```kotlin
fun isLikeExpression(): Boolean =
    (params.argument as? String)?.let { it.startsWith("*") || it.endsWith("*") } ?: false

fun formattedLikePattern(): String = params.argument.toString().replace('*', '%')
```

Operator mapping (JPA CriteriaBuilder -> Jimmer Kotlin DSL):

| RSQL | Processor | Jimmer implementation |
|---|---|---|
| `==` | `EqualProcessor` | `expr.eq(v)`; wildcard -> `(expr as KPropExpression<String>).like(pattern, LikeMode.EXACT)` with pre-translated `%`; `null` arg -> `expr.isNull()` |
| `!=` | `NotEqualProcessor` | `expr.ne(v)`; wildcard -> `expr.notLike(pattern)` (or `like(...).not()`) |
| `>` `>=` `<` `<=` | `Gt/Gte/Lt/LteProcessor` | `gt` / `ge` / `lt` / `le` - cast `v` to `Comparable<Any>` |
| `=in=` | `InProcessor` | `expr.valueIn(args)` |
| `=out=` | `NotInProcessor` | `expr.valueNotIn(args)` |
| `=isNull=` | `IsNullProcessor` | arg `true` -> `expr.isNull()`, `false` -> `expr.isNotNull()`; for a reference association use `table.getAssociatedId(prop).isNull()` to avoid an unnecessary join |
| `=eqci=` | `EqualCiProcessor` | `expr.ilike(v, LikeMode.EXACT)` (Postgres ILIKE / lower() on others - Jimmer dialect handles it) |
| `=isEmpty=` | `IsEmptyProcessor` | collection prop only: `true` -> `not(exists(prop) { null })`, `false` -> `exists(prop) { null }`. Implemented in the visitor path since it needs the parent table, not an expression |
| `=jsonbeq=` | `JsonbEqualProcessor` | shipped (phase 4): `sql(Boolean::class, "jsonb_extract_path_text(%e::jsonb, %v[, %v...]) = %v")` - argument `path|value`, first-pipe split, dot-separated keys, keys and value BOUND (deviation from the JPA literal interpolation, closes the injection surface) |
| `=jsoneq=` | `JsonEqualProcessor` | same with `json_extract_path_text(%e::json, ...)` |

`ProcessorsFactory` + `RsqlOperationsRegistry` dispatch stays structurally identical to the JPA
library so custom operators register the same way:
`RsqlOperationsRegistry.registerOperation(ComparisonOperator("=myop="), { params -> MyProcessor(params) })`.

### 4.5 Error handling

Same philosophy as the JPA library:

- `InvalidEnumValueException`, `InvalidDateFormatException` on enum/date coercion failure.
- All other cast failures fall back silently to the raw string argument.
- New: `UnknownPropertyException` with the full selector, the failing token, and the entity type
  name. The JPA version leaked raw Hibernate metamodel exceptions here; do better.
- All exceptions extend a common `JimmerRsqlSupportException : RuntimeException` so callers can map
  them to HTTP 400 with one handler.

---

## 5. Examples

Both examples share the same domain, ported from the JPA repo so the integration test suite can
be ported as the parity spec: `Book`, `Author`, `Review`, `Chapter`, `Category`, embedded
`Dimensions`. Entities are Jimmer interfaces (`@Entity interface Book { ... }`) with KSP.

### 5.1 examples/spring-boot4-postgres-example

- Spring Boot 4.x, `jimmer-spring-boot-starter`, Postgres, Kotlin.
- `BookRepository : KRepository<Book, Long>` from the starter.
- `BookController` with `GET /books?query=<rsql>`:

```kotlin
@GetMapping
fun search(@RequestParam(required = false) query: String?): List<Book> {
    if (query.isNullOrBlank()) return bookRepository.findAll()
    val node = RsqlParserFactory.instance(ParserContext.POSTGRESQL).parse(query)
    return bookRepository.sql
        .createQuery(Book::class) {
            node.toPredicate(table)?.let { where(it) }
            select(table)
        }
        .execute()
}
```

- Integration tests: port `BookControllerIntegrationTest` from the spring-boot4 example of the
  JPA repo verbatim (same RSQL inputs, same expected result sets). Every query that needed
  `distinct = true` there must pass here without any distinct handling - that is the acceptance
  test for the EXISTS strategy. Testcontainers Postgres, `@SpringBootTest(webEnvironment = RANDOM_PORT)`.
- A `@RestControllerAdvice` mapping `JimmerRsqlSupportException` -> 400 with a problem-details body.

### 5.2 examples/ktor-postgres-example

Demonstrates that the library has zero Spring coupling.

- Ktor 3.x server (Netty), `jimmer-sql-kotlin` directly, HikariCP DataSource, Postgres.
- Manual client construction:

```kotlin
val sqlClient = newKSqlClient {
    setConnectionManager(ConnectionManager.simpleConnectionManager(dataSource))
    setDialect(PostgresDialect())
}
```

- Route:

```kotlin
get("/books") {
    val query = call.request.queryParameters["query"]
    val books = if (query.isNullOrBlank()) {
        sqlClient.createQuery(Book::class) { select(table) }.execute()
    } else {
        sqlClient.createRsqlQuery(Book::class, query, ParserContext.POSTGRESQL).execute()
    }
    call.respond(books)
}
```

- StatusPages plugin maps `JimmerRsqlSupportException` and `RSQLParserException` to 400.
- Same Testcontainers-based integration tests using Ktor `testApplication`.
- Jimmer entities serialize via Jackson; register `ImmutableModule` in Ktor's Jackson
  content negotiation.

---

## 6. Kotlin best practices for this codebase

Apply throughout the library and examples:

- **Objects and top-level functions over static-style classes.** `RsqlParserFactory`,
  `ArgumentConvertor`, `JavaTypeUtil` stay `object`s; public API entry points are top-level
  extension functions (`Node.toPredicate`, `KSqlClient.createRsqlQuery`) in a single `Api.kt`.
- **Immutability by default.** `val` everywhere, `data class Params` with `val` fields
  (the JPA version used `var` - fix that). No mutable shared state except the operations
  registry, which should be backed by a `ConcurrentHashMap` and expose read-only views
  (`Map`, not `MutableMap`).
- **Null-safety as design, not defense.** The visitor returns `KNonNullExpression<Boolean>?`
  and composition uses Jimmer's null-ignoring `and`/`or`. No `!!` anywhere; the JPA version's
  `builder!!` / `property!!` pattern must not be ported. Use `requireNotNull(x) { "message" }`
  when a contract truly guarantees non-null.
- **Sealed hierarchies for closed sets.** Consider `sealed interface` for resolver results if
  `ResolvedSelector` grows variants (scalar / embedded / collection-terminal); exhaustive `when`
  without `else`.
- **`when` over if-chains**, expression bodies for single-expression functions,
  named arguments at call sites with more than two parameters of the same type.
- **fun interface** for `Processor` so operator registration lambdas stay concise.
- **Explicit API mode.** Enable `explicitApi()` in the library's Gradle config so every public
  declaration has an explicit visibility and return type - this is a published library.
- **Binary-compatibility validator.** Add `org.jetbrains.kotlinx.binary-compatibility-validator`
  and commit the `.api` dump; review it in PRs.
- **No `Strings.CS.contains` / Apache commons-lang.** Kotlin stdlib covers everything the JPA
  version used commons-lang for (`contains`, tokenization via `split('.')` instead of
  `StringTokenizer`).
- **Error messages with context.** Every thrown exception includes the selector and operator;
  use `require` / `check` with lazy message lambdas.
- **KDoc on all public API**, including an example RSQL string per operator in
  `RsqlOperation` KDoc.
- **Logging:** slf4j-api only, no XLogger (drop the `slf4j-ext` dependency the JPA version had),
  log at `debug` for resolution steps, never log argument values above `trace`
  (they may contain user data).
- **Formatting/lint:** ktlint or ktfmt via Gradle plugin, wired into CI. Plain hyphens in
  docs and comments, no em dashes.
- **Coroutines note:** Jimmer's blocking client is used in both examples; in Ktor call it via
  `Dispatchers.IO` (`withContext(Dispatchers.IO) { ... }`) inside routes.

### 6.1 Functional programming practices

The library is a natural fit for FP: it is a pure transformation from an RSQL AST to a Jimmer
expression tree. Apply these throughout, stdlib-only (no Arrow dependency in a published
low-level library):

- **Pure core, side effects at the edges.** Everything between `parse(rsql)` and the returned
  `KNonNullExpression<Boolean>?` must be pure and deterministic: no I/O, no clock access, no
  mutation of anything outside local scope. Argument conversion is the one place touching
  locale/format concerns - keep formats as explicit parameters or constants, never ambient
  mutable configuration.
- **Model the domain as algebraic data types.** `ResolvedSelector` becomes a
  `sealed interface` with variants (`Scalar`, `Embedded`, `CollectionPath`), each carrying
  exactly the data valid for that case - make illegal states unrepresentable instead of
  using nullable fields as flags. Exhaustive `when` over the sealed type, no `else` branch.
- **Fold the AST instead of visiting with mutation.** The rsql-parser visitor API is
  imperative in shape, but each `visit` implementation must be a pure expression:
  map children -> combine with `and(...)` / `or(...)`. No accumulators, no `var`,
  no builder objects mutated across visits. Tree recursion is fine - RSQL queries from
  HTTP parameters are shallow, stack depth is a non-issue.
- **Higher-order functions over class hierarchies where it is simpler.** A processor is
  conceptually `(Params) -> KNonNullExpression<Boolean>?`. The registry maps an operator to a
  processor factory function; prefer registering lambdas over instantiating one-method classes.
  Keep `fun interface Processor` as the named type so signatures stay readable and SAM
  conversion keeps call sites concise.
- **Function composition for the EXISTS wrapper.** `existsWrapper` is a
  `(KNonNullExpression<Boolean>?) -> KNonNullExpression<Boolean>?`. Nested collection paths
  compose wrappers with `andThen`-style composition (define a tiny private `compose`/`then`
  helper) rather than accumulating a mutable list of levels.
- **Expressions over statements.** Expression bodies, `when` as expression, `if` as expression.
  A function longer than one expression should usually be decomposed, not turned into a
  statement block with early returns and temps.
- **Total functions: encode failure in the type where the caller must react.**
  Two-tier policy:
  - Internal conversion steps return `null` (or a sealed result) for "could not coerce,
    fall back to raw string" - control flow, not an error, so no exceptions internally.
    `runCatching` may wrap third-party parsers (`UUID.fromString`, date parsing) at the
    boundary, immediately mapped to the sealed result; never let `Result` leak into
    signatures or swallow unrelated exceptions with a broad `runCatching`.
  - The public API throws the documented `JimmerRsqlSupportException` hierarchy - for a
    library consumed from controllers/routes, exceptions at the boundary are more ergonomic
    than forcing `Result` on every caller.
- **Immutable collections in signatures.** Accept and return `List`/`Map` (read-only
  interfaces); build with `buildList`/`buildMap`/`associate` instead of appending to
  `mutableListOf`. Registry snapshots for parser construction are `toMap()` copies.
- **No shared mutable state in the hot path.** The visitor, resolver, and processors must be
  stateless and therefore trivially thread-safe; a single parser/visitor instance can serve
  concurrent requests. The operations registry is the sole mutable point (see above) and is
  written only at startup.
- **Sequence for multi-step pipelines only when it pays.** Selector token resolution is a
  short fold over `selector.split('.')` - use `fold` over an explicit loop with `var current`.
  Do not introduce `asSequence()` for 3-element chains; measure before optimizing.
- **Referential transparency in tests.** Because the core is pure, unit-test it as
  input -> output mapping (RSQL string -> expected SQL captured via Jimmer's `Executor`),
  no mocks. Mocking frameworks should be unnecessary in the entire library test suite;
  treat a needed mock as a design smell.

---

## 7. Implementation phases with acceptance criteria

1. **Skeleton + parser port.** Repo scaffold, CI, library module compiling with
   `RsqlOperation`, registry, parser factory, `ArgumentConvertor`, exceptions.
   AC: unit-level smoke via a temporary main - parse `name==x;year>2000` into an AST.
2. **SelectorResolver + scalar processors.** `get(String)` resolution, reference-association
   `outerJoin`, all comparison/in/null/ci processors, `toPredicate` entry point.
   AC: Spring Boot 4 example runs scalar and reference-join queries
   (`title==*SQL*`, `author.lastName=eqci=smith`, `rating=isNull=true`).
3. **Collection semantics.** EXISTS wrapping, nested collections, `=isEmpty=`.
   AC: all collection-path tests from the JPA suite pass with no distinct handling;
   assert generated SQL contains `exists` and no `distinct` (Jimmer's SQL can be captured
   via an `Executor` listener in tests).
4. **Postgres JSON operators.** `=jsonbeq=` / `=jsoneq=` via `sql()` fragments,
   `ParserContext.POSTGRESQL`.
   AC: JSON operator tests from the JPA example pass on Testcontainers Postgres.
5. **Ktor example.** Full example app + integration tests.
   AC: same test matrix green in Ktor.
6. **Docs + release.** README (version matrix: library version vs Jimmer version), CLAUDE.md,
   operator reference table with examples, nmcp publishing verified with a `-SNAPSHOT` dry run,
   `v0.1` release via the release workflow.

## 8. Risks and open questions

- **Embedded property API:** verify the exact weakly-typed navigation into `@Embeddable` props
  against the pinned Jimmer version early (phase 2); if `get("a.b")` dotted access is not
  supported, chain embedded `get` calls.
- **Negation semantics across EXISTS:** `reviews.rating!=5` means "has a review with rating != 5"
  (EXISTS + inner `ne`), matching the JPA join behavior. Document this explicitly and add a test;
  users sometimes expect "has no review with rating 5", which is `not(exists(rating==5))` and out
  of scope.
- **`in` with mixed-type coercion fallbacks:** when one argument fails to cast and falls back to
  a raw string, `valueIn` may get a heterogeneous list. Decide: fail fast with a clear error
  (recommended, differs from JPA version) or preserve silent fallback for parity.
- **Jimmer version pinning:** weakly-typed `KProps` methods are stable public API, but pin one
  Jimmer version in CI and state the tested range in README.
