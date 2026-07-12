# Phase 4: Postgres JSON Operators - Design

Date: 2026-07-12
Status: Approved
Parent spec: `docs/jimmer-rsql-support-implementation-plan.md` (sections 4.4, 7-phase-4)
Builds on: `docs/superpowers/specs/2026-07-12-phase3-collection-semantics-design.md` (merged to main, PR #3)

## Scope

Library:

- `JsonbEqualProcessor` and `JsonEqualProcessor` replace the two remaining `futureStub`
  registrations in `initDefaultPostgresOperation`; the `futureStub` helper is deleted
  (no stubs remain anywhere)
- Error-context carry-in (decided): nested-collection resolution errors report the
  original full selector, not the remainder

Example (spring-boot4-postgres-example):

- `Book.metadata` (jsonb) and `Book.details` (json) columns + seed
- `BookController` parses with `ParserContext.POSTGRESQL`
- 5 new tests + 1 strengthened assertion (36 total)

Out of scope (backlog, decided): strict `=isEmpty=` boolean parsing, wildcard-on-non-string
400 guard. Ktor example is Phase 5.

## JSON processors

Argument contract (JPA parity): exactly one argument of the form `path|value`; the path
dot-splits into keys (`publisher.name|Chilton` -> keys `publisher`, `name`, value
`Chilton`). Malformed input - no `|`, empty path, or empty value - throws
`JimmerRsqlSupportException("'=jsonbeq=' expects <json.path>|<value>, got '<argument>'")`
(the JPA original threw a bare `IllegalArgumentException`; ours maps to 400 through the
example's existing advice). A null/absent argument is malformed input too.

Fragment (decided: bound parameters, not the JPA original's literal interpolation - path
keys are user-controlled RSQL input and binds close the injection surface at zero
semantic cost; `jsonb_extract_path_text` accepts `text` arguments):

```
jsonb_extract_path_text(%e::jsonb, %v[, %v ...]) = %v
```

built dynamically for N keys and executed via the verified 0.9.96 API:

```kotlin
sql(Boolean::class, fragment) {
    expression(expr)
    keys.forEach { value(it) }
    value(value)
}
```

`org.babyfish.jimmer.sql.kt.ast.expression.sql` with `NativeDsl.expression/value` is
verified; the `%e`/`%v` placeholder format follows the parent plan and is a NAMED
VERIFICATION POINT for the implementer against `NativeDsl`'s parsing (check the class
or Jimmer's docs/tests before relying on it; adapt the placeholder tokens if the actual
syntax differs, keeping the bind structure).

The `%e::jsonb` cast is deliberate: identity when the column is already `jsonb`, and it
makes the operator work on `text` columns holding valid JSON - de-risking Jimmer's
String-prop-on-jsonb read mapping with no behavior change for real jsonb columns.

`JsonEqualProcessor` is identical with `json_extract_path_text(%e::json, ...)`.

Both processors `requireNotNull(params.expression)` with the operator symbol in the
message (same guard pattern as the other processors). Registration:

```kotlin
public fun initDefaultPostgresOperation() {
    registerOperation(RsqlOperation.JSON_EQ.operator) { JsonEqualProcessor(it) }
    registerOperation(RsqlOperation.JSONB_EQ.operator) { JsonbEqualProcessor(it) }
}
```

`futureStub` is removed. Shared argument parsing lives in a small internal function in
`operations/` (used by both processors), returning a parsed keys+value pair or throwing
the malformed-argument exception with the operator symbol threaded in.

## Error-context carry-in

`SelectorResolver.resolve(table, selector)` gains a third parameter
`context: String = selector` (internal API); all `UnknownPropertyException` /
`UnsupportedSelectorException` constructions use `context` instead of `selector`.
The visitor's `CollectionStep` branch recurses with
`resolve(this-table, resolved.remainder, context = original)` by passing the original
selector through `predicate`. Result: `tags.nosuch==1` reports
`Unknown property 'nosuch' in selector 'tags.nosuch' on entity 'BookTag'`.
Internal-only; api dump unchanged.

## Example changes

- `Book` gains `val metadata: String?` and `val details: String?` (plain nullable String
  props; Jimmer reads jsonb/json columns through JDBC `getString`).
- Schema: `metadata jsonb` and `details json` columns on `book`.
- Seed (in the test `@BeforeEach`, values cast explicitly):
  - Hobbit: `'{"genre":"fantasy","pages":310,"publisher":{"name":"Unwin"}}'::jsonb`,
    `'{"format":"hardcover"}'::json`
  - Dune: `'{"genre":"scifi","pages":412,"publisher":{"name":"Chilton"}}'::jsonb`,
    `'{"format":"paperback"}'::json`
  - The Silmarillion helper insert gains `null, null` for the two new columns
    (explicit columns list keeps it unambiguous).
- `BookController.search` passes the context:
  `sqlClient.createRsqlQuery(Book::class, query, ParserContext.POSTGRESQL)`.
- Documented fallback if Jimmer's String-prop read of jsonb fails at runtime: switch the
  two columns to `text` in schema + seed (drop the casts); the `%e::jsonb`/`%e::json`
  fragment casts already cover text storage. Record the switch if taken.

### New tests

| Query | Expectation |
|---|---|
| `metadata=jsonbeq=genre|scifi` | 1, Dune (JPA parity - the parent plan's phase AC) |
| `metadata=jsonbeq=publisher.name|Chilton` | 1, Dune (multi-key path) |
| `details=jsoneq=format|paperback` | 1, Dune (json variant) |
| `metadata=jsonbeq=nopipe` | 400, body mentions the expected `path|value` shape |
| `reviews.rating>=4` | exactly 2 (dedup carry-in: Hobbit has two qualifying reviews but appears once) |

Strengthened assertion: the existing `rejects unknown property inside collection path`
test's body check upgrades from `containsString("nosuch")` to
`containsString("tags.nosuch")`.

## Acceptance criteria (parent plan phase 4)

1. Root `./gradlew build` green; api dump unchanged (all new code internal).
2. Example `./gradlew test` green: 36 tests (31 existing + 5 new), including the
   `metadata=jsonbeq=genre|scifi` parity query on Testcontainers Postgres.

## Risks

- `%e`/`%v` placeholder syntax - named verification point above.
- Jimmer String-prop-on-jsonb read - documented text-column fallback above.
- The pipe character in RSQL argument values: `|` is legal inside rsql-parser argument
  tokens (the JPA version relies on the same syntax), so `path|value` parses as one
  argument. If the parser rejects it unquoted, tests may pass the argument quoted
  (`metadata=jsonbeq='genre|scifi'`) - JPA-compatible either way; note which form the
  tests use.
