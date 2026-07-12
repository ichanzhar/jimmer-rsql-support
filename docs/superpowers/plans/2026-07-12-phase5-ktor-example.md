# Phase 5: Ktor Example Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Ktor 3 example app proving zero Spring coupling, with the full 36-test matrix from the Boot example green against Testcontainers Postgres.

**Architecture:** `fun Application.bookModule(sqlClient: KSqlClient)` is the whole app; `main()` wires HikariCP + schema init + `newKSqlClient` for production; tests build their own client (container URL + capturing executor) and mount the same module via `testApplication`. Domain/schema/seed copied verbatim from the Boot example. Spec: `docs/superpowers/specs/2026-07-12-phase5-ktor-example-design.md`.

**Tech Stack:** Ktor 3.2.0, Kotlin 2.3.20 + KSP 2.3.10, jimmer-sql-kotlin/jimmer-ksp 0.9.96, HikariCP 6.3.0, postgresql driver, logback-classic, Testcontainers 2.0.5, JUnit 5.

## Global Constraints

- ZERO library-module changes. If a test seems to require one, report BLOCKED - it is an escalation, never a slip-in fix.
- All work under `examples/ktor-postgres-example/` (independent build; run its Gradle from that directory). Docker required for tasks 2-4.
- The Boot example at `examples/spring-boot4-postgres-example/` is the copy source and parity reference: entities and `schema.sql` copy VERBATIM (same package `com.github.ichanzhar.rsql.example.model`); every `@Test` in its `BookControllerIntegrationTest` ports with the IDENTICAL query string and expected values.
- No comments in code. Plain hyphens, no em dashes. No wildcard imports.
- Commit messages end with:

```
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
```

---

### Task 1: Scaffold, domain, and app module (compiles, no Docker)

**Files (all under `examples/ktor-postgres-example/`):**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`
- Copy: `gradlew`, `gradlew.bat`, `gradle/` from the repo root
- Copy: all 8 files from `examples/spring-boot4-postgres-example/src/main/kotlin/com/github/ichanzhar/rsql/example/model/` to `src/main/kotlin/com/github/ichanzhar/rsql/example/model/` (verbatim)
- Copy: `examples/spring-boot4-postgres-example/src/main/resources/schema.sql` to `src/main/resources/schema.sql` (verbatim)
- Create: `src/main/kotlin/com/github/ichanzhar/rsql/example/Main.kt`
- Create: `src/main/kotlin/com/github/ichanzhar/rsql/example/BookModule.kt`
- Create: `src/main/resources/logback.xml`

**Interfaces:**
- Consumes: the library via `includeBuild` substitution
- Produces: `fun Application.bookModule(sqlClient: KSqlClient)`, `fun buildSqlClient(dataSource: DataSource, executor: Executor? = null): KSqlClient`, `fun executeSchema(dataSource: DataSource)`, `data class BookDto(id: Long, title: String, isbn: String?, publicationYear: Int)` - Task 2's harness uses all four

- [ ] **Step 1: Write `settings.gradle.kts`**

```kotlin
rootProject.name = "ktor-postgres-example"

includeBuild("../..") {
    dependencySubstitution {
        substitute(module("com.github.ichanzhar:jimmer-rsql-support")).using(project(":jimmer-rsql-support"))
    }
}
```

- [ ] **Step 2: Write `build.gradle.kts` and `gradle.properties`**

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.20"
    id("com.google.devtools.ksp") version "2.3.10"
    application
}

group = "com.github.ichanzhar.rsql.example"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

val ktorVersion = "3.2.0"
val jimmerVersion = "0.9.96"
val testcontainersVersion = "2.0.5"

dependencies {
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-jackson:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("org.babyfish.jimmer:jimmer-sql-kotlin:$jimmerVersion")
    ksp("org.babyfish.jimmer:jimmer-ksp:$jimmerVersion")
    implementation("com.github.ichanzhar:jimmer-rsql-support:0.1.0-SNAPSHOT")
    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:testcontainers-postgresql:$testcontainersVersion")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.set(listOf("-Xjsr305=strict"))
    }
}

application {
    mainClass.set("com.github.ichanzhar.rsql.example.MainKt")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
```

`gradle.properties`:

```properties
kotlin.code.style=official
```

If `org.postgresql:postgresql:42.7.7` or `junit-jupiter:5.11.4` are not the latest at implementation time, bumping to the latest stable is fine; the pinned Ktor/Jimmer/KSP/Hikari/Testcontainers versions are NOT negotiable.

