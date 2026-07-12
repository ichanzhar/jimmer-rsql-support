# Phase 6: Docs, Guards, Release Readiness - Design

Date: 2026-07-13
Status: Approved
Parent spec: `docs/jimmer-rsql-support-implementation-plan.md` (section 7-phase-6)
Branch: continues on `feat/phase5` (decided - phases 5+6 merge as one PR).

## Scope

1. Two behavior guards (decided - both in scope for 0.1.0):
   - `IsEmptyProcessor`: strict boolean parsing via `toBooleanStrictOrNull()`;
     non-`true`/`false` arguments throw
     `JimmerRsqlSupportException("'=isEmpty=' expects true or false, got '<arg>'")`.
   - `EqualProcessor` / `NotEqualProcessor`: the wildcard branch requires
     `params.prop.returnClass == String::class.java`; otherwise throw
     `JimmerRsqlSupportException("wildcard '*' requires a string property, '<name>' is '<type>'")`.
     No `Params` change needed; reference-terminal FK ids also correctly reject
     wildcards. Both guards are internal behavior - `.api` dump unchanged.
   - Two new 400-asserting tests in EACH example suite: `publicationYear==19*` and
     `reviews=isEmpty=maybe`. Both suites go 36 -> 38.
2. Version flip: library `version = "0.1.0"`; both examples' dependency coordinates
   align to `0.1.0`.
3. README replacing the 2-line stub (content outline below).
4. Doc debt: parent plan section 4.4 JSON-operator rows updated to the shipped
   bound-parameter fragments; CLAUDE.md current-state -> all six phases done.
5. Example polish: Ktor `JimmerRsqlSupportException` handler gains the same
   `?: "invalid RSQL query"` null-default as the parser handler.
6. Release readiness verification: `./gradlew publishToMavenLocal` green; POM
   inspected (name, description, MIT license, scm urls, developer block, packaging,
   no SNAPSHOT anywhere); `-sources` and `-javadoc` jars present in the local repo;
   `release.yaml`'s sed pattern extracts `0.1.0` and its SNAPSHOT guard passes.

Out of scope: actually running the release workflow (owner-triggered, post-merge,
needs repo secrets), Dokka, new operators, any public API change.

## README outline (target ~250-350 lines)

1. Title + one-paragraph pitch (RSQL strings -> Jimmer predicates; ported from
   rsql-hibernate-jpa; collections via implicit EXISTS, no DISTINCT).
2. Installation: Gradle Kotlin DSL + Maven coordinates
   `com.github.ichanzhar:jimmer-rsql-support:0.1.0`; the `compileOnly` contract -
   consumers bring their own `jimmer-sql-kotlin` (tested against 0.9.96).
3. Quick start: `Node.toPredicate(table)` inside any `createQuery` block (the
   primitive) and `KSqlClient.createRsqlQuery(...)` (the sugar); parser construction
   via `RsqlParserFactory.instance(ParserContext.POSTGRESQL)`.
4. Operator reference table - all 13 operators, one example RSQL string each
   (KDoc examples reused), plus for the JSON operators the `path|value` grammar:
   first `|` splits path from value (values may contain `|`, keys cannot), path
   dot-splits into keys, JSON keys containing dots are unaddressable, bound
   parameters (no SQL injection through paths).
5. Selectors: dotted paths; reference associations -> LEFT JOIN (terminal reference
   compares the FK id, no join); embedded props chain at any depth; collection
   associations -> implicit EXISTS subqueries at any nesting depth - callout box for
   negation semantics (`reviews.rating!=5` = "has a review != 5", NOT "has no
   5-review"); `=isEmpty=true/false` on bare collection props; scalar lists not
   queryable.
6. PostgreSQL context + custom operators: `ParserContext.POSTGRESQL` registers the
   JSON operators (one-way, process-wide - once registered, subsequent COMMON parsers
   accept them too); `RsqlOperationsRegistry.registerOperation` for custom operators;
   defaults never clobber registered overrides.
7. Error handling: `JimmerRsqlSupportException` hierarchy
   (`UnknownPropertyException`, `UnsupportedSelectorException`, coercion exceptions);
   map it plus `RSQLParserException` to HTTP 400 - Spring `@RestControllerAdvice` and
   Ktor StatusPages snippets referencing the examples.
8. Version matrix: 0.1.0 | Jimmer 0.9.96 (tested) | Kotlin 2.x | JDK 21.
9. Examples: both apps, run commands, Docker requirement, `includeBuild` substitution
   explained (switching to the published coordinate after release), the
   jimmer-spring-boot-starter 0.9.96 / Spring Boot 4.1.0 incompatibility and manual
   `KSqlClient` bean, `simpleConnectionManager` bypasses Spring transactions (demo
   wiring, not transactional-production wiring), the Ktor example's schema init
   splits on `;` (plain DDL only).
10. Behavior notes: strict `=isEmpty=` booleans; wildcards require string
    properties; argument coercion falls back to the raw string except dates/enums
    which throw.

Plain hyphens throughout, no em dashes, no emoji.

## Acceptance criteria

1. Root `./gradlew build` green; `.api` dump unchanged.
2. Both example suites green at 38 tests each.
3. `./gradlew publishToMavenLocal` green; `~/.m2/repository/com/github/ichanzhar/jimmer-rsql-support/0.1.0/`
   contains `.jar`, `-sources.jar`, `-javadoc.jar`, `.pom`; POM fields verified; no
   `SNAPSHOT` string anywhere in the published POM or repo paths.
4. `sed -n 's/^version = "\(.*\)"/\1/p' jimmer-rsql-support/build.gradle.kts` prints
   `0.1.0` (the release workflow's extraction).
5. README renders without broken tables/code fences (markdown lint by inspection).

## Risks

- `publishToMavenLocal` runs the signing task if configured with `sign(...)`:
  without `SIGNING_KEY` env the signing plugin may fail the local publish. If it
  does, the verification command becomes
  `./gradlew publishToMavenLocal -x signMavenJavaPublication` (or the actual sign
  task name) - signing is exercised only in the release workflow where the key
  exists. Record which command shipped.
- README length vs accuracy: every claim in it must match shipped behavior; the
  final review checks the operator table against `RsqlOperation` KDoc and the
  behavior notes against the guard implementations.
