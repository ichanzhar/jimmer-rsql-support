# Phase 4: Postgres JSON Operators Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `=jsonbeq=` and `=jsoneq=` work end to end on Testcontainers Postgres via bound-parameter `sql()` fragments, plus the two approved Phase 3 carry-ins (error context in nested selectors, functional dedup test).

**Architecture:** Two internal processors parse the JPA-parity `path|value` argument and emit `jsonb_extract_path_text(%e::jsonb, %v...) = %v` fragments with every key and the value bound; registered via `initDefaultPostgresOperation`, deleting the last `futureStub`s. Spec: `docs/superpowers/specs/2026-07-12-phase4-json-operators-design.md` (its verification points for `%e`/`%v` syntax and the jsonb read mapping are binding).

**Tech Stack:** unchanged (Kotlin 2.3.20/JDK 21, jimmer 0.9.96 compileOnly; example: Boot 4.1.0, manual KSqlClient, Testcontainers).

## Global Constraints

- Library tasks at the repo root; `./gradlew apiDump` and `./gradlew build` as SEPARATE invocations; the api dump must be UNCHANGED in every task of this phase (all new code internal).
- Example task from `examples/spring-boot4-postgres-example`; Docker required.
- `explicitApi()` strict; no comments (KDoc only where already present); plain hyphens; no wildcard imports; no `!!`; ktlintFormat on formatting failures, never hand-format.
- Existing test assertions unchanged EXCEPT the one strengthened assertion this plan names explicitly.
- Commit messages end with:

```
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
```

---

### Task 1: Error-context threading (carry-in)

**Files:**
- Modify: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/SelectorResolver.kt`
- Modify: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/JimmerRsqlVisitor.kt`

**Interfaces:**
- Consumes: current internal signatures `resolve(table, selector)`, visitor `predicate(table, selector, node)` / `dispatch(expression, prop, castTarget, table, node)`
- Produces: `resolve(table: KProps<*>, selector: String, context: String = selector): ResolvedSelector`; visitor `predicate(..., context: String)` and `dispatch(..., context: String)` - all resolution/coercion errors report the ORIGINAL full selector. Behavior pinned by Task 3's strengthened assertion.

- [ ] **Step 1: Thread context through the resolver**

In `SelectorResolver.kt`: change `resolve` to

```kotlin
    fun resolve(
        table: KProps<*>,
        selector: String,
        context: String = selector,
    ): ResolvedSelector = resolveFromTable(table, selector.split('.'), context)
```

and rename the `selector` parameter to `context` in BOTH private functions (`resolveFromTable`, `resolveFromExpression`), including their recursive call sites and every exception construction (`UnknownPropertyException(context, token, ...)`, `UnsupportedSelectorException(context, ...)`). No logic changes.

- [ ] **Step 2: Thread context through the visitor**

In `JimmerRsqlVisitor.kt`:
- `visit(ComparisonNode)` body becomes `predicate(table, node.selector, node, node.selector)`.
- `predicate` gains a fourth parameter `context: String`; the `resolve` call becomes `SelectorResolver.resolve(table, selector, context)`; the `CollectionStep` branch becomes `table.exists<Any>(resolved.token) { predicate(this, resolved.remainder, node, context) }`; the `CollectionTerminal` throw uses `context` instead of `selector`; both `dispatch` calls pass `context`.
- `dispatch` gains a final parameter `context: String` and its cast line becomes `node.arguments.map { ArgumentConvertor.castArgument(it, context, target) }`.

- [ ] **Step 3: Verify**

Run: `./gradlew apiDump` then `./gradlew build`
Expected: both BUILD SUCCESSFUL; `git status` shows only the two source files (dump unchanged - internal signatures only).

- [ ] **Step 4: Commit**

```bash
git add jimmer-rsql-support/src
git commit -m "feat: report the original selector in nested resolution errors"
```

---

### Task 2: JSON processors and registry wiring

**Files:**
- Create: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/operations/JsonArgument.kt`
- Create: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/operations/JsonbEqualProcessor.kt`
- Create: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/operations/JsonEqualProcessor.kt`
- Modify: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/utils/RsqlOperationsRegistry.kt`

**Interfaces:**
- Consumes: `Params` (5 fields incl. `table`), `Processor`, `JimmerRsqlSupportException`; verified 0.9.96 API `sql(type: KClass<T>, sql: String, block: NativeDsl.() -> Unit)` from `org.babyfish.jimmer.sql.kt.ast.expression` with `NativeDsl.expression(...)`/`value(...)`
- Produces: `internal data class JsonArgument(val keys: List<String>, val value: String)` + `internal fun parseJsonArgument(operatorSymbol: String, argument: Any?): JsonArgument`; the two processors; `initDefaultPostgresOperation` registers real factories; `futureStub` deleted

**NAMED VERIFICATION POINT (spec):** before relying on `%e`/`%v`, confirm the placeholder tokens against the actual 0.9.96 `NativeDsl` parsing (sources jar `NativeExpressions.kt` or javap). If the real tokens differ, adapt the fragment strings only - the bind structure (expression first, then key values, then the comparison value) stays. Record what you found in your report.