- [ ] **Step 3: Copy wrapper, domain, schema**

```bash
cd /Users/user/IdeaProjects/jimmer-rsql-support
cp gradlew gradlew.bat examples/ktor-postgres-example/
cp -R gradle examples/ktor-postgres-example/
chmod +x examples/ktor-postgres-example/gradlew
mkdir -p examples/ktor-postgres-example/src/main/kotlin/com/github/ichanzhar/rsql/example/model
cp examples/spring-boot4-postgres-example/src/main/kotlin/com/github/ichanzhar/rsql/example/model/*.kt \
   examples/ktor-postgres-example/src/main/kotlin/com/github/ichanzhar/rsql/example/model/
mkdir -p examples/ktor-postgres-example/src/main/resources
cp examples/spring-boot4-postgres-example/src/main/resources/schema.sql \
   examples/ktor-postgres-example/src/main/resources/
```

- [ ] **Step 4: Write `Main.kt`**

```kotlin
package com.github.ichanzhar.rsql.example

import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import javax.sql.DataSource
import org.babyfish.jimmer.sql.dialect.PostgresDialect
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.newKSqlClient
import org.babyfish.jimmer.sql.runtime.ConnectionManager
import org.babyfish.jimmer.sql.runtime.Executor

fun main() {
    val dataSource =
        HikariDataSource().apply {
            jdbcUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/rsql_example"
            username = System.getenv("DATABASE_USER") ?: "postgres"
            password = System.getenv("DATABASE_PASSWORD") ?: "postgres"
        }
    executeSchema(dataSource)
    val sqlClient = buildSqlClient(dataSource)
    embeddedServer(Netty, port = 8080) { bookModule(sqlClient) }.start(wait = true)
}

fun buildSqlClient(
    dataSource: DataSource,
    executor: Executor? = null,
): KSqlClient =
    newKSqlClient {
        setConnectionManager(ConnectionManager.simpleConnectionManager(dataSource))
        setDialect(PostgresDialect())
        executor?.let { setExecutor(it) }
    }

fun executeSchema(dataSource: DataSource) {
    val schema =
        object {}.javaClass.getResource("/schema.sql")?.readText()
            ?: error("schema.sql not found on classpath")
    dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            schema.split(';').map(String::trim).filter(String::isNotEmpty).forEach(statement::execute)
        }
    }
}
```

- [ ] **Step 5: Write `BookModule.kt`**

```kotlin
package com.github.ichanzhar.rsql.example

import com.github.ichanzhar.rsql.example.model.Book
import com.github.ichanzhar.rsql.jimmer.ParserContext
import com.github.ichanzhar.rsql.jimmer.createRsqlQuery
import com.github.ichanzhar.rsql.jimmer.exception.JimmerRsqlSupportException
import cz.jirutka.rsql.parser.RSQLParserException
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.babyfish.jimmer.sql.kt.KSqlClient

data class BookDto(
    val id: Long,
    val title: String,
    val isbn: String?,
    val publicationYear: Int,
)

fun Application.bookModule(sqlClient: KSqlClient) {
    install(ContentNegotiation) { jackson() }
    install(StatusPages) {
        exception<JimmerRsqlSupportException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to cause.message))
        }
        exception<RSQLParserException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "invalid RSQL query")))
        }
    }
    routing {
        get("/books") {
            val query = call.request.queryParameters["query"]
            val books =
                withContext(Dispatchers.IO) {
                    if (query.isNullOrBlank()) {
                        sqlClient.createQuery(Book::class) { select(table) }.execute()
                    } else {
                        sqlClient.createRsqlQuery(Book::class, query, ParserContext.POSTGRESQL).execute()
                    }
                }
            call.respond(books.map { BookDto(it.id, it.title, it.isbn, it.publicationYear) })
        }
    }
}
```

- [ ] **Step 6: Write `logback.xml`**

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <root level="INFO">
        <appender-ref ref="STDOUT"/>
    </root>
