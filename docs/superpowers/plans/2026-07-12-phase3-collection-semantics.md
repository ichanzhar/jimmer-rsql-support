# Phase 3: Collection Semantics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collection-association selectors resolve into Jimmer implicit EXISTS subqueries (nested at any depth), `=isEmpty=` works, and SQL-shape assertions prove the no-DISTINCT strategy.

**Architecture:** `ResolvedSelector` gains `CollectionStep`/`CollectionTerminal`; the visitor owns a recursive `predicate` builder that recurses inside `KProps.exists(token) { ... }` blocks; `Params` gains `table: KProps<*>` (approved pre-release break) so `IsEmptyProcessor` is a normal registry processor. Spec: `docs/superpowers/specs/2026-07-12-phase3-collection-semantics-design.md`.

**Tech Stack:** unchanged from Phase 2 (Kotlin 2.3.20/JDK 21, jimmer 0.9.96 compileOnly; example: Boot 4.1.0, manual KSqlClient bean, Testcontainers).

## Global Constraints

- Library tasks run at the repo root; run `./gradlew apiDump` and `./gradlew build` as SEPARATE invocations (combined run trips a known Gradle implicit-dependency error). Task 2 changes the public `Params` - its commit includes the updated `jimmer-rsql-support/api/jimmer-rsql-support.api`; other library tasks must leave the dump unchanged.
- Example tasks run from `examples/spring-boot4-postgres-example` (own wrapper); Docker required.
- `explicitApi()` strict; no comments except KDoc on public API; plain hyphens, no em dashes; no wildcard imports; no `!!`; `@Suppress("UNCHECKED_CAST")` smallest scope.
- If ktlintCheck fails on formatting, `./gradlew ktlintFormat` and re-run; never hand-format.
- Existing Phase 2 test assertions must not change; the 17 existing example tests must stay green.
- Commit messages end with:

```
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
```

---

### Task 1: Registry accessor and Byte coercion (deferred Phase 2 items)

**Files:**
- Modify: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/utils/RsqlOperationsRegistry.kt`
- Modify: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/utils/ArgumentConvertor.kt`

**Interfaces:**
- Consumes: existing registry/convertor internals
- Produces: `internal fun RsqlOperationsRegistry.processorFor(operator: ComparisonOperator): ProcessorParamsBuilder?` (direct ConcurrentHashMap read, no snapshot copy) - Task 2's visitor calls it; a `Byte` branch in `castArgument`

- [ ] **Step 1: Add `processorFor` to the registry object**

Insert after the `operations` val (before `registerOperation`):

```kotlin
    internal fun processorFor(operator: ComparisonOperator): ProcessorParamsBuilder? = processors[operator]
```

Public getters (`operationProcessors`, `operations`) stay exactly as they are.

- [ ] **Step 2: Add the Byte branch to `castArgument`**

In the `when (javaType)` block, directly after the `Short::class.javaObjectType` branch, add:

```kotlin
            Byte::class.javaObjectType -> arg.toByteOrNull() ?: arg
```

- [ ] **Step 3: Verify**

Run: `./gradlew apiDump` then `./gradlew build`
Expected: both BUILD SUCCESSFUL; `git status` shows only the two source files (dump unchanged - `processorFor` is internal).

- [ ] **Step 4: Commit**

```bash
git add jimmer-rsql-support/src
git commit -m "feat: registry processorFor accessor and Byte coercion branch"
```

---

### Task 2: Collection resolution, Params.table, visitor recursion, IsEmptyProcessor

**Files:**
- Modify: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/ResolvedSelector.kt`
- Modify: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/SelectorResolver.kt`
- Modify: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/operations/Params.kt`
- Create: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/operations/IsEmptyProcessor.kt`
- Modify: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/utils/RsqlOperationsRegistry.kt`
- Modify: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/JimmerRsqlVisitor.kt`
- Modify: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/Api.kt` (KDoc only)

**Interfaces:**
- Consumes: `processorFor` from Task 1; verified Jimmer API `KProps.exists(prop: String, block: KImplicitSubQueryTable<X>.() -> KNonNullExpression<Boolean>?): KNonNullExpression<Boolean>?`
- Produces: `ResolvedSelector.CollectionStep(token: String, remainder: String)` and `CollectionTerminal(prop: ImmutableProp)`; `SelectorResolver.resolve(table, selector): ResolvedSelector` (widened); `Params(expression, prop, args, argument, table: KProps<*>)` - the shape Tasks 3-5's tests exercise end to end

- [ ] **Step 1: Extend `ResolvedSelector.kt`**

Replace the file body so the sealed interface reads:

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

    data class CollectionStep(
        val token: String,
        val remainder: String,
    ) : ResolvedSelector

    data class CollectionTerminal(
        val prop: ImmutableProp,
    ) : ResolvedSelector
}
```

