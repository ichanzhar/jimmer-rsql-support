# Phase 6: Docs, Guards, Release Readiness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 0.1.0 is ready to release: two behavior guards shipped and tested in both examples, a real README with the full operator reference, doc debt paid, and the publication verified locally.

**Architecture:** Guards are two small internal processor changes (strict `=isEmpty=` boolean, wildcard-requires-string) with 400-tests in both example suites; the README replaces the 2-line stub; the version flips to `0.1.0` everywhere; `publishToMavenLocal` proves the artifact set. Spec: `docs/superpowers/specs/2026-07-13-phase6-docs-release-design.md`. Work continues on branch `feat/phase5` (owner decision).

**Tech Stack:** unchanged.

## Global Constraints

- Library edits: run `./gradlew apiDump` and `./gradlew build` at the repo root as SEPARATE invocations; the `.api` dump must be UNCHANGED in every task (all changes internal or docs).
- Example suites run from their own directories; Docker required for Task 1.
- No comments in code; plain hyphens, no em dashes, no emoji (README included).
- Every factual claim in the README must match shipped behavior - the operator examples reuse the `RsqlOperation` KDoc examples.
- Commit messages end with:

```
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
```

---

### Task 1: Behavior guards + tests in both examples

**Files:**
- Modify: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/operations/Wildcards.kt`
- Modify: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/operations/EqualProcessor.kt`
- Modify: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/operations/NotEqualProcessor.kt`
- Modify: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/operations/IsEmptyProcessor.kt`
- Test: `examples/spring-boot4-postgres-example/src/test/kotlin/com/github/ichanzhar/rsql/example/web/BookControllerIntegrationTest.kt`
- Test: `examples/ktor-postgres-example/src/test/kotlin/com/github/ichanzhar/rsql/example/BookRoutesIntegrationTest.kt`

**Interfaces:**
- Consumes: existing `Params`, `isLikeExpression`, `JimmerRsqlSupportException`
- Produces: `internal fun requireStringProperty(prop: ImmutableProp)` in `Wildcards.kt`; guard behavior the README's Task 3 "Behavior notes" section documents

- [ ] **Step 1: Add the failing tests to the Boot suite**

Append to `BookControllerIntegrationTest`:

```kotlin
    @Test
    fun `rejects wildcard on non-string property with 400`() {
        mockMvc.perform(get("/books").param("query", "publicationYear==19*"))
            .andExpect(status().isBadRequest)
            .andExpect(content().string(containsString("wildcard")))
    }

    @Test
    fun `rejects non-boolean isEmpty argument with 400`() {
        mockMvc.perform(get("/books").param("query", "reviews=isEmpty=maybe"))
            .andExpect(status().isBadRequest)
            .andExpect(content().string(containsString("true or false")))
    }
```

- [ ] **Step 2: Run them to verify they fail for the right reason**

Run from `examples/spring-boot4-postgres-example/`:
`./gradlew test --tests 'BookControllerIntegrationTest'`
Expected: exactly these 2 FAIL (wildcard test gets a 500 from Postgres; isEmpty test gets 200 treating `maybe` as false); the other 36 pass.

- [ ] **Step 3: Implement the guards**

`Wildcards.kt` - append:

```kotlin
internal fun requireStringProperty(prop: ImmutableProp) {
    if (prop.returnClass != String::class.java) {
        throw JimmerRsqlSupportException(
            "wildcard '*' requires a string property, '${prop.name}' is '${prop.returnClass.simpleName}'",
        )
    }
}
```

with imports `com.github.ichanzhar.rsql.jimmer.exception.JimmerRsqlSupportException` and `org.babyfish.jimmer.meta.ImmutableProp`.

`EqualProcessor.kt` - the wildcard branch becomes:

```kotlin
        return if (isLikeExpression(params.argument)) {
            requireStringProperty(params.prop)
            (expression as KExpression<String>).like(likePattern(params.argument), LikeMode.EXACT)
        } else {
            expression.eq(params.argument)
        }
```

`NotEqualProcessor.kt` - same insertion (`requireStringProperty(params.prop)` as the first line of the wildcard branch, before the cast).

