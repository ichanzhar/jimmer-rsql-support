# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Kotlin library translating RSQL query strings (parsed by `cz.jirutka.rsql:rsql-parser`) into Jimmer ORM `KNonNullExpression<Boolean>` predicates, ported from `rsql-hibernate-jpa` (https://github.com/ichanzhar/rsql-hibernate-jpa). Published to Maven Central as `com.github.ichanzhar:jimmer-rsql-support`.

**Current state: phases 1-5 done.** The full operator set is implemented (parser layer,
SelectorResolver with EXISTS collections, all COMMON operators, Postgres `=jsonbeq=`/
`=jsoneq=`), and BOTH examples prove it: spring-boot4-postgres-example (Spring Boot 4,
manual `KSqlClient` bean - the jimmer starter 0.9.96 is incompatible with Boot 4.1.0)
and ktor-postgres-example (Ktor 3, zero Spring on the classpath), each with the same
36-test Testcontainers matrix including SQL-shape assertions. Remaining: docs + Maven
Central release (phase 6). The authoritative spec is
`docs/jimmer-rsql-support-implementation-plan.md`.

## Commands

Root build contains exactly one module (`jimmer-rsql-support`), JDK 21:

```
./gradlew build
```

Examples under `examples/` are fully independent Gradle builds (own wrapper, own `settings.gradle.kts`), NOT included in the root build or CI. They consume the library via `includeBuild("../..")`. Run their tests from inside each example directory:

```
cd examples/spring-boot4-postgres-example && ./gradlew test
cd examples/ktor-postgres-example && ./gradlew test
```

The library module has **no test source set** — all integration tests live in the examples, Testcontainers-based (Docker required). Run a single test class: `./gradlew test --tests 'BookControllerIntegrationTest'`.

## Architecture

Package root: `com.github.ichanzhar.rsql.jimmer`.

Data flow: RSQL string → `RsqlParserFactory` (built from `RsqlOperationsRegistry`) → rsql-parser AST → `JimmerRsqlVisitor` (pure fold, no mutation) → `SelectorResolver` (walks dotted selectors against `ImmutableType`/`ImmutableProp` metadata) → operator `Processor` from `ProcessorsFactory` → composed predicate via null-ignoring `and(...)`/`or(...)`.

Three layers:

- **Parser layer** (ported unchanged from rsql-hibernate-jpa, ORM-agnostic): `RsqlOperation`, `ParserContext` (COMMON/POSTGRESQL), `utils/RsqlOperationsRegistry`, `utils/RsqlParserFactory`, `utils/ArgumentConvertor`, `utils/JavaTypeUtil`, exceptions. Target types come from `ImmutableProp.returnClass` instead of the JPA metamodel.
- **Resolution** (new): `SelectorResolver` resolves `a.b.c` selectors — reference associations via `outerJoin`, embedded props via chained `get`, **collection associations via Jimmer implicit EXISTS subqueries, never joins** (this eliminates the JPA version's `distinct(true)` workaround; the EXISTS wrapper is applied by the visitor after `process()`, processors never see it).
- **Processors** (`operations/`): `fun interface Processor`, one per operator (`==`, `!=`, `>`, `>=`, `<`, `<=`, `=in=`, `=out=`, `=isNull=`, `=eqci=`, `=isEmpty=`, Postgres-only `=jsonbeq=`/`=jsoneq=`). Wildcards `*` translate to SQL `LIKE %`. Custom operators register via `RsqlOperationsRegistry.registerOperation`.

Public API: top-level extension functions in `Api.kt` — `Node.toPredicate(table)` (the primitive) and `KSqlClient.createRsqlQuery(...)` (sugar). All exceptions extend `JimmerRsqlSupportException : RuntimeException`.

## Hard constraints

- No Spring dependencies in the library — framework-agnostic (must work in Spring Boot 4 and Ktor). `jimmer-sql-kotlin` is `compileOnly`; consumers bring their own Jimmer.
- Kotlin `explicitApi()` mode; binary-compatibility-validator with committed `.api` dump.
- Pure functional core: stateless visitor/resolver/processors, `val` only, expression bodies, sealed types over nullable flags, no `!!`, no mocks in tests. The operations registry (ConcurrentHashMap, written at startup only) is the sole mutable state.
- Section 6 of the plan doc lists the full Kotlin/FP conventions — follow them for all new code.
- Plain hyphens in docs and comments, no em dashes.