- [ ] **Step 2: Widen the resolver**

In `SelectorResolver.kt`: change both `resolve` and `resolveFromTable` return types from `ResolvedSelector.Scalar` to `ResolvedSelector` (leave `resolveFromExpression` returning `ResolvedSelector.Scalar`), and replace the single collection branch

```kotlin
            prop.isReferenceList(TargetLevel.ENTITY) || prop.isScalarList ->
                throw UnsupportedSelectorException(selector, "collection property '$token' is not supported yet")
```

with these three branches (same position, first in the `when`):

```kotlin
            prop.isScalarList ->
                throw UnsupportedSelectorException(selector, "scalar collection property '$token' is not queryable")
            prop.isReferenceList(TargetLevel.ENTITY) && terminal ->
                ResolvedSelector.CollectionTerminal(prop)
            prop.isReferenceList(TargetLevel.ENTITY) ->
                ResolvedSelector.CollectionStep(token, tokens.drop(1).joinToString("."))
```

If the compiler now rejects `tailrec` on `resolveFromTable`, drop the modifier.

- [ ] **Step 3: Add `table` to `Params.kt`**

```kotlin
package com.github.ichanzhar.rsql.jimmer.operations

import org.babyfish.jimmer.meta.ImmutableProp
import org.babyfish.jimmer.sql.kt.ast.expression.KPropExpression
import org.babyfish.jimmer.sql.kt.ast.table.KProps

public data class Params(
    public val expression: KPropExpression<Any>?,
    public val prop: ImmutableProp,
    public val args: List<Any>,
    public val argument: Any?,
    public val table: KProps<*>,
)
```

- [ ] **Step 4: Write `IsEmptyProcessor.kt`**

Spec verification point: `exists(prop) { null }` is expected to yield the bare `EXISTS(subquery)` predicate. Verify while testing in Task 4; if it returns null instead, replace the block with `{ get<Any>(idPropName).isNotNull() }` where `idPropName` comes from `requireNotNull(params.prop.targetType).idProp.name` - semantically equivalent under EXISTS - and record the switch in your report.

```kotlin
package com.github.ichanzhar.rsql.jimmer.operations

import com.github.ichanzhar.rsql.jimmer.exception.JimmerRsqlSupportException
import org.babyfish.jimmer.sql.kt.ast.expression.KNonNullExpression
import org.babyfish.jimmer.sql.kt.ast.expression.not

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

- [ ] **Step 5: Register it**

In `RsqlOperationsRegistry.kt`: change the seed entry

```kotlin
                RsqlOperation.IS_EMPTY.operator to futureStub("=isEmpty=", "phase 3"),
```

to

```kotlin
                RsqlOperation.IS_EMPTY.operator to { IsEmptyProcessor(it) },
```

and add the `IsEmptyProcessor` import. `futureStub` stays (still used by `initDefaultPostgresOperation`).

- [ ] **Step 6: Rewrite the visitor**

Replace `JimmerRsqlVisitor.kt` in full:

```kotlin
package com.github.ichanzhar.rsql.jimmer

import com.github.ichanzhar.rsql.jimmer.exception.JimmerRsqlSupportException
import com.github.ichanzhar.rsql.jimmer.exception.UnsupportedSelectorException
import com.github.ichanzhar.rsql.jimmer.operations.Params
import com.github.ichanzhar.rsql.jimmer.utils.ArgumentConvertor
import com.github.ichanzhar.rsql.jimmer.utils.JavaTypeUtil
import com.github.ichanzhar.rsql.jimmer.utils.RsqlOperationsRegistry
import cz.jirutka.rsql.parser.ast.AndNode
import cz.jirutka.rsql.parser.ast.ComparisonNode
import cz.jirutka.rsql.parser.ast.OrNode
import cz.jirutka.rsql.parser.ast.RSQLVisitor
import org.babyfish.jimmer.meta.ImmutableProp
import org.babyfish.jimmer.sql.kt.ast.expression.KNonNullExpression
import org.babyfish.jimmer.sql.kt.ast.expression.KPropExpression
import org.babyfish.jimmer.sql.kt.ast.expression.and
import org.babyfish.jimmer.sql.kt.ast.expression.or
import org.babyfish.jimmer.sql.kt.ast.table.KProps