`IsEmptyProcessor.kt` - the final line

```kotlin
        return if (params.argument.toString().toBoolean()) exists?.not() else exists
```

becomes

```kotlin
        val empty =
            params.argument.toString().toBooleanStrictOrNull()
                ?: throw JimmerRsqlSupportException("'=isEmpty=' expects true or false, got '${params.argument}'")
        return if (empty) exists?.not() else exists
```

- [ ] **Step 4: Build the library**

Run at the repo root: `./gradlew apiDump` then `./gradlew build`
Expected: both BUILD SUCCESSFUL; `git status` shows NO `.api` change (all internal).

- [ ] **Step 5: Run the Boot suite green**

Run from `examples/spring-boot4-postgres-example/`: `./gradlew test`
Expected: 38/38 PASS.

- [ ] **Step 6: Port the two tests to the Ktor suite**

Append to `BookRoutesIntegrationTest`:

```kotlin
    @Test
    fun `rejects wildcard on non-string property with 400`() = assertBadRequest("publicationYear==19*", "wildcard")

    @Test
    fun `rejects non-boolean isEmpty argument with 400`() = assertBadRequest("reviews=isEmpty=maybe", "true or false")
```

- [ ] **Step 7: Run the Ktor suite green**

Run from `examples/ktor-postgres-example/`: `./gradlew test`
Expected: 38/38 PASS.

- [ ] **Step 8: Commit**

```bash
git add jimmer-rsql-support/src examples
git commit -m "feat: strict isEmpty booleans and wildcard-requires-string guard"
```

---

### Task 2: Version flip and example polish

**Files:**
- Modify: `jimmer-rsql-support/build.gradle.kts:13`
- Modify: `examples/spring-boot4-postgres-example/build.gradle.kts:31`
- Modify: `examples/ktor-postgres-example/build.gradle.kts:31`
- Modify: `examples/ktor-postgres-example/src/main/kotlin/com/github/ichanzhar/rsql/example/BookModule.kt:32`

**Interfaces:**
- Consumes: nothing new
- Produces: version `0.1.0` everywhere - Task 5's release checks depend on it

- [ ] **Step 1: Flip the versions**

- `jimmer-rsql-support/build.gradle.kts`: `version = "0.1.0-SNAPSHOT"` -> `version = "0.1.0"`
- Both examples' dependency line: `implementation("com.github.ichanzhar:jimmer-rsql-support:0.1.0-SNAPSHOT")` -> `...:0.1.0")`

- [ ] **Step 2: Fix the Ktor error-body asymmetry**

`BookModule.kt` line 32: `mapOf("error" to cause.message)` ->
`mapOf("error" to (cause.message ?: "invalid RSQL query"))`

- [ ] **Step 3: Verify**

```bash
cd /Users/user/IdeaProjects/jimmer-rsql-support && ./gradlew build
sed -n 's/^version = "\(.*\)"/\1/p' jimmer-rsql-support/build.gradle.kts
cd examples/spring-boot4-postgres-example && ./gradlew compileKotlin
cd ../ktor-postgres-example && ./gradlew compileKotlin
```

Expected: root build green, sed prints exactly `0.1.0`, both examples compile.

- [ ] **Step 4: Commit**

```bash
git add jimmer-rsql-support/build.gradle.kts examples
git commit -m "chore: version 0.1.0 and ktor error-body default"
```

---

### Task 3: README

**Files:**
- Modify: `README.md` (replace the 2-line stub entirely)

**Interfaces:**
- Consumes: shipped behavior from all phases (the content below is written against it)
- Produces: the release documentation

- [ ] **Step 1: Replace `README.md` with exactly this content**

````markdown
# jimmer-rsql-support

