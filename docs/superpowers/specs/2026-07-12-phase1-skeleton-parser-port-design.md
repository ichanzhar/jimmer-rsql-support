# Phase 1: Skeleton + Parser Port - Design

Date: 2026-07-12
Status: Approved
Parent spec: `docs/jimmer-rsql-support-implementation-plan.md` (authoritative; this doc pins the Phase 1 decisions)

## Scope

Repo scaffold, CI, and the ORM-agnostic parser layer compiling under
`com.github.ichanzhar.rsql.jimmer`. No visitor, no `SelectorResolver`, no processor
implementations - those are Phases 2-3.

Porting style: FP-first. Same class names, structure, and behavior as the
rsql-hibernate-jpa originals, but written to the plan's section 6 conventions from the
start (immutability, expression bodies, `runCatching` at parse boundaries,
`explicitApi()`-clean signatures). The behavior contract stays identical - silent
fallback to raw string on cast failure, same exception messages - so the Phase 2+
parity tests from the JPA repo still apply.

## Pinned versions

| Dependency | Version | Note |
|---|---|---|
| `cz.jirutka.rsql:rsql-parser` | 2.1.0 | Latest on Maven Central; the parent plan's 2.3.2 does not exist |
| `org.babyfish.jimmer:jimmer-sql-kotlin` | 0.9.96 | `compileOnly` |
| `org.slf4j:slf4j-api` | 2.0.17 | Latest stable; 2.1.0 is alpha only |
| Kotlin | 2.3.20 | Matches JPA repo |
| JDK | 21 | Toolchain |

## Build scaffold

- Root `settings.gradle.kts`: `com.gradleup.nmcp.settings` plugin (same version as the
  JPA repo, 1.6.1), `rootProject.name = "jimmer-rsql-support-root"`, includes only
  `jimmer-rsql-support`, central portal credentials from `CENTRAL_USERNAME` /
  `CENTRAL_PASSWORD` env vars.
- `gradle.properties`: `kotlin.code.style=official`.
- Gradle wrapper copied from the JPA repo.
- `jimmer-rsql-support/build.gradle.kts`:
  - `kotlin("jvm") version "2.3.20"`, JDK 21 toolchain, `explicitApi()`
  - `org.jetbrains.kotlinx.binary-compatibility-validator` with committed `.api` dump
  - ktlint via `org.jlleitschuh.gradle.ktlint`
  - `maven-publish` + `signing`, POM mirroring the JPA repo's (MIT, same developer
    block, scm pointing at `ichanzhar/jimmer-rsql-support`)
  - `group = "com.github.ichanzhar"`, `version = "0.1.0-SNAPSHOT"`
  - Dependencies exactly the three pinned above; no commons-lang, no slf4j-ext
- CI:
  - `.github/workflows/pr-ci.yaml`: on PR, JDK 21, `./gradlew build` (compile +
    ktlint check + apiCheck)
  - `.github/workflows/release.yaml`: manual trigger, publishes via nmcp, tags
    `v<version>`
- Examples directory is out of scope for Phase 1.

## Ported files

All under `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/`.

| File | Delta vs JPA original |
|---|---|
| `ParserContext.kt` | Unchanged enum: `COMMON`, `POSTGRESQL` |
| `RsqlOperation.kt` | KDoc with an example RSQL string per operator; `getSimpleOperator` becomes `fun from(operator: ComparisonOperator): RsqlOperation?` via `entries.firstOrNull`, identity comparison kept |
| `utils/RsqlOperationsRegistry.kt` | Backed by `ConcurrentHashMap`; public surface exposes read-only `Map<ComparisonOperator, ProcessorParamsBuilder>` and `Set<ComparisonOperator>` views; `registerOperation(operator, processor)` and `initDefaultPostgresOperation()` kept; COMMON entries seeded with factories that `TODO("Phase 2")` until processors land |
| `utils/RsqlParserFactory.kt` | Same shape: `instance(context: ParserContext? = null): RSQLParser`; POSTGRESQL context registers JSON operators; a snapshot copy of the operator set is passed to `RSQLParser` |
| `utils/JavaTypeUtil.kt` | Immutable `Map` literal instead of mutable map with init block |
| `utils/ArgumentConvertor.kt` | Same coercion matrix (Int, Long, BigInteger, Double, Float, BigDecimal, Char, Short, Boolean, UUID, Timestamp/Date, LocalDate, LocalDateTime, LocalTime, OffsetDateTime, ZonedDateTime, enums, fallback to raw string); `runCatching` at third-party parse boundaries mapped immediately to a value or fallback, expression-bodied `when`; `InvalidDateFormatException` and `InvalidEnumValueException` still thrown, all other failures silently fall back to the raw string |
| `operations/Processor.kt` | New per plan 4.4: `public fun interface Processor { public fun process(): KNonNullExpression<Boolean>? }` |
| `operations/Params.kt` | New per plan 4.4: `public data class Params(val expression: KPropExpression<Any>?, val prop: ImmutableProp, val args: List<Any>, val argument: Any?)`, compiles against compileOnly Jimmer |
| `exception/JimmerRsqlSupportException.kt` | New: `public open class JimmerRsqlSupportException : RuntimeException` base |
| `exception/InvalidDateFormatException.kt` | Extends the base; message text unchanged from JPA version |
| `exception/InvalidEnumValueException.kt` | Extends the base; message text unchanged from JPA version |

Registry/processor chicken-and-egg resolution (decided): `Processor` and `Params` types
are defined in Phase 1 so the registry's public API is final from day one; the seeded
COMMON factories throw `NotImplementedError` via `TODO("Phase 2")` and are replaced by
real processor registrations in Phase 2. Parsing is unaffected because `RSQLParser`
only consumes the operator set.

## Acceptance criteria

1. `./gradlew build` green: compile, ktlint, apiCheck against the committed `.api` dump.
2. Smoke check: a temporary `main` parses `name==x;year>2000` into an rsql-parser AST
   and prints it; output verified, then the file is deleted before commit.

## Out of scope

Visitor, `SelectorResolver`, processor implementations, `UnknownPropertyException`,
`Api.kt` entry points, examples, tests, README content beyond a stub.