/**
 * Folds an RSQL AST into a nullable Jimmer predicate; stateless and thread-safe.
 * Collection associations resolve to implicit EXISTS subqueries, recursively for
 * nested paths.
 */
public class JimmerRsqlVisitor<E : Any> : RSQLVisitor<KNonNullExpression<Boolean>?, KProps<E>> {
    override fun visit(
        node: AndNode,
        table: KProps<E>,
    ): KNonNullExpression<Boolean>? = and(*node.children.mapNotNull { it.accept(this, table) }.toTypedArray())

    override fun visit(
        node: OrNode,
        table: KProps<E>,
    ): KNonNullExpression<Boolean>? = or(*node.children.mapNotNull { it.accept(this, table) }.toTypedArray())

    override fun visit(
        node: ComparisonNode,
        table: KProps<E>,
    ): KNonNullExpression<Boolean>? = predicate(table, node.selector, node)

    private fun predicate(
        table: KProps<*>,
        selector: String,
        node: ComparisonNode,
    ): KNonNullExpression<Boolean>? =
        when (val resolved = SelectorResolver.resolve(table, selector)) {
            is ResolvedSelector.Scalar ->
                dispatch(resolved.expression, resolved.prop, resolved.castTarget, table, node)
            is ResolvedSelector.CollectionStep ->
                table.exists<Any>(resolved.token) { predicate(this, resolved.remainder, node) }
            is ResolvedSelector.CollectionTerminal ->
                if (node.operator == RsqlOperation.IS_EMPTY.operator) {
                    dispatch(expression = null, prop = resolved.prop, castTarget = String::class.java, table = table, node = node)
                } else {
                    throw UnsupportedSelectorException(
                        selector,
                        "collection property '${resolved.prop.name}' supports only =isEmpty=",
                    )
                }
        }

    private fun dispatch(
        expression: KPropExpression<Any>?,
        prop: ImmutableProp,
        castTarget: Class<out Any>,
        table: KProps<*>,
        node: ComparisonNode,
    ): KNonNullExpression<Boolean>? {
        val target = JavaTypeUtil.getPropertyJavaType(castTarget)
        val args = node.arguments.map { ArgumentConvertor.castArgument(it, node.selector, target) }
        val factory =
            RsqlOperationsRegistry.processorFor(node.operator)
                ?: throw JimmerRsqlSupportException("No processor registered for operator '${node.operator.symbol}'")
        return factory(Params(expression, prop, args, args.firstOrNull(), table)).process()
    }
}
```

- [ ] **Step 7: Extend `Api.kt` KDoc with the negation-semantics note**

Add to the existing `toPredicate` KDoc (keep everything already there):

```kotlin
 * Negation across collections follows EXISTS semantics: `reviews.rating!=5` matches
 * entities having at least one review whose rating differs from 5, not entities
 * having no review with rating 5.
```

- [ ] **Step 8: Verify**

Run: `./gradlew apiDump` then `./gradlew build`
Expected: both BUILD SUCCESSFUL; the dump diff shows ONLY the `Params` constructor/copy/component changes (new `KProps` parameter and `getTable` accessor) - `ResolvedSelector`/`IsEmptyProcessor`/visitor internals stay absent.

- [ ] **Step 9: Commit**

```bash
git add jimmer-rsql-support/src jimmer-rsql-support/api
git commit -m "feat: EXISTS-based collection resolution, Params.table, IsEmptyProcessor"
```

---

### Task 3: Example domain - BookTag and ReviewLabel

**Files (all under `examples/spring-boot4-postgres-example/`):**
- Create: `src/main/kotlin/com/github/ichanzhar/rsql/example/model/BookTag.kt`
- Create: `src/main/kotlin/com/github/ichanzhar/rsql/example/model/ReviewLabel.kt`
- Modify: `src/main/kotlin/com/github/ichanzhar/rsql/example/model/Book.kt` (add `tags`)
- Modify: `src/main/kotlin/com/github/ichanzhar/rsql/example/model/Review.kt` (add `labels`)
- Modify: `src/main/resources/schema.sql`
- Modify: `src/test/kotlin/com/github/ichanzhar/rsql/example/web/BookControllerIntegrationTest.kt` (seed only, no assertion changes)

**Interfaces:**
- Consumes: existing domain/schema/seed conventions
- Produces: selectors `tags.tag`, `reviews.labels.label` resolvable; seeded rows for Task 4's matrix (Hobbit: tags fantasy+classic, review 1 label editorial, review 2 label community; Dune: tags scifi+epic, review 3 labels urgent+editorial)

- [ ] **Step 1: Write `BookTag.kt`**

```kotlin
package com.github.ichanzhar.rsql.example.model