RSQL support for [Jimmer ORM](https://github.com/babyfish-ct/jimmer). Translates RSQL
query strings (parsed by [rsql-parser](https://github.com/jirutka/rsql-parser)) into
Jimmer `KNonNullExpression<Boolean>` predicates, with association-path support and
collection filtering via implicit EXISTS subqueries - no joins, no `DISTINCT`, no row
fanout. Ported from
[rsql-hibernate-jpa](https://github.com/ichanzhar/rsql-hibernate-jpa).

```
GET /books?query=author.name==*Tolkien*;reviews.rating>=4;publicationYear=in=(1937,1965)
```

## Installation

```kotlin
dependencies {
    implementation("com.github.ichanzhar:jimmer-rsql-support:0.1.0")
}
```

```xml
<dependency>
    <groupId>com.github.ichanzhar</groupId>
    <artifactId>jimmer-rsql-support</artifactId>
    <version>0.1.0</version>
</dependency>
```

The library declares `jimmer-sql-kotlin` as `compileOnly`: your application brings its
own Jimmer (directly or via a starter). Tested against Jimmer 0.9.96.

## Quick start

The primitive is `Node.toPredicate(table)` - usable inside any Jimmer query block:

```kotlin
val node = RsqlParserFactory.instance(ParserContext.POSTGRESQL).parse(query)
val books = sqlClient
    .createQuery(Book::class) {
        node.toPredicate(table)?.let { where(it) }
        select(table)
    }
    .execute()
```

`KSqlClient.createRsqlQuery` is the one-line sugar (parse + where + select):

```kotlin
val books = sqlClient.createRsqlQuery(Book::class, query, ParserContext.POSTGRESQL).execute()
```

Omit the `ParserContext.POSTGRESQL` argument if you do not need the JSON operators.

## Operator reference

| Operator | Example | Meaning |
|---|---|---|
| `==` | `title==Dune`, `title==*SQL*` | Equality; `*` wildcards translate to SQL `LIKE` (string properties only) |
| `!=` | `title!=Dune`, `title!=*SQL*` | Inequality; wildcards as above, negated |
| `>` / `=gt=` | `year>2000` | Greater than |
| `>=` / `=ge=` | `year>=2000` | Greater than or equal |
| `<` / `=lt=` | `year<2000` | Less than |
| `<=` / `=le=` | `year<=2000` | Less than or equal |
| `=in=` | `year=in=(2000,2001)` | Membership |
| `=out=` | `year=out=(2000,2001)` | Exclusion |
| `=isNull=` | `rating=isNull=true` | Null check (`true`/`false`); on a reference property it checks the foreign key without a join |
| `=eqci=` | `lastName=eqci=smith` | Case insensitive equality (ILIKE) |
| `=isEmpty=` | `reviews=isEmpty=true` | Collection emptiness (`true`/`false`, strict); collection properties only |
| `=jsonbeq=` | `attributes=jsonbeq=color\|red` | PostgreSQL jsonb path equality (see below) |
| `=jsoneq=` | `attributes=jsoneq=color\|red` | PostgreSQL json path equality (see below) |

### JSON operator argument grammar

The single argument is `path|value`: the FIRST `|` separates the path from the value
(values may contain further `|` characters, path keys cannot). The path splits on `.`
into jsonb/json object keys - `metadata=jsonbeq=publisher.name|Chilton` compares
`metadata -> 'publisher' ->> 'name'` to `Chilton`. JSON keys that themselves contain a
dot are not addressable. Keys and value are sent as bound parameters
(`jsonb_extract_path_text`), so no user input reaches the SQL text. The column may be
`jsonb`/`json` or a `text` column holding valid JSON.

## Selectors

Selectors are dotted property paths resolved against Jimmer metadata:

- **Scalar** properties compare directly: `title==Dune`.
- **Reference associations** (many-to-one, one-to-one) resolve via LEFT JOIN:
  `author.name==*Tolkien*`. A terminal reference compares the foreign key without a
  join: `author==5`, `author=isNull=true`.
- **Embedded** properties chain at any depth: `dimensions.weightGrams=gt=400`.
- **Collection associations** (one-to-many, many-to-many) become implicit EXISTS
  subqueries at any nesting depth: `reviews.rating==5`,
  `reviews.labels.label==urgent`. No joins, no `DISTINCT`, no duplicate rows.
- `=isEmpty=true/false` applies to a bare collection property: `reviews=isEmpty=true`.
- Scalar list properties (Jimmer `@Serialized` lists) are not queryable.

**Negation across collections follows EXISTS semantics:** `reviews.rating!=5` matches
entities having AT LEAST ONE review whose rating differs from 5 - not entities having
no review with rating 5. This matches the JPA library's join behavior.

Unknown properties and unsupported selectors throw with the full selector in the
message: `Unknown property 'nosuch' in selector 'tags.nosuch' on entity 'BookTag'`.

## PostgreSQL context and custom operators

`RsqlParserFactory.instance(ParserContext.POSTGRESQL)` registers the two JSON
operators before building the parser. Registration is process-wide and one-way: once
registered, parsers built afterwards (including without the context argument) also
accept the JSON operators.

Custom operators register the same way as in rsql-hibernate-jpa:

```kotlin
RsqlOperationsRegistry.registerOperation(ComparisonOperator("=myop=")) { params ->
    Processor { /* build a KNonNullExpression<Boolean> from params */ }
}
```

`Params` carries the resolved property expression, the `ImmutableProp`, the converted
arguments, and the table (`KProps<*>`) for subquery-building operators. Registered
overrides of the built-in JSON operators are never clobbered by later
`ParserContext.POSTGRESQL` parser construction.

## Error handling

All library exceptions extend `JimmerRsqlSupportException` (`RuntimeException`):
`UnknownPropertyException`, `UnsupportedSelectorException`,
`InvalidDateFormatException`, `InvalidEnumValueException`, and guard violations
(malformed JSON arguments, non-boolean `=isEmpty=`, wildcards on non-string
properties). Map it - together with rsql-parser's `RSQLParserException` - to HTTP 400
with one handler; both examples show the pattern (Spring `@RestControllerAdvice`,
Ktor `StatusPages`).

Argument coercion converts to the property's type (numbers, booleans, UUID, dates,
enums); on conversion failure the raw string is passed through, except dates and
enums, which throw.

## Version matrix

| jimmer-rsql-support | Jimmer (tested) | Kotlin | JDK |
|---|---|---|---|
| 0.1.0 | 0.9.96 | 2.x | 21 |

## Examples

Two fully independent example builds (own Gradle wrappers, NOT part of the root
build), each with the same 38-test Testcontainers integration suite - Docker required:

```
cd examples/spring-boot4-postgres-example && ./gradlew test
cd examples/ktor-postgres-example && ./gradlew test
```

- **spring-boot4-postgres-example** - Spring Boot 4. Note:
  `jimmer-spring-boot-starter` 0.9.96 is incompatible with Spring Boot 4.1.0, so the
  example wires a manual `KSqlClient` bean over `jimmer-sql-kotlin` +
  `spring-boot-starter-jdbc`.
- **ktor-postgres-example** - Ktor 3 with zero Spring on the classpath: HikariCP,
  manual `KSqlClient`, blocking Jimmer calls isolated in `withContext(Dispatchers.IO)`.

Both consume the library source via `includeBuild("../..")` with dependency
substitution; replace that with the published coordinate in your own project. The
example wiring uses Jimmer's `simpleConnectionManager` (no Spring/transaction
integration - demo wiring, not a transactional-production template), and the Ktor
example's schema init naively splits `schema.sql` on `;` (fine for its plain DDL).

## License

MIT
````

- [ ] **Step 2: Sanity-check rendering**

Run: `grep -c '^|' README.md` (expect a positive count; tables intact) and eyeball
the file for broken fences (```` ``` ```` pairs balanced: `grep -c '^```' README.md`
must print an even number... note the JSON grammar section uses inline code only).

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: readme with operator reference and release documentation"
```

---

### Task 4: Doc debt - parent plan and CLAUDE.md

**Files:**
- Modify: `docs/jimmer-rsql-support-implementation-plan.md:236-237`
- Modify: `CLAUDE.md` (current-state paragraph)

- [ ] **Step 1: Update the parent plan's JSON operator rows**

Replace lines 236-237:

```markdown
| `=jsonbeq=` | `JsonbEqualProcessor` | native fragment: `sql(Boolean::class, "(%e ->> '<key>') = %v") { expression(expr); value(v) }` - key comes from the argument syntax used in the JPA version, keep it identical |
| `=jsoneq=` | `JsonEqualProcessor` | same with `->>'` on json column |
```

with:

```markdown
| `=jsonbeq=` | `JsonbEqualProcessor` | shipped (phase 4): `sql(Boolean::class, "jsonb_extract_path_text(%e::jsonb, %v[, %v...]) = %v")` - argument `path|value`, first-pipe split, dot-separated keys, keys and value BOUND (deviation from the JPA literal interpolation, closes the injection surface) |
| `=jsoneq=` | `JsonEqualProcessor` | same with `json_extract_path_text(%e::json, ...)` |
```

- [ ] **Step 2: Update CLAUDE.md's current-state paragraph**

Replace the paragraph starting `**Current state: phases 1-5 done.**` with:

```markdown
**Current state: all six phases done - 0.1.0 release-ready.** Full operator set
(parser layer, SelectorResolver with EXISTS collections, all COMMON operators,
Postgres `=jsonbeq=`/`=jsoneq=` with bound-parameter fragments), behavior guards
(strict `=isEmpty=` booleans, wildcard-requires-string), README with the operator
reference, and both example suites at 38 tests each. Release is owner-triggered via
the manual GitHub release workflow (nmcp -> Maven Central, tags v0.1.0). Note:
jimmer-spring-boot-starter 0.9.96 is incompatible with Spring Boot 4.1.0; the Boot
example wires a manual `KSqlClient` bean over `jimmer-sql-kotlin` instead.
```

- [ ] **Step 3: Commit**

```bash
git add docs/jimmer-rsql-support-implementation-plan.md CLAUDE.md
git commit -m "docs: align parent plan json rows with shipped fragments, final CLAUDE.md state"
```

---

### Task 5: Release readiness verification

**Files:** none committed (verification only; report is the deliverable)

- [ ] **Step 1: Publish locally**

Run at the repo root: `./gradlew publishToMavenLocal`
Expected: BUILD SUCCESSFUL. If it fails on a signing task (no `SIGNING_KEY` in the
environment), re-run excluding it - find the exact task name in the failure output
(likely `signMavenJavaPublication`) and use
`./gradlew publishToMavenLocal -x signMavenJavaPublication`. Record which command
succeeded - signing itself is exercised only in the release workflow where the key
exists.

- [ ] **Step 2: Inspect the published artifacts**

```bash
ls ~/.m2/repository/com/github/ichanzhar/jimmer-rsql-support/0.1.0/
```

Expected: `jimmer-rsql-support-0.1.0.jar`, `-sources.jar`, `-javadoc.jar`, `.pom`
(plus checksums/module metadata). Then verify the POM:

```bash
P=~/.m2/repository/com/github/ichanzhar/jimmer-rsql-support/0.1.0/jimmer-rsql-support-0.1.0.pom
grep -c 'SNAPSHOT' $P; grep -o '<name>Jimmer RSQL Support</name>' $P
grep -o '<url>http://www.opensource.org/licenses/mit-license.php</url>' $P
grep -o 'ichanzhar/jimmer-rsql-support' $P | head -1
grep -o '<artifactId>rsql-parser</artifactId>' $P
```

Expected: SNAPSHOT count 0; name, MIT license url, scm slug, and the rsql-parser
compile dependency all present. Also confirm the jimmer dependency does NOT appear
as a `compile`/`runtime` dependency in the POM (compileOnly must not leak):
`grep -A2 'jimmer' $P` should show nothing or provided-scope only.

- [ ] **Step 3: Confirm the release workflow inputs**

```bash
sed -n 's/^version = "\(.*\)"/\1/p' jimmer-rsql-support/build.gradle.kts
```

Expected: `0.1.0` (matches `release.yaml`'s extraction; the SNAPSHOT guard passes;
the workflow would tag `v0.1.0`).

- [ ] **Step 4: Full final verification**

```bash
cd /Users/user/IdeaProjects/jimmer-rsql-support && ./gradlew build
cd examples/spring-boot4-postgres-example && ./gradlew test
cd ../ktor-postgres-example && ./gradlew test
```

Expected: all green, 38 + 38 tests. No commit in this task; the report carries the
evidence.