- [ ] **Step 1: Write `JsonArgument.kt`**

```kotlin
package com.github.ichanzhar.rsql.jimmer.operations

import com.github.ichanzhar.rsql.jimmer.exception.JimmerRsqlSupportException

internal data class JsonArgument(
    val keys: List<String>,
    val value: String,
)

internal fun parseJsonArgument(
    operatorSymbol: String,
    argument: Any?,
): JsonArgument {
    val raw =
        argument as? String
            ?: throw JimmerRsqlSupportException("'$operatorSymbol' expects <json.path>|<value>, got '$argument'")
    val separator = raw.indexOf('|')
    if (separator <= 0 || separator == raw.length - 1) {
        throw JimmerRsqlSupportException("'$operatorSymbol' expects <json.path>|<value>, got '$raw'")
    }
    val keys = raw.substring(0, separator).split('.')
    if (keys.any { it.isEmpty() }) {
        throw JimmerRsqlSupportException("'$operatorSymbol' expects <json.path>|<value>, got '$raw'")
    }
    return JsonArgument(keys, raw.substring(separator + 1))
}
```

- [ ] **Step 2: Write `JsonbEqualProcessor.kt`**

```kotlin
package com.github.ichanzhar.rsql.jimmer.operations

import org.babyfish.jimmer.sql.kt.ast.expression.KNonNullExpression
import org.babyfish.jimmer.sql.kt.ast.expression.sql

internal class JsonbEqualProcessor(private val params: Params) : Processor {
    override fun process(): KNonNullExpression<Boolean> {
        val expression = requireNotNull(params.expression) { "'=jsonbeq=' requires a property expression" }
        val argument = parseJsonArgument("=jsonbeq=", params.argument)
        val keyPlaceholders = argument.keys.joinToString(", ") { "%v" }
        return sql(Boolean::class, "jsonb_extract_path_text(%e::jsonb, $keyPlaceholders) = %v") {
            expression(expression)
            argument.keys.forEach { value(it) }
            value(argument.value)
        }
    }
}
```

- [ ] **Step 3: Write `JsonEqualProcessor.kt`**

Identical shape with the json function, cast, and symbol:

```kotlin
package com.github.ichanzhar.rsql.jimmer.operations

import org.babyfish.jimmer.sql.kt.ast.expression.KNonNullExpression
import org.babyfish.jimmer.sql.kt.ast.expression.sql

internal class JsonEqualProcessor(private val params: Params) : Processor {
    override fun process(): KNonNullExpression<Boolean> {
        val expression = requireNotNull(params.expression) { "'=jsoneq=' requires a property expression" }
        val argument = parseJsonArgument("=jsoneq=", params.argument)
        val keyPlaceholders = argument.keys.joinToString(", ") { "%v" }
        return sql(Boolean::class, "json_extract_path_text(%e::json, $keyPlaceholders) = %v") {
            expression(expression)
            argument.keys.forEach { value(it) }
            value(argument.value)
        }
    }
}
```

- [ ] **Step 4: Rewire the registry**

In `RsqlOperationsRegistry.kt`: replace `initDefaultPostgresOperation` and delete `futureStub`:

```kotlin
    public fun initDefaultPostgresOperation() {
        registerOperation(RsqlOperation.JSON_EQ.operator) { JsonEqualProcessor(it) }
        registerOperation(RsqlOperation.JSONB_EQ.operator) { JsonbEqualProcessor(it) }
    }
```

Add imports for `JsonEqualProcessor`/`JsonbEqualProcessor`; remove the now-unused `Processor` import if ktlint flags it (the seed map lambdas no longer reference `Processor` directly once `futureStub` is gone - keep whatever imports the compiler actually needs).

- [ ] **Step 5: Verify**

Run: `./gradlew apiDump` then `./gradlew build`
Expected: both BUILD SUCCESSFUL; dump unchanged (`git status`).

- [ ] **Step 6: Commit**

```bash
git add jimmer-rsql-support/src
git commit -m "feat: jsonb/json equality processors with bound-parameter fragments"
```

---

### Task 3: Example - JSON columns, Postgres context, tests

**Files (all under `examples/spring-boot4-postgres-example/`):**
- Modify: `src/main/kotlin/com/github/ichanzhar/rsql/example/model/Book.kt`
- Modify: `src/main/resources/schema.sql`
- Modify: `src/main/kotlin/com/github/ichanzhar/rsql/example/web/BookController.kt`
- Modify: `src/test/kotlin/com/github/ichanzhar/rsql/example/web/BookControllerIntegrationTest.kt`

**Interfaces:**
- Consumes: Tasks 1-2 end to end; `ParserContext` from `com.github.ichanzhar.rsql.jimmer`
- Produces: Phase 4 acceptance evidence (36 tests)

- [ ] **Step 1: Add the JSON props to `Book.kt`**

After the `publicationYear` property:

```kotlin
    val metadata: String?
    val details: String?
```