import org.babyfish.jimmer.sql.Entity
import org.babyfish.jimmer.sql.GeneratedValue
import org.babyfish.jimmer.sql.GenerationType
import org.babyfish.jimmer.sql.Id
import org.babyfish.jimmer.sql.ManyToOne

@Entity
interface BookTag {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long
    val tag: String

    @ManyToOne
    val book: Book?
}
```

- [ ] **Step 2: Write `ReviewLabel.kt`**

```kotlin
package com.github.ichanzhar.rsql.example.model

import org.babyfish.jimmer.sql.Entity
import org.babyfish.jimmer.sql.GeneratedValue
import org.babyfish.jimmer.sql.GenerationType
import org.babyfish.jimmer.sql.Id
import org.babyfish.jimmer.sql.ManyToOne

@Entity
interface ReviewLabel {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long
    val label: String

    @ManyToOne
    val review: Review?
}
```

- [ ] **Step 3: Add the back-collections**

In `Book.kt`, after the `reviews` property:

```kotlin
    @OneToMany(mappedBy = "book")
    val tags: List<BookTag>
```

In `Review.kt`, after the `book` property (add the `OneToMany` import):

```kotlin
    @OneToMany(mappedBy = "review")
    val labels: List<ReviewLabel>
```

- [ ] **Step 4: Extend `schema.sql`**

Add `drop table if exists review_label;` and `drop table if exists book_tag;` FIRST (before the existing drops), and append after the `review` table:

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

- [ ] **Step 5: Extend the seed in `BookControllerIntegrationTest`**

Change the truncate statement to:

```kotlin
            "truncate table review_label, book_tag, book_categories, review, chapter, book, category, author restart identity cascade",