</configuration>
```

- [ ] **Step 7: Verify compilation (no Docker)**

Run from `examples/ktor-postgres-example/`: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL (KSP generates drafts, library substituted). If a Ktor 3.2.0 API name differs from the code above (e.g. plugin package paths), fix against the actual artifact - the module/DTO/wiring SHAPE is normative, exact import spelling is not.

- [ ] **Step 8: Verify the wrapper jar is staged and commit**

```bash
git status --short examples/ktor-postgres-example | grep wrapper
git add examples/ktor-postgres-example
git commit -m "feat: ktor example scaffold with jimmer domain and book module"
```

(The repo `.gitignore` un-ignores `**/gradle/wrapper/gradle-wrapper.jar` - the jar must appear as added.)

---

### Task 2: Test harness and first test

**Files:**
- Create: `examples/ktor-postgres-example/src/test/kotlin/com/github/ichanzhar/rsql/example/SqlCapture.kt`
- Create: `examples/ktor-postgres-example/src/test/kotlin/com/github/ichanzhar/rsql/example/BookRoutesIntegrationTest.kt`

**Interfaces:**
- Consumes: `bookModule`, `buildSqlClient`, `executeSchema` (Task 1); the Boot example's `SqlCapture.kt` at `examples/spring-boot4-postgres-example/src/test/kotlin/com/github/ichanzhar/rsql/example/web/SqlCapture.kt` as the reference for `CapturingExecutor`'s exact `executeBatch` override signature
- Produces: the harness Task 3 fills with the full matrix - helpers `assertSingle(query, title)`, `assertCount(query, expected)`, `assertBadRequest(query, bodyContains)`, `searchRaw(query): Pair<HttpStatusCode, String>`, `executeSql(sql)`, `insertBookWithoutRelations()`, seeded `@BeforeEach`

- [ ] **Step 1: Write `SqlCapture.kt`**

Copy the `CapturingExecutor` object from the Boot example's `SqlCapture.kt` (same body: `statements: CopyOnWriteArrayList<String>`, `execute` records `args.sql` then delegates to `DefaultExecutor.INSTANCE`, `executeBatch` delegates with the exact signature that file uses), package `com.github.ichanzhar.rsql.example`, WITHOUT the Spring `@TestConfiguration` class (not applicable here).

- [ ] **Step 2: Write the harness + first failing-then-passing test**

```kotlin
package com.github.ichanzhar.rsql.example