- [ ] **Step 2: Extend `schema.sql`**

In the `book` table definition, after `weight_grams int not null`, add:

```sql
    metadata jsonb,
    details json
```

(mind the comma on the preceding line).

- [ ] **Step 3: Pass the Postgres context in `BookController.kt`**

Change the RSQL branch to `sqlClient.createRsqlQuery(Book::class, query, ParserContext.POSTGRESQL)` and add the import `com.github.ichanzhar.rsql.jimmer.ParserContext`.

- [ ] **Step 4: Extend the seed**

Replace the `book` insert in the `@BeforeEach` seed block with:

```sql
            insert into book (id, title, isbn, publication_year, author_id, width_cm, height_cm, weight_grams, metadata, details) values
                (1, 'The Hobbit', '9780618260300', 1937, 1, 13.0, 20.0, 340,
                 '{"genre":"fantasy","pages":310,"publisher":{"name":"Unwin"}}'::jsonb, '{"format":"hardcover"}'::json),
                (2, 'Dune', null, 1965, 2, 15.0, 23.0, 480,
                 '{"genre":"scifi","pages":412,"publisher":{"name":"Chilton"}}'::jsonb, '{"format":"paperback"}'::json);
```

and change `insertBookWithoutRelations`'s statement to include the new columns:

```kotlin
            "insert into book (id, title, isbn, publication_year, author_id, width_cm, height_cm, weight_grams, metadata, details) " +
                "values (3, 'The Silmarillion', '9780048231390', 1977, 1, 14.0, 21.0, 400, null, null)",
```

- [ ] **Step 5: Strengthen the error-context assertion**

In the test `rejects unknown property inside collection path with 400`, change
`containsString("nosuch")` to `containsString("tags.nosuch")` (pins Task 1).

- [ ] **Step 6: Add the 5 new tests**

```kotlin
    @Test
    fun `filters by jsonb path equality`() {
        mockMvc.perform(get("/books").param("query", "metadata=jsonbeq=genre|scifi"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("Dune"))
    }

    @Test
    fun `filters by nested jsonb path`() {
        mockMvc.perform(get("/books").param("query", "metadata=jsonbeq=publisher.name|Chilton"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("Dune"))
    }

    @Test
    fun `filters by json path equality`() {
        mockMvc.perform(get("/books").param("query", "details=jsoneq=format|paperback"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("Dune"))
    }

    @Test
    fun `rejects malformed json operator argument with 400`() {
        mockMvc.perform(get("/books").param("query", "metadata=jsonbeq=nopipe"))
            .andExpect(status().isBadRequest)
            .andExpect(content().string(containsString("<json.path>|<value>")))
    }

    @Test
    fun `collection match yields one row per parent`() {
        mockMvc.perform(get("/books").param("query", "reviews.rating>=4"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }
```

- [ ] **Step 7: Run the suite**

Run from the example dir: `./gradlew test`
Expected: 36/36 pass. Debug order: (1) parser rejects `=jsonbeq=` - the controller context change (Step 3) is missing; (2) fragment errors mentioning placeholders - the spec's `%e`/`%v` verification point (fix the library fragment tokens, not the tests); (3) jsonb read errors on `select` - apply the spec's documented fallback (columns become `text`, drop the seed casts, fragments unchanged) and record it; (4) `|` tokenization errors from rsql-parser - quote the argument in tests (`metadata=jsonbeq='genre|scifi'`), JPA-compatible, record it.

- [ ] **Step 8: Commit**

```bash
git add examples/spring-boot4-postgres-example
git commit -m "test: json operator matrix with postgres context and dedup pin"
```

---

### Task 4: Final verification and CLAUDE.md refresh

**Files:**
- Modify: `CLAUDE.md` (repo root)

- [ ] **Step 1: Verify both builds**

```bash
cd /Users/user/IdeaProjects/jimmer-rsql-support && ./gradlew build
cd examples/spring-boot4-postgres-example && ./gradlew test
```

Expected: both BUILD SUCCESSFUL (36 tests).

- [ ] **Step 2: Update CLAUDE.md's current-state paragraph**

Replace the paragraph starting `**Current state: phases 1-3 done.**` with:

```markdown
**Current state: phases 1-4 done.** The full operator set is implemented: parser layer,
SelectorResolver (scalar, reference, embedded, collections via implicit EXISTS at any
depth), all COMMON operators, and the Postgres-only `=jsonbeq=`/`=jsoneq=`
(bound-parameter `sql()` fragments, `path|value` argument syntax, registered via
`ParserContext.POSTGRESQL`). The spring-boot4-postgres-example suite is the acceptance
evidence. Remaining: Ktor example (phase 5), docs + Maven Central release (phase 6).
The authoritative spec is `docs/jimmer-rsql-support-implementation-plan.md`. Note:
jimmer-spring-boot-starter 0.9.96 is incompatible with Spring Boot 4.1.0; the example
wires a manual `KSqlClient` bean over `jimmer-sql-kotlin` instead.
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: mark phases 1-4 complete in CLAUDE.md"
```