```

and append to the multi-insert SQL block (before the closing `""".trimIndent()`):

```sql
            insert into book_tag (id, tag, book_id) values
                (1, 'fantasy', 1), (2, 'classic', 1), (3, 'scifi', 2), (4, 'epic', 2);
            insert into review_label (id, label, review_id) values
                (1, 'editorial', 1), (2, 'community', 2), (3, 'urgent', 3), (4, 'editorial', 3);
```

- [ ] **Step 6: Verify (regression - no new tests yet)**

Run from the example dir: `./gradlew test`
Expected: BUILD SUCCESSFUL, all 17 existing tests pass (KSP regenerates drafts for the new entities; assertions untouched).

- [ ] **Step 7: Commit**

```bash
git add examples/spring-boot4-postgres-example
git commit -m "feat: BookTag and ReviewLabel entities with schema and seed"
```

---

### Task 4: Collection test matrix

**Files:**
- Modify: `examples/spring-boot4-postgres-example/src/test/kotlin/com/github/ichanzhar/rsql/example/web/BookControllerIntegrationTest.kt`

**Interfaces:**
- Consumes: Tasks 1-3 end to end
- Produces: the 12 new tests below - collection acceptance evidence

- [ ] **Step 1: Add the 12 tests**

Append to the test class:

```kotlin
    @Test
    fun `filters by child entity tag through exists`() {
        mockMvc.perform(get("/books").param("query", "tags.tag==classic"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("The Hobbit"))
    }

    @Test
    fun `filters by one-to-many field`() {
        mockMvc.perform(get("/books").param("query", "reviews.rating==5"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `negation across collections uses exists semantics`() {
        mockMvc.perform(get("/books").param("query", "reviews.rating!=5"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("The Hobbit"))
    }

    @Test
    fun `filters by two-level nested collection path`() {
        mockMvc.perform(get("/books").param("query", "reviews.labels.label==urgent"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("Dune"))
    }

    @Test
    fun `filters by list association field`() {
        mockMvc.perform(get("/books").param("query", "chapters.title==Prologue"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("Dune"))
    }

    @Test
    fun `filters by many-to-many association`() {
        mockMvc.perform(get("/books").param("query", "categories.name==Fantasy"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("The Hobbit"))
    }

    @Test
    fun `combines join and nested collection filter with logical and`() {
        mockMvc.perform(get("/books").param("query", "author.name==*Tolkien*;reviews.labels.label==editorial"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("The Hobbit"))
    }

    @Test
    fun `rejects unknown property inside collection path with 400`() {
        mockMvc.perform(get("/books").param("query", "tags.nosuch==1"))
            .andExpect(status().isBadRequest)
            .andExpect(content().string(containsString("nosuch")))
    }

    @Test
    fun `rejects non-isEmpty operator on bare collection with 400`() {
        mockMvc.perform(get("/books").param("query", "reviews==5"))
            .andExpect(status().isBadRequest)
            .andExpect(content().string(containsString("=isEmpty=")))
    }

    @Test
    fun `rejects isEmpty on scalar property with 400`() {
        mockMvc.perform(get("/books").param("query", "title=isEmpty=true"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `finds books with no reviews via isEmpty true`() {
        insertBookWithoutRelations()
        mockMvc.perform(get("/books").param("query", "reviews=isEmpty=true"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("The Silmarillion"))
    }

    @Test
    fun `finds books with reviews via isEmpty false`() {
        insertBookWithoutRelations()
        mockMvc.perform(get("/books").param("query", "reviews=isEmpty=false"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    private fun insertBookWithoutRelations() {
        jdbcTemplate.execute(
            "insert into book (id, title, isbn, publication_year, author_id, width_cm, height_cm, weight_grams) " +
                "values (3, 'The Silmarillion', '9780048231390', 1977, 1, 14.0, 21.0, 400)",
        )
    }
```

- [ ] **Step 2: Run the suite**

Run: `./gradlew test`
Expected: 29/29 pass. Debug order for failures: (1) `tags.tag`/`labels` resolution errors - check Task 3 entity props and Task 2 resolver branches; (2) nested `reviews.labels.label` failing while single-level works - the recursion inside `exists` lambda (escalate if Jimmer rejects nested implicit subqueries: hard blocker per spec, do not work around); (3) `=isEmpty=` returning wrong shape - apply Task 2 Step 4's documented fallback block and record it; (4) 400 tests - exception types in the advice.

- [ ] **Step 3: Commit**

```bash
git add examples/spring-boot4-postgres-example
git commit -m "test: collection matrix with nested exists, negation pin, isEmpty"
```

---

### Task 5: SQL-shape assertions

**Files:**
- Modify: `examples/spring-boot4-postgres-example/src/main/kotlin/com/github/ichanzhar/rsql/example/JimmerConfig.kt`
- Create: `examples/spring-boot4-postgres-example/src/test/kotlin/com/github/ichanzhar/rsql/example/web/SqlCapture.kt`
- Modify: `examples/spring-boot4-postgres-example/src/test/kotlin/com/github/ichanzhar/rsql/example/web/BookControllerIntegrationTest.kt`

**Interfaces:**
- Consumes: `KSqlClient` bean wiring; `org.babyfish.jimmer.sql.runtime.Executor` / `DefaultExecutor` (verify exact members against 0.9.96 - see Step 2 fallback)
- Produces: the phase's headline AC - captured SQL proves `exists`, no `distinct`, `left join`, `not exists`

- [ ] **Step 1: Make the executor injectable in `JimmerConfig.kt`**

```kotlin
package com.github.ichanzhar.rsql.example

import javax.sql.DataSource
import org.babyfish.jimmer.sql.dialect.PostgresDialect
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.newKSqlClient
import org.babyfish.jimmer.sql.runtime.ConnectionManager
import org.babyfish.jimmer.sql.runtime.Executor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class JimmerConfig {
    @Bean
    fun sqlClient(dataSource: DataSource, executorProvider: ObjectProvider<Executor>): KSqlClient = newKSqlClient {
        setConnectionManager(ConnectionManager.simpleConnectionManager(dataSource))
        setDialect(PostgresDialect())
        executorProvider.ifAvailable { setExecutor(it) }
    }
}
```

(Preserve any existing content differences by keeping the current file's structure and only adding the provider parameter + `ifAvailable` line if the file already deviates.)

- [ ] **Step 2: Write `SqlCapture.kt` (test sources)**

```kotlin
package com.github.ichanzhar.rsql.example.web

import java.util.concurrent.CopyOnWriteArrayList
import org.babyfish.jimmer.sql.runtime.DefaultExecutor
import org.babyfish.jimmer.sql.runtime.Executor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

object CapturingExecutor : Executor {
    val statements = CopyOnWriteArrayList<String>()

    override fun <R> execute(args: Executor.Args<R>): R {
        statements.add(args.sql)
        return DefaultExecutor.INSTANCE.execute(args)
    }
}

@TestConfiguration
class SqlCaptureConfig {
    @Bean
    fun capturingExecutor(): Executor = CapturingExecutor
}
```

Verify against 0.9.96: if `Executor` has additional abstract members (e.g. `executeBatch`, `openCursor`), implement each by delegating to `DefaultExecutor.INSTANCE`'s corresponding method; if `DefaultExecutor.INSTANCE` is named differently, adapt (check the class with javap or the sources jar). Only if the interface genuinely cannot be delegated cleanly, use the spec's fallback: a Logback `ListAppender` attached to the Jimmer SQL logger, and record the switch in your report.

- [ ] **Step 3: Wire capture into the test class and add the three tests**

Add to `BookControllerIntegrationTest`'s annotations: `@Import(SqlCaptureConfig::class)` (import `org.springframework.context.annotation.Import`), and add tests (imports: `org.junit.jupiter.api.Assertions.assertFalse`, `org.junit.jupiter.api.Assertions.assertTrue`):

```kotlin
    @Test
    fun `collection query is built as exists without distinct`() {
        CapturingExecutor.statements.clear()
        mockMvc.perform(get("/books").param("query", "reviews.rating==5"))
            .andExpect(status().isOk)
        val sql = CapturingExecutor.statements.joinToString("\n").lowercase()
        assertTrue(sql.contains("exists"), sql)
        assertFalse(sql.contains("distinct"), sql)
    }

    @Test
    fun `reference join query uses left join`() {
        CapturingExecutor.statements.clear()
        mockMvc.perform(get("/books").param("query", "author.name==*Tolkien*"))
            .andExpect(status().isOk)
        val sql = CapturingExecutor.statements.joinToString("\n").lowercase()
        assertTrue(sql.contains("left join"), sql)
    }

    @Test
    fun `isEmpty true is built as not exists`() {
        CapturingExecutor.statements.clear()
        mockMvc.perform(get("/books").param("query", "reviews=isEmpty=true"))
            .andExpect(status().isOk)
        val sql = CapturingExecutor.statements.joinToString("\n").lowercase()
        assertTrue(sql.contains("not exists"), sql)
    }
```

- [ ] **Step 4: Run the suite**

Run: `./gradlew test`
Expected: 32/32 pass. If a shape assertion fails, print the captured SQL (it is the assertion message) and diagnose: `distinct` present means a join leaked where an EXISTS was expected - check the resolver's collection branches, do NOT weaken the assertion.

- [ ] **Step 5: Commit**

```bash
git add examples/spring-boot4-postgres-example
git commit -m "test: sql-shape assertions proving exists strategy"
```

---

### Task 6: Final verification and CLAUDE.md refresh

**Files:**
- Modify: `CLAUDE.md` (repo root)

**Interfaces:**
- Consumes: everything
- Produces: Phase 3 acceptance evidence, accurate CLAUDE.md

- [ ] **Step 1: Verify both builds**

```bash
cd /Users/user/IdeaProjects/jimmer-rsql-support && ./gradlew build
cd examples/spring-boot4-postgres-example && ./gradlew test
```

Expected: both BUILD SUCCESSFUL (32 tests). Capture the count.

- [ ] **Step 2: Update CLAUDE.md's current-state paragraph**

Replace the paragraph starting `**Current state: phases 1-2 done.**` with:

```markdown
**Current state: phases 1-3 done.** Parser layer, SelectorResolver (scalar, reference,
embedded, and collection associations via implicit EXISTS subqueries at any nesting
depth), all COMMON operators including `=isEmpty=`, and the public
`toPredicate`/`createRsqlQuery` API are implemented; the spring-boot4-postgres-example
integration suite (incl. SQL-shape assertions: exists, no distinct) is the acceptance
evidence. JSON operators are still stubs (phase 4); the Ktor example is phase 5. The
authoritative spec is `docs/jimmer-rsql-support-implementation-plan.md`. Note:
jimmer-spring-boot-starter 0.9.96 is incompatible with Spring Boot 4.1.0; the example
wires a manual `KSqlClient` bean over `jimmer-sql-kotlin` instead.
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: mark phases 1-3 complete in CLAUDE.md"
```
