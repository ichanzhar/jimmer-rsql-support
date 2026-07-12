# Phase 5: Ktor Example - Design

Date: 2026-07-12
Status: Approved
Parent spec: `docs/jimmer-rsql-support-implementation-plan.md` (sections 5.2, 7-phase-5)
Builds on: `docs/superpowers/specs/2026-07-12-phase4-json-operators-design.md` (merged to main, PR #4)

## Scope

New independent Gradle build `examples/ktor-postgres-example` proving the library runs
with zero Spring on the classpath. This phase changes NOTHING in the library module -
it is a pure consumer. If a test forces a library change, that is an escalation to the
project owner, not a slip-in fix.

Out of scope: Phase 6 (README, operator reference, release), ImmutableModule
serialization (decided: `BookDto` mapping, consistent with the Boot example; the parent
plan's ImmutableModule note is a documented deviation).

## Stack (pinned)

| Dependency | Version |
|---|---|
| Ktor (server-netty, server-content-negotiation, serialization-jackson, server-status-pages, server-test-host) | 3.2.0 |
| Kotlin / KSP plugin | 2.3.20 / 2.3.10 |
| jimmer-sql-kotlin / jimmer-ksp | 0.9.96 |
| HikariCP | 6.3.0 |
| org.postgresql:postgresql | latest at implementation time |
| logback-classic | latest 1.5.x |
| org.testcontainers:testcontainers-junit-jupiter / testcontainers-postgresql | 2.0.5 |
| JUnit | 5 (junit-jupiter) |
| Gradle wrapper | 9.6.1 (copied from repo root) |

`settings.gradle.kts` mirrors the Boot example: `includeBuild("../..")` with dependency
substitution for `com.github.ichanzhar:jimmer-rsql-support`.

## Wiring seam (decided: module-function injection)

```kotlin
fun Application.bookModule(sqlClient: KSqlClient)
```

is the entire app. `main()` builds the production client; tests build their own client
(Testcontainers URL + capturing executor) and mount the same module via
`testApplication { application { bookModule(testClient) } }`. No HOCON config plumbing.

## App shape (package `com.github.ichanzhar.rsql.example`)

- **Domain + schema**: entities `Book` (title, isbn?, publicationYear, metadata?,
  details?, author, dimensions, reviews, chapters, categories, tags), `Author`,
  `Review` (+labels), `Chapter`, `Category`, `BookTag`, `ReviewLabel`,
  `@Embeddable Dimensions` - copied verbatim from
  `examples/spring-boot4-postgres-example/src/main/kotlin/.../model/` (package renamed
  only if needed; keep `com.github.ichanzhar.rsql.example.model`). `schema.sql` copied
  verbatim. Cross-example duplication is by design (independent builds, JPA-repo
  convention).
- **`Main.kt`**: `main()` reads `DATABASE_URL` (default
  `jdbc:postgresql://localhost:5432/rsql_example`), `DATABASE_USER` (`postgres`),
  `DATABASE_PASSWORD` (`postgres`); builds `HikariDataSource`; executes `schema.sql`
  from classpath (split on `;`, skip blanks); builds
  `newKSqlClient { setConnectionManager(ConnectionManager.simpleConnectionManager(dataSource)); setDialect(PostgresDialect()) }`;
  `embeddedServer(Netty, port = 8080) { bookModule(sqlClient) }.start(wait = true)`.
- **`BookModule.kt`**:
  - ContentNegotiation with Jackson (Jackson 2; Kotlin module registered by
    `ktor-serialization-jackson` defaults).
  - StatusPages: `JimmerRsqlSupportException` and `RSQLParserException` ->
    400 with body `mapOf("error" to message)`.
  - `GET /books`: blank/absent `query` -> all books; else
    `sqlClient.createRsqlQuery(Book::class, query, ParserContext.POSTGRESQL)`.
    Execution wrapped in `withContext(Dispatchers.IO)` (parent plan section 6
    coroutines note - the Jimmer client is blocking).
  - Responses map to `BookDto(id, title, isbn, publicationYear)` - identical to the
    Boot example.

## Tests - full 36-test parity (phase AC: "same test matrix green in Ktor")

One class `BookRoutesIntegrationTest` in
`src/test/kotlin/com/github/ichanzhar/rsql/example/`:

- `@Testcontainers` with a companion `@Container @JvmStatic` `PostgreSQLContainer`
  (`postgres:16-alpine`) - framework-free lifecycle, container shared across the class.
- Once per class: `HikariDataSource` against the container, `schema.sql` executed,
  `KSqlClient` built with the delegating `CapturingExecutor` (same pattern as the Boot
  example: records `args.sql`, delegates `execute` and `executeBatch` to
  `DefaultExecutor.INSTANCE`).
- `@BeforeEach`: truncate + seed via plain JDBC (`dataSource.connection.use { ... }`),
  SAME SQL and dataset as the Boot example (authors, categories, books with
  metadata/details JSON, reviews, chapters, book_categories, book_tag, review_label).
- Each test: `testApplication { application { bookModule(client) } }`, issue the GET,
  parse the body with Jackson `readTree`, assert with JUnit
  (`assertEquals(1, body.size())`, `assertEquals("Dune", body[0]["title"].asText())`;
  400 tests assert status + error-body substring).
- All 36 tests port with IDENTICAL query strings and expected values from
  `BookControllerIntegrationTest`: 33 functional (scalar, wildcard, in/out, isNull,
  eqci, reference join, embedded, collections incl. two-level nested EXISTS, negation
  pin, isEmpty pair with the third-book insert, JSON operators, malformed-argument 400,
  unknown-property 400 with `tags.nosuch` context, bare-collection 400, dedup pin) + 3
  SQL-shape (exists/no distinct, left join, not exists).

## Acceptance criteria (parent plan phase 5)

1. `cd examples/ktor-postgres-example && ./gradlew test` -> 36/36 green (Docker).
2. Root `./gradlew build` untouched and green; the branch diff contains ZERO
   library-module changes.
3. CLAUDE.md current-state bumps to phases 1-5 (and gains the Ktor example test
   command) in the final task.

## Risks

- Ktor test-host with blocking JDBC: isolated per request via
  `withContext(Dispatchers.IO)`; standard pattern.
- Testcontainers 2.0.5 without Spring: `@Testcontainers`/`@Container` JUnit 5
  extension handles lifecycle; no `@ServiceConnection` involved.
- Jackson 2 serialization of `BookDto` (a plain data class) has no Jimmer coupling;
  the ImmutableModule question does not arise (decided out of scope).