import com.fasterxml.jackson.databind.ObjectMapper
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.assertContains
import kotlin.test.assertEquals
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@Testcontainers
class BookRoutesIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine")

        private val dataSource: HikariDataSource by lazy {
            HikariDataSource()
                .apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                }.also { executeSchema(it) }
        }

        private val sqlClient: KSqlClient by lazy { buildSqlClient(dataSource, CapturingExecutor) }

        private val mapper = ObjectMapper()
    }

    @BeforeEach
    fun seed() {
        executeSql(
            "truncate table review_label, book_tag, book_categories, review, chapter, book, category, author restart identity cascade",
        )
        executeSql(SEED_SQL)
    }

    private fun executeSql(sql: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }

    private fun searchRaw(query: String?): Pair<HttpStatusCode, String> {
        var result: Pair<HttpStatusCode, String>? = null
        testApplication {
            application { bookModule(sqlClient) }
            val response = client.get("/books") { if (query != null) parameter("query", query) }
            result = response.status to response.bodyAsText()
        }
        return requireNotNull(result)
    }

    private fun assertCount(query: String?, expected: Int) {
        val (status, body) = searchRaw(query)
        assertEquals(HttpStatusCode.OK, status, body)
        assertEquals(expected, mapper.readTree(body).size(), body)
    }

    private fun assertSingle(query: String, title: String) {
        val (status, body) = searchRaw(query)
        assertEquals(HttpStatusCode.OK, status, body)
        val json = mapper.readTree(body)
        assertEquals(1, json.size(), body)
        assertEquals(title, json[0]["title"].asText(), body)
    }

    private fun assertBadRequest(query: String, bodyContains: String? = null) {
        val (status, body) = searchRaw(query)
        assertEquals(HttpStatusCode.BadRequest, status, body)
        if (bodyContains != null) assertContains(body, bodyContains, message = body)
    }

    private fun insertBookWithoutRelations() {
        executeSql(
            "insert into book (id, title, isbn, publication_year, author_id, width_cm, height_cm, weight_grams, metadata, details) " +
                "values (3, 'The Silmarillion', '9780048231390', 1977, 1, 14.0, 21.0, 400, null, null)",
        )
    }

    @Test
    fun `returns all books when query is absent`() = assertCount(null, 2)
}
```

`SEED_SQL` is a `private val` on the companion holding EXACTLY the multi-insert SQL block from the Boot example's `@BeforeEach` (authors, categories, books incl. metadata/details JSON casts, reviews, chapters, book_categories, book_tag, review_label) - copy it verbatim from `BookControllerIntegrationTest.kt`.

- [ ] **Step 3: Run the first test**

Run from the example dir: `./gradlew test --tests 'BookRoutesIntegrationTest'`
Expected: 1 test PASS (container boots, schema applied, seed inserted, module serves). Iterate on harness plumbing errors here - this task exists so Task 3 is pure test transcription.

- [ ] **Step 4: Commit**

```bash
git add examples/ktor-postgres-example
git commit -m "test: ktor integration harness with testcontainers and sql capture"
```

---

### Task 3: Full matrix port (36 tests)

**Files:**
- Modify: `examples/ktor-postgres-example/src/test/kotlin/com/github/ichanzhar/rsql/example/BookRoutesIntegrationTest.kt`

**Interfaces:**
- Consumes: the Task 2 helpers; the parity reference `examples/spring-boot4-postgres-example/src/test/kotlin/com/github/ichanzhar/rsql/example/web/BookControllerIntegrationTest.kt` (open it and port every `@Test` - it is the single source of truth for query strings and expectations)
- Produces: 36/36 - the phase acceptance evidence

- [ ] **Step 1: Port the remaining 35 tests**

Conversion recipe (mechanical): each Boot test maps to one method with the same backticked name, using the matching helper:

- `status().isOk` + `jsonPath("$.length()").value(n)` + `jsonPath("$[0].title").value(t)` -> `assertSingle(query, t)` (n is always 1 in these)
- `status().isOk` + length-only -> `assertCount(query, n)`
- `status().isBadRequest` + `containsString(s)` -> `assertBadRequest(query, s)`
- `status().isBadRequest` only -> `assertBadRequest(query)`
- The two isEmpty tests call `insertBookWithoutRelations()` first (same as Boot)
- The three SQL-shape tests: `CapturingExecutor.statements.clear()`, then `searchRaw(query)` asserting OK, then the same lowercased-contains assertions on `CapturingExecutor.statements.joinToString("\n")` (`exists` + not `distinct`; `left join`; `not exists`) via `assertTrue`/`assertFalse` from `kotlin.test`

Expected inventory (verify against the Boot file; it governs): 15 more functional tests from Phase 2 (wildcard, exact, inequality, gt-fiql, range-and, in, out, isNull true/false, reference join, eqci join, embedded, or-fold, unknown-property 400, malformed-RSQL 400), 12 from Phase 3 (tag exists, one-to-many, negation pin, two-level nested, list association, many-to-many, join+nested and, tags.nosuch 400, bare-collection 400, isEmpty-on-scalar 400, isEmpty true/false), 5 from Phase 4 (jsonbeq, nested jsonbeq, jsoneq, malformed json 400, dedup >=4), 3 SQL-shape. Total with Task 2's test: 36.

- [ ] **Step 2: Run the suite**

Run: `./gradlew test`
Expected: 36/36 PASS. Any failure here that traces to the LIBRARY (not the harness) is a BLOCKED escalation per the global constraints - the same queries pass in the Boot example, so a Ktor-only library failure means an environment-coupling bug the owner must see.

- [ ] **Step 3: Commit**

```bash
git add examples/ktor-postgres-example
git commit -m "test: full 36-test parity matrix in ktor"
```

---

### Task 4: Final verification and CLAUDE.md refresh

**Files:**
- Modify: `CLAUDE.md` (repo root)

- [ ] **Step 1: Verify all three builds**

```bash
cd /Users/user/IdeaProjects/jimmer-rsql-support && ./gradlew build
cd examples/spring-boot4-postgres-example && ./gradlew test
cd ../ktor-postgres-example && ./gradlew test
```

Expected: all BUILD SUCCESSFUL (36 + 36 tests). Confirm `git diff main...HEAD --stat -- jimmer-rsql-support/` is EMPTY (zero library changes on the branch).

- [ ] **Step 2: Update CLAUDE.md's current-state paragraph**

Replace the paragraph starting `**Current state: phases 1-4 done.**` with:

```markdown
**Current state: phases 1-5 done.** The full operator set is implemented (parser layer,
SelectorResolver with EXISTS collections, all COMMON operators, Postgres `=jsonbeq=`/
`=jsoneq=`), and BOTH examples prove it: spring-boot4-postgres-example (Spring Boot 4,
manual `KSqlClient` bean - the jimmer starter 0.9.96 is incompatible with Boot 4.1.0)
and ktor-postgres-example (Ktor 3, zero Spring on the classpath), each with the same
36-test Testcontainers matrix including SQL-shape assertions. Remaining: docs + Maven
Central release (phase 6). The authoritative spec is
`docs/jimmer-rsql-support-implementation-plan.md`.
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: mark phases 1-5 complete in CLAUDE.md"
```
