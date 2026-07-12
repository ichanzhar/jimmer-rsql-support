# Phase 3: Collection Semantics - Design

Date: 2026-07-12
Status: Approved
Parent spec: `docs/jimmer-rsql-support-implementation-plan.md` (sections 4.3, 4.4, 7-phase-3, 8)
Builds on: `docs/superpowers/specs/2026-07-12-phase2-resolver-processors-design.md` (merged to main, PR #2)

## Scope

Library:

- `ResolvedSelector` grows `CollectionStep` and `CollectionTerminal` variants
- Visitor-level recursion builds collection predicates inside `KProps.exists(prop) { ... }`
  blocks; nested collections fall out of the recursion
- `Params` gains `val table: KProps<*>` (breaking pre-release change - decided; the
  library is 0.1.0-SNAPSHOT with zero releases; `.api` dump updates)
- `IsEmptyProcessor` replaces the `=isEmpty=` futureStub as a normal registry processor
- Deferred Phase 2 items land here: internal `RsqlOperationsRegistry.processorFor(operator)`
  accessor (removes the per-node `operationProcessors.toMap()` copy from the visitor),
  `Byte` coercion branch in `ArgumentConvertor`

Example (spring-boot4-postgres-example):

- `BookTag` / `ReviewLabel` child entities + schema + seed (tags/labels modeling - decided:
  child entities with adapted selectors `tags.tag==...` / `reviews.labels.label==...`)
- Ported collection test matrix + negation-semantics test + `=isEmpty=` tests
- SQL-shape assertions via a capturing Jimmer `Executor` (the phase's headline AC:
  `exists` present, no `distinct`)

Out of scope: JSON operators (Phase 4), Ktor example (Phase 5), wildcard-on-non-string
400 guard (backlog), any Spring dependency in the library.

## Design decision: recursion, not a post-hoc wrapper

The parent plan's `existsWrapper: (KNonNullExpression<Boolean>?) -> KNonNullExpression<Boolean>?`
sketch (section 4.3) is not implementable against the verified Jimmer 0.9.96 API: the
inner predicate must be BUILT inside the `exists(prop) { ... }` lambda because the
subquery table (`KImplicitSubQueryTable`, the lambda receiver, itself a `KProps`) exists
only there. A predicate pre-built against the outer table cannot be wrapped afterward.

Shipped design: `SelectorResolver` stays a pure token-walker returning a sealed step;
the visitor owns predicate construction and recurses through `exists` blocks. This
supersedes the parent plan's wrapper sketch with equivalent semantics.

## Resolver changes

`ResolvedSelector` (still internal, still sealed):

```kotlin
internal sealed interface ResolvedSelector {
    data class Scalar(
        val expression: KPropExpression<Any>,
        val prop: ImmutableProp,
        val castTarget: Class<out Any>,
    ) : ResolvedSelector

    data class CollectionStep(val token: String, val remainder: String) : ResolvedSelector

    data class CollectionTerminal(val prop: ImmutableProp) : ResolvedSelector
}
```

`resolveFromTable`'s collection branch (currently `isReferenceList || isScalarList ->
throw UnsupportedSelectorException`) becomes:

- `prop.isScalarList` -> `UnsupportedSelectorException(selector, "scalar collection
  property '$token' is not queryable")` (Jimmer scalar lists have no EXISTS support)
- `prop.isReferenceList(TargetLevel.ENTITY)`, tokens remain ->
  `CollectionStep(token, remainder = tokens.drop(1).joinToString("."))`
- `prop.isReferenceList(TargetLevel.ENTITY)`, terminal -> `CollectionTerminal(prop)`

`resolve` return type widens from `ResolvedSelector.Scalar` to `ResolvedSelector`.
All other branches unchanged. `resolveFromExpression` (embedded walk) unchanged -
embeddables cannot contain associations at `EmbeddedLevel.SCALAR`.

## Visitor changes

The comparison visit extracts to a private recursive builder:

```kotlin
private fun predicate(table: KProps<E>, selector: String, node: ComparisonNode): KNonNullExpression<Boolean>? =
    when (val resolved = SelectorResolver.resolve(table, selector)) {
        is ResolvedSelector.Scalar -> dispatch(resolved.expression, resolved.prop, resolved.castTarget, table, node)
        is ResolvedSelector.CollectionStep ->
            table.exists<Any>(resolved.token) { predicate(this, resolved.remainder, node) }
        is ResolvedSelector.CollectionTerminal ->
            dispatch(expression = null, resolved.prop, castTarget = String::class.java, table, node)
    }
```

(Exact generics resolved at implementation; `exists`'s lambda receiver is
`KImplicitSubQueryTable<*>` which is a `KProps<*>` - the recursion's table parameter
loosens to `KProps<*>` internally while the public visitor signature keeps `KProps<E>`.)

`dispatch` = cast args via `ArgumentConvertor`/`JavaTypeUtil` (for `CollectionTerminal`
the castTarget is `String::class.java`, whose coercion is the identity - `=isEmpty=`'s
boolean argument passes through as a string and the processor calls `toBoolean()`), build
`Params(expression, prop, args, argument, table)`, look up the factory via the NEW
internal accessor `RsqlOperationsRegistry.processorFor(node.operator)` (reads the
ConcurrentHashMap directly, no snapshot copy; public getters unchanged), throw
`JimmerRsqlSupportException` naming the operator if absent.

`CollectionTerminal` with any operator other than `=isEmpty=` reaches
`IsEmptyProcessor`-incompatible processors whose `requireNotNull(params.expression)`
fires with a generic message. Better: the visitor throws
`UnsupportedSelectorException(selector, "collection property '${prop.name}' supports
only =isEmpty=")` BEFORE dispatch when the resolved selector is `CollectionTerminal`
and the operator is not `RsqlOperation.IS_EMPTY.operator`. Explicit, tested.

## Params change (breaking, pre-release - decided)

```kotlin
public data class Params(
    public val expression: KPropExpression<Any>?,
    public val prop: ImmutableProp,
    public val args: List<Any>,
    public val argument: Any?,
    public val table: KProps<*>,
)
```

All 10 existing processors are untouched (they ignore `table`). Custom operators gain
the same table access the JPA version's `Params` provided via root/builder. `.api` dump
updates accordingly.

## IsEmptyProcessor

```kotlin
internal class IsEmptyProcessor(private val params: Params) : Processor {
    override fun process(): KNonNullExpression<Boolean>? {
        if (params.expression != null) {
            throw JimmerRsqlSupportException(
                "'=isEmpty=' applies only to collection properties, '${params.prop.name}' is not one",
            )
        }
        val exists = params.table.exists<Any>(params.prop.name) { null }
        return if (params.argument.toString().toBoolean()) exists?.not() else exists
    }
}
```

(The guard throws the library base exception, not `require`'s
`IllegalArgumentException`, so the example's advice maps it to 400.)

Registered in the seed map replacing `futureStub("=isEmpty=", "phase 3")`; the
`futureStub` helper stays for the two Phase 4 JSON operators.

Verification point for the plan: `exists(prop) { null }` with a null-returning block is
expected to yield the bare `EXISTS(subquery)` predicate (the verified signature returns
`KNonNullExpression<Boolean>?`). The implementer confirms the null-block behavior against
0.9.96 before relying on it; if it returns null, fall back to a tautological inner
predicate obtained from the subquery table's id: `{ get<Any>(idPropName).isNotNull() }`
- semantically equivalent under EXISTS. The SQL-shape test pins the final shape.

## Negation semantics (parent plan risk 8)

`reviews.rating!=5` means "there EXISTS a review with rating != 5", matching the JPA
join behavior - NOT "has no review with rating 5". Documented in `Api.kt` KDoc on
`toPredicate` and pinned by a test: seeded data yields exactly The Hobbit (its rating-4
review qualifies; Dune's only review is a 5).

## Example changes

Domain (package `com.github.ichanzhar.rsql.example.model`):

```kotlin
@Entity
interface BookTag {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long
    val tag: String
    @ManyToOne val book: Book?
}

@Entity
interface ReviewLabel {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long
    val label: String
    @ManyToOne val review: Review?
}
```

`Book` gains `@OneToMany(mappedBy = "book") val tags: List<BookTag>`; `Review` gains
`@OneToMany(mappedBy = "review") val labels: List<ReviewLabel>`.

Schema gains:

```sql
create table book_tag (
    id bigserial primary key,
    tag text not null,
    book_id bigint references book (id)
);

create table review_label (
    id bigserial primary key,
    label text not null,
    review_id bigint references review (id)
);
```

Truncate statement extends to include `book_tag, review_label`. Seed (matching the JPA
dataset): Hobbit tags fantasy+classic, Dune tags scifi+epic; labels - review 1
(Hobbit/5) editorial, review 2 (Hobbit/4) community, review 3 (Dune/5) urgent+editorial.

### New tests (existing assertions untouched)

| Query | Expectation |
|---|---|
| `tags.tag==classic` | 1, The Hobbit (single-level EXISTS on child entity) |
| `reviews.rating==5` | 2 (one-to-many EXISTS) |
| `reviews.rating!=5` | 1, The Hobbit (negation semantics pin) |
| `reviews.labels.label==urgent` | 1, Dune (two-level nested EXISTS) |
| `chapters.title==Prologue` | 1, Dune (list association) |
| `categories.name==Fantasy` | 1, The Hobbit (many-to-many EXISTS) |
| `author.name==*Tolkien*;reviews.labels.label==editorial` | 1, The Hobbit (join AND nested EXISTS) |
| `tags.nosuch==1` | 400, body mentions `nosuch` |
| `reviews==5` | 400, body mentions `=isEmpty=` (collection terminal, wrong operator) |
| `title=isEmpty=true` | 400 (scalar prop, IsEmptyProcessor guard) |
| `reviews=isEmpty=true` | 1, the extra book (see below) |
| `reviews=isEmpty=false` | 2 (the two seeded books) |

The two `=isEmpty=` tests insert a third book (id 3, 'The Silmarillion', isbn set,
year 1977, author 1, dimensions arbitrary, NO reviews/tags/chapters/categories) inside
their own test bodies via `jdbcTemplate` - `@BeforeEach` truncation cleans it up, so no
existing Phase 2 expectations change.

### SQL-shape assertions (headline AC)

`JimmerConfig.sqlClient` gains an optional executor:

```kotlin
@Bean
fun sqlClient(dataSource: DataSource, executorProvider: ObjectProvider<Executor>): KSqlClient =
    newKSqlClient {
        setConnectionManager(ConnectionManager.simpleConnectionManager(dataSource))
        setDialect(PostgresDialect())
        executorProvider.ifAvailable { setExecutor(it) }
    }
```

Tests contribute a `@TestConfiguration` capturing `Executor` bean: records `args.sql`
into a concurrent list, delegates to `DefaultExecutor` (Jimmer
`org.babyfish.jimmer.sql.runtime.Executor` / `DefaultExecutor`). Assertions (lowercased
SQL):

- `reviews.rating==5` -> contains `exists`, does NOT contain `distinct`
- `author.name==*Tolkien*` -> contains `left join`
- `reviews=isEmpty=true` -> contains `not exists`

Fallback if `Executor` in 0.9.96 has members that cannot be cleanly delegated: capture
Jimmer's SQL log output via a Logback `ListAppender` on the Jimmer logger with
`jimmer.show-sql` semantics - same three assertions. The plan's example task verifies
`Executor` first and records which route shipped.

## Acceptance criteria (parent plan phase 3)

1. Root `./gradlew build` green with updated `.api` dump (Params change).
2. Example `./gradlew test` green: all Phase 2 tests (17) still pass unchanged, plus the
   12 new tests above.
3. SQL-shape assertions prove EXISTS strategy: `exists` present, `distinct` absent, on
   collection-path queries.

## Risks

- `exists(prop) { null }` null-block behavior unverified - mitigation inline above.
- `Params` breaking change - accepted (pre-release, decided).
- Two-level nested EXISTS (`reviews.labels.label`) exercises recursive
  `KImplicitSubQueryTable` resolution - covered by a dedicated test; if Jimmer rejects
  nested implicit subqueries, that is a hard blocker to escalate, not to work around.
