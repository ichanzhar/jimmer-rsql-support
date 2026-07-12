# Phase 1: Skeleton + Parser Port Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Scaffold the jimmer-rsql-support repo (Gradle build, CI) and port the ORM-agnostic parser layer so the library module compiles and parses RSQL strings into an AST.

**Architecture:** Single-module Gradle build publishing `com.github.ichanzhar:jimmer-rsql-support`. The parser layer is ported FP-first from rsql-hibernate-jpa (at `/Users/user/IdeaProjects/rsql-hibernate-jpa`) into package `com.github.ichanzhar.rsql.jimmer`. The operations registry seeds COMMON operators with factories that throw `NotImplementedError`; Phase 2 replaces them with real processors. Spec: `docs/superpowers/specs/2026-07-12-phase1-skeleton-parser-port-design.md`.

**Tech Stack:** Kotlin 2.3.20, JDK 21, Gradle 9.6.1 wrapper (copied from the JPA repo), rsql-parser 2.1.0 (`api`), jimmer-sql-kotlin 0.9.96 (`compileOnly`), slf4j-api 2.0.17, binary-compatibility-validator 0.18.0, ktlint plugin 14.2.0, nmcp settings plugin 1.6.1.

## Global Constraints

- No test source set in the library module (parent spec rule: all tests live in Phase 2+ examples). TDD is therefore not applicable in this phase; each task's cycle is: write code, `./gradlew apiDump`, `./gradlew build` green (compile + ktlintCheck + apiCheck), commit including the updated `jimmer-rsql-support/api/jimmer-rsql-support.api` dump.
- All Gradle commands run from the repo root `/Users/user/IdeaProjects/jimmer-rsql-support`.
- `explicitApi()` strict mode: every public declaration carries an explicit `public` modifier and explicit return type.
- FP conventions: `val` only, expression bodies, no `!!`, no mutable shared state except the registry, `runCatching` only at third-party parse boundaries.
- Behavior parity with the JPA originals, with one approved spec deviation: `InvalidDateFormatException` and `InvalidEnumValueException` propagate out of `ArgumentConvertor` (the JPA version's outer catch swallowed them).
- No inline comments in code. KDoc on public API only.
- Plain hyphens in docs and comments, no em dashes.
- No Spring, commons-lang, or slf4j-ext dependencies. `import java.util.*` style wildcard imports are forbidden (ktlint official style); always write explicit imports.
- If ktlintCheck fails on formatting, run `./gradlew ktlintFormat` and re-run the build; never hand-format against the tool.

---

### Task 1: Gradle scaffold

**Files:**
- Create: `settings.gradle.kts`
- Create: `gradle.properties`
- Create: `.editorconfig`
- Create: `jimmer-rsql-support/build.gradle.kts`
- Modify: `.gitignore` (wrapper jar exception)
- Copy: `gradlew`, `gradlew.bat`, `gradle/` from `/Users/user/IdeaProjects/rsql-hibernate-jpa`

**Interfaces:**
- Consumes: nothing (first task)
- Produces: a building Gradle module `jimmer-rsql-support` with `explicitApi()`, bcv (`apiDump`/`apiCheck`), ktlint, publishing config. Later tasks add Kotlin sources under `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/` and re-run `apiDump`.

- [ ] **Step 1: Write `settings.gradle.kts`**

```kotlin
plugins {
    id("com.gradleup.nmcp.settings") version "1.6.1"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "jimmer-rsql-support-root"

include("jimmer-rsql-support")

nmcpSettings {
    centralPortal {
        username = System.getenv("CENTRAL_USERNAME")
        password = System.getenv("CENTRAL_PASSWORD")
        publishingType = "AUTOMATIC"
    }
}
```

- [ ] **Step 2: Write `gradle.properties`**

```properties
kotlin.code.style=official
```

- [ ] **Step 3: Write `.editorconfig`**

```
root = true

[*.{kt,kts}]
ktlint_code_style = ktlint_official
max_line_length = 120
```

- [ ] **Step 4: Write `jimmer-rsql-support/build.gradle.kts`**

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.3.20"
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.0"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    `maven-publish`
    signing
}

group = "com.github.ichanzhar"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    explicitApi()
}

dependencies {
    api("cz.jirutka.rsql:rsql-parser:2.1.0")
    compileOnly("org.babyfish.jimmer:jimmer-sql-kotlin:0.9.96")
    implementation("org.slf4j:slf4j-api:2.0.17")
}

java {
    withJavadocJar()
    sourceCompatibility = JavaVersion.VERSION_21
}

tasks.withType<KotlinCompile>() {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.set(listOf("-Xjsr305=strict"))
    }
}

val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifact(sourcesJar.get())
            pom {
                name.set("Jimmer RSQL Support")
                description.set("RSQL implementation for Jimmer ORM with association path and collection support")
                url.set("https://github.com/ichanzhar/jimmer-rsql-support")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("http://www.opensource.org/licenses/mit-license.php")
                    }
                }
                developers {
                    developer {
                        id.set("ichanzhar")
                        name.set("Ihor Chanzhar")
                        email.set("ihor.chanzhar@gmail.com")
                        organization.set("com.github.ichanzhar")
                        organizationUrl.set("https://github.com/ichanzhar")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/ichanzhar/jimmer-rsql-support.git")
                    developerConnection.set("scm:git:git@github.com:ichanzhar/jimmer-rsql-support.git")
                    url.set("https://github.com/ichanzhar/jimmer-rsql-support")
                }
            }
        }
    }
}

signing {
    val signingKey = providers.environmentVariable("SIGNING_KEY").orNull
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, providers.environmentVariable("SIGNING_PASSWORD").orNull)
    }
    sign(publishing.publications.getByName("mavenJava"))
}
```

- [ ] **Step 5: Copy the Gradle wrapper from the JPA repo**

```bash
cp /Users/user/IdeaProjects/rsql-hibernate-jpa/gradlew /Users/user/IdeaProjects/rsql-hibernate-jpa/gradlew.bat /Users/user/IdeaProjects/jimmer-rsql-support/
cp -R /Users/user/IdeaProjects/rsql-hibernate-jpa/gradle /Users/user/IdeaProjects/jimmer-rsql-support/
chmod +x /Users/user/IdeaProjects/jimmer-rsql-support/gradlew
```

- [ ] **Step 6: Un-ignore the wrapper jar**

The existing `.gitignore` contains a global `*.jar` rule that would exclude `gradle/wrapper/gradle-wrapper.jar`. Append this line to `.gitignore`:

```
!gradle/wrapper/gradle-wrapper.jar
```

- [ ] **Step 7: Create the empty source root and run apiDump**

```bash
mkdir -p jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer
./gradlew apiDump
```

Expected: `BUILD SUCCESSFUL`; file `jimmer-rsql-support/api/jimmer-rsql-support.api` created (empty at this point).

- [ ] **Step 8: Run the build**

```bash
./gradlew build
```

Expected: `BUILD SUCCESSFUL` (compileKotlin NO-SOURCE, ktlintCheck, apiCheck all pass).

- [ ] **Step 9: Commit**

```bash
git add settings.gradle.kts gradle.properties .editorconfig .gitignore gradlew gradlew.bat gradle/ jimmer-rsql-support/build.gradle.kts jimmer-rsql-support/api/
git commit -m "build: gradle scaffold for library module"
```

---

### Task 2: CI workflows

**Files:**
- Create: `.github/workflows/pr-ci.yaml`
- Create: `.github/workflows/release.yaml`

**Interfaces:**
- Consumes: the root build from Task 1 (`./gradlew build`, nmcp `publishAggregationToCentralPortal` task, version string in `jimmer-rsql-support/build.gradle.kts`)
- Produces: PR CI and manual release pipelines; no code-level interfaces

- [ ] **Step 1: Write `.github/workflows/pr-ci.yaml`**

```yaml
name: PR CI workflow.
on:
  pull_request:
    types: ["opened", "synchronize", "reopened"]

jobs:
  build:
    name: Run build
    runs-on: ubuntu-latest
    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Gradlew build
        run: ./gradlew build
```

- [ ] **Step 2: Write `.github/workflows/release.yaml`**

```yaml
name: Release

on:
  workflow_dispatch:

permissions:
  contents: write

jobs:
  release:
    name: Publish to Maven Central and create GitHub Release
    runs-on: ubuntu-latest
    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Extract version
        id: version
        run: |
          VERSION=$(sed -n 's/^version = "\(.*\)"/\1/p' jimmer-rsql-support/build.gradle.kts)
          if [ -z "$VERSION" ]; then
            echo "Could not extract version from jimmer-rsql-support/build.gradle.kts"
            exit 1
          fi
          echo "version=$VERSION" >> "$GITHUB_OUTPUT"

      - name: Check tag does not exist
        run: |
          if git ls-remote --exit-code --tags origin "v${{ steps.version.outputs.version }}"; then
            echo "Tag v${{ steps.version.outputs.version }} already exists"
            exit 1
          fi

      - name: Build
        run: ./gradlew build

      - name: Publish to Maven Central
        env:
          CENTRAL_USERNAME: ${{ secrets.CENTRAL_USERNAME }}
          CENTRAL_PASSWORD: ${{ secrets.CENTRAL_PASSWORD }}
          SIGNING_KEY: ${{ secrets.SIGNING_KEY }}
          SIGNING_PASSWORD: ${{ secrets.SIGNING_PASSWORD }}
        run: ./gradlew publishAggregationToCentralPortal

      - name: Create GitHub Release
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          gh release create "v${{ steps.version.outputs.version }}" \
            --target "${{ github.sha }}" \
            --title "v${{ steps.version.outputs.version }}" \
            --generate-notes \
            jimmer-rsql-support/build/libs/*.jar
```

- [ ] **Step 3: Validate YAML syntax**

```bash
python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/pr-ci.yaml')); yaml.safe_load(open('.github/workflows/release.yaml')); print('OK')"
```

Expected: `OK`

- [ ] **Step 4: Commit**

```bash
git add .github/
git commit -m "ci: pr build and release workflows"
```

---

### Task 3: Exception hierarchy

**Files:**
- Create: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/exception/JimmerRsqlSupportException.kt`
- Create: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/exception/InvalidDateFormatException.kt`
- Create: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/exception/InvalidEnumValueException.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `JimmerRsqlSupportException(message: String)` open base class; `InvalidDateFormatException(argument: String?, property: String?)`; `InvalidEnumValueException(javaType: Class<out Any>?, arg: String)`. Task 6 throws the latter two.

- [ ] **Step 1: Write `JimmerRsqlSupportException.kt`**

```kotlin
package com.github.ichanzhar.rsql.jimmer.exception

public open class JimmerRsqlSupportException(message: String) : RuntimeException(message)
```

- [ ] **Step 2: Write `InvalidDateFormatException.kt`**

Message text is identical to the JPA original.

```kotlin
package com.github.ichanzhar.rsql.jimmer.exception

public class InvalidDateFormatException(argument: String?, property: String?) :
    JimmerRsqlSupportException("The datetime parameter: '$argument' for the field: '$property' has an invalid date format.")
```

- [ ] **Step 3: Write `InvalidEnumValueException.kt`**

Message text is identical to the JPA original.

```kotlin
package com.github.ichanzhar.rsql.jimmer.exception

public class InvalidEnumValueException(javaType: Class<out Any>?, arg: String) :
    JimmerRsqlSupportException("can't find '$arg' value in ${javaType?.simpleName} enum")
```

- [ ] **Step 4: Dump API and build**

```bash
./gradlew apiDump build
```

Expected: `BUILD SUCCESSFUL`; `jimmer-rsql-support/api/jimmer-rsql-support.api` now lists the three exception classes.

- [ ] **Step 5: Commit**

```bash
git add jimmer-rsql-support/src jimmer-rsql-support/api
git commit -m "feat: exception hierarchy with JimmerRsqlSupportException base"
```

---

### Task 4: ParserContext and RsqlOperation

**Files:**
- Create: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/ParserContext.kt`
- Create: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/RsqlOperation.kt`

**Interfaces:**
- Consumes: `cz.jirutka.rsql.parser.ast.ComparisonOperator`, `cz.jirutka.rsql.parser.ast.RSQLOperators` (rsql-parser 2.1.0)
- Produces: `ParserContext` enum (`COMMON`, `POSTGRESQL`); `RsqlOperation` enum with `public val operator: ComparisonOperator`, `public val context: ParserContext`, and `RsqlOperation.from(operator: ComparisonOperator): RsqlOperation?`. Task 7 reads `RsqlOperation.<NAME>.operator`.

- [ ] **Step 1: Write `ParserContext.kt`**

```kotlin
package com.github.ichanzhar.rsql.jimmer

public enum class ParserContext {
    COMMON,
    POSTGRESQL,
}
```

- [ ] **Step 2: Write `RsqlOperation.kt`**

The JPA original's `getSimpleOperator` (stream + `orElse(null)`) becomes `from` with `entries.firstOrNull`; identity comparison (`===`) is kept because rsql-parser interns its built-in operators and custom operators are singletons on the enum constants.

```kotlin
package com.github.ichanzhar.rsql.jimmer

import cz.jirutka.rsql.parser.ast.ComparisonOperator
import cz.jirutka.rsql.parser.ast.RSQLOperators

public enum class RsqlOperation(
    public val operator: ComparisonOperator,
    public val context: ParserContext,
) {
    /** Equality, `*` wildcards translate to SQL LIKE: `title==Kotlin`, `title==*SQL*`. */
    EQUAL(RSQLOperators.EQUAL, ParserContext.COMMON),

    /** Inequality, `*` wildcards translate to SQL NOT LIKE: `title!=Kotlin`, `title!=*SQL*`. */
    NOT_EQUAL(RSQLOperators.NOT_EQUAL, ParserContext.COMMON),

    /** Greater than: `year>2000`. */
    GREATER_THAN(RSQLOperators.GREATER_THAN, ParserContext.COMMON),

    /** Greater than or equal: `year>=2000`. */
    GREATER_THAN_OR_EQUAL(RSQLOperators.GREATER_THAN_OR_EQUAL, ParserContext.COMMON),

    /** Less than: `year<2000`. */
    LESS_THAN(RSQLOperators.LESS_THAN, ParserContext.COMMON),

    /** Less than or equal: `year<=2000`. */
    LESS_THAN_OR_EQUAL(RSQLOperators.LESS_THAN_OR_EQUAL, ParserContext.COMMON),

    /** Membership: `year=in=(2000,2001)`. */
    IN(RSQLOperators.IN, ParserContext.COMMON),

    /** Exclusion: `year=out=(2000,2001)`. */
    NOT_IN(RSQLOperators.NOT_IN, ParserContext.COMMON),

    /** Null check: `rating=isNull=true` or `rating=isNull=false`. */
    IS_NULL(ComparisonOperator("=isNull="), ParserContext.COMMON),

    /** Case insensitive equality: `lastName=eqci=smith`. */
    EQUAL_CI(ComparisonOperator("=eqci="), ParserContext.COMMON),

    /** Empty collection check: `reviews=isEmpty=true` or `reviews=isEmpty=false`. */
    IS_EMPTY(ComparisonOperator("=isEmpty="), ParserContext.COMMON),

    /** PostgreSQL jsonb path equality, argument is `path|value`: `attributes=jsonbeq=color|red`. */
    JSONB_EQ(ComparisonOperator("=jsonbeq="), ParserContext.POSTGRESQL),

    /** PostgreSQL json path equality, argument is `path|value`: `attributes=jsoneq=color|red`. */
    JSON_EQ(ComparisonOperator("=jsoneq="), ParserContext.POSTGRESQL),
    ;

    public companion object {
        public fun from(operator: ComparisonOperator): RsqlOperation? =
            entries.firstOrNull { it.operator === operator }
    }
}
```

- [ ] **Step 3: Dump API and build**

```bash
./gradlew apiDump build
```

Expected: `BUILD SUCCESSFUL`; api dump gains `ParserContext` and `RsqlOperation`.

- [ ] **Step 4: Commit**

```bash
git add jimmer-rsql-support/src jimmer-rsql-support/api
git commit -m "feat: ParserContext and RsqlOperation enums"
```

---

### Task 5: Processor and Params types

**Files:**
- Create: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/operations/Processor.kt`
- Create: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/operations/Params.kt`

**Interfaces:**
- Consumes: `org.babyfish.jimmer.sql.kt.ast.expression.KNonNullExpression`, `org.babyfish.jimmer.sql.kt.ast.expression.KPropExpression`, `org.babyfish.jimmer.meta.ImmutableProp` (compileOnly jimmer-sql-kotlin 0.9.96)
- Produces: `public fun interface Processor { public fun process(): KNonNullExpression<Boolean>? }` and `public data class Params(val expression: KPropExpression<Any>?, val prop: ImmutableProp, val args: List<Any>, val argument: Any?)`. Task 7's `ProcessorParamsBuilder` typealias is `(Params) -> Processor`.

- [ ] **Step 1: Write `Processor.kt`**

```kotlin
package com.github.ichanzhar.rsql.jimmer.operations

import org.babyfish.jimmer.sql.kt.ast.expression.KNonNullExpression

public fun interface Processor {
    public fun process(): KNonNullExpression<Boolean>?
}
```

- [ ] **Step 2: Write `Params.kt`**

```kotlin
package com.github.ichanzhar.rsql.jimmer.operations

import org.babyfish.jimmer.meta.ImmutableProp
import org.babyfish.jimmer.sql.kt.ast.expression.KPropExpression

public data class Params(
    public val expression: KPropExpression<Any>?,
    public val prop: ImmutableProp,
    public val args: List<Any>,
    public val argument: Any?,
)
```

- [ ] **Step 3: Dump API and build**

```bash
./gradlew apiDump build
```

Expected: `BUILD SUCCESSFUL`; api dump gains `Processor` and `Params`. If resolution of jimmer types fails, verify the `compileOnly("org.babyfish.jimmer:jimmer-sql-kotlin:0.9.96")` line from Task 1 and check the two import paths against the artifact (both exist in 0.9.x: `org.babyfish.jimmer.meta.ImmutableProp`, `org.babyfish.jimmer.sql.kt.ast.expression.KNonNullExpression`, `org.babyfish.jimmer.sql.kt.ast.expression.KPropExpression`).

- [ ] **Step 4: Commit**

```bash
git add jimmer-rsql-support/src jimmer-rsql-support/api
git commit -m "feat: Processor fun interface and Params for operator dispatch"
```

---

### Task 6: JavaTypeUtil and ArgumentConvertor

**Files:**
- Create: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/utils/JavaTypeUtil.kt`
- Create: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/utils/ArgumentConvertor.kt`

**Interfaces:**
- Consumes: `InvalidDateFormatException`, `InvalidEnumValueException` from Task 3
- Produces: `JavaTypeUtil.getPropertyJavaType(propertyJavaType: Class<out Any>?): Class<out Any>?` and `ArgumentConvertor.castArgument(arg: String, property: String?, javaType: Class<out Any>?): Any`. Phase 2 processors call both with `ImmutableProp.returnClass`.

- [ ] **Step 1: Write `JavaTypeUtil.kt`**

Same mapping table as the JPA original, immutable map instead of a mutable map filled in an init block. The `Class` references are kept expression-for-expression identical to the original to preserve behavior (`Boolean::class.java` is the primitive class, `Integer::class.java` is the wrapper class, exactly as before).

```kotlin
package com.github.ichanzhar.rsql.jimmer.utils

public object JavaTypeUtil {
    private val primitiveWrappers: Map<String, Class<out Any>> = mapOf(
        "java.lang.Boolean" to Boolean::class.java,
        "boolean" to Boolean::class.java,
        "byte" to Byte::class.java,
        "char" to Character::class.java,
        "double" to Double::class.java,
        "float" to Float::class.java,
        "int" to Integer::class.java,
        "long" to Long::class.java,
        "short" to Short::class.java,
    )

    public fun getPropertyJavaType(propertyJavaType: Class<out Any>?): Class<out Any>? =
        primitiveWrappers[propertyJavaType?.name] ?: propertyJavaType
}
```

- [ ] **Step 2: Write `ArgumentConvertor.kt`**

Same coercion matrix and silent-fallback contract as the JPA original, restructured: `toXOrNull()` / `runCatching` per branch instead of one broad outer try/catch. Approved deviation: `InvalidDateFormatException` and `InvalidEnumValueException` propagate instead of being swallowed by the outer catch.

```kotlin
package com.github.ichanzhar.rsql.jimmer.utils

import com.github.ichanzhar.rsql.jimmer.exception.InvalidDateFormatException
import com.github.ichanzhar.rsql.jimmer.exception.InvalidEnumValueException
import java.math.BigDecimal
import java.math.BigInteger
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.UUID

public object ArgumentConvertor {
    private val fallbackDateTimeFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    public fun castArgument(arg: String, property: String?, javaType: Class<out Any>?): Any =
        when (javaType) {
            Int::class.java -> arg.toIntOrNull() ?: arg
            Long::class.java -> arg.toLongOrNull() ?: arg
            BigInteger::class.java -> arg.toBigIntegerOrNull() ?: arg
            Double::class.java -> arg.toDoubleOrNull() ?: arg
            Float::class.java -> arg.toFloatOrNull() ?: arg
            BigDecimal::class.java -> arg.toBigDecimalOrNull() ?: arg
            Char::class.java -> arg.firstOrNull() ?: arg
            Short::class.java -> arg.toShortOrNull() ?: arg
            Boolean::class.java -> arg.toBoolean()
            UUID::class.java -> parsedOrRaw(arg) { UUID.fromString(it) }
            Timestamp::class.java, Date::class.java -> parseDate(arg, property)
            LocalDate::class.java -> parsedOrRaw(arg) { LocalDate.parse(it) }
            LocalDateTime::class.java -> parsedOrRaw(arg) { LocalDateTime.parse(it) }
            LocalTime::class.java -> parsedOrRaw(arg) { LocalTime.parse(it) }
            OffsetDateTime::class.java -> parsedOrRaw(arg) { OffsetDateTime.parse(it) }
            ZonedDateTime::class.java -> parsedOrRaw(arg) { ZonedDateTime.parse(it) }
            else -> if (javaType?.isEnum == true) enumValue(javaType, arg) else arg
        }

    private fun parsedOrRaw(arg: String, parse: (String) -> Any): Any =
        runCatching { parse(arg) }.getOrDefault(arg)

    private fun parseDate(arg: String, property: String?): Date =
        runCatching { LocalDateTime.parse(arg) }
            .recoverCatching { LocalDateTime.parse(arg, fallbackDateTimeFormat) }
            .map { Date.from(it.atZone(ZoneId.systemDefault()).toInstant()) }
            .getOrElse { throw InvalidDateFormatException(arg, property) }

    private fun enumValue(enumClass: Class<out Any>, value: String): Enum<*> =
        enumClass.enumConstants.filterIsInstance<Enum<*>>().firstOrNull { it.name == value }
            ?: throw InvalidEnumValueException(enumClass, value)
}
```

- [ ] **Step 3: Dump API and build**

```bash
./gradlew apiDump build
```

Expected: `BUILD SUCCESSFUL`; api dump gains `JavaTypeUtil` and `ArgumentConvertor`.

- [ ] **Step 4: Commit**

```bash
git add jimmer-rsql-support/src jimmer-rsql-support/api
git commit -m "feat: JavaTypeUtil and ArgumentConvertor with typed coercion"
```

---

### Task 7: RsqlOperationsRegistry and RsqlParserFactory

**Files:**
- Create: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/utils/RsqlOperationsRegistry.kt`
- Create: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/utils/RsqlParserFactory.kt`

**Interfaces:**
- Consumes: `RsqlOperation`, `ParserContext` (Task 4); `Processor`, `Params` (Task 5); `cz.jirutka.rsql.parser.RSQLParser`
- Produces: `public typealias ProcessorParamsBuilder = (Params) -> Processor`; `RsqlOperationsRegistry` object with `operationProcessors: Map<ComparisonOperator, ProcessorParamsBuilder>`, `operations: Set<ComparisonOperator>`, `registerOperation(operator, processor)`, `initDefaultPostgresOperation()`; `RsqlParserFactory.instance(context: ParserContext? = null): RSQLParser`. Phase 2 replaces the stub factories via `registerOperation`; Task 8's smoke main calls `RsqlParserFactory.instance().parse(...)`.

- [ ] **Step 1: Write `RsqlOperationsRegistry.kt`**

`ConcurrentHashMap` backing store, read-only views on the public surface (parent plan section 6). COMMON operators are seeded with factories whose processors throw `NotImplementedError`; Phase 2 replaces every stub with a real processor registration.

```kotlin
package com.github.ichanzhar.rsql.jimmer.utils

import com.github.ichanzhar.rsql.jimmer.RsqlOperation
import com.github.ichanzhar.rsql.jimmer.operations.Params
import com.github.ichanzhar.rsql.jimmer.operations.Processor
import cz.jirutka.rsql.parser.ast.ComparisonOperator
import java.util.concurrent.ConcurrentHashMap

public typealias ProcessorParamsBuilder = (Params) -> Processor

public object RsqlOperationsRegistry {
    private val processors: ConcurrentHashMap<ComparisonOperator, ProcessorParamsBuilder> =
        ConcurrentHashMap(
            mapOf(
                RsqlOperation.EQUAL.operator to phase2Stub("=="),
                RsqlOperation.NOT_EQUAL.operator to phase2Stub("!="),
                RsqlOperation.GREATER_THAN.operator to phase2Stub(">"),
                RsqlOperation.GREATER_THAN_OR_EQUAL.operator to phase2Stub(">="),
                RsqlOperation.LESS_THAN.operator to phase2Stub("<"),
                RsqlOperation.LESS_THAN_OR_EQUAL.operator to phase2Stub("<="),
                RsqlOperation.IN.operator to phase2Stub("=in="),
                RsqlOperation.NOT_IN.operator to phase2Stub("=out="),
                RsqlOperation.IS_NULL.operator to phase2Stub("=isNull="),
                RsqlOperation.EQUAL_CI.operator to phase2Stub("=eqci="),
                RsqlOperation.IS_EMPTY.operator to phase2Stub("=isEmpty="),
            ),
        )

    public val operationProcessors: Map<ComparisonOperator, ProcessorParamsBuilder>
        get() = processors

    public val operations: Set<ComparisonOperator>
        get() = processors.keys

    public fun registerOperation(operator: ComparisonOperator, processor: ProcessorParamsBuilder) {
        processors[operator] = processor
    }

    public fun initDefaultPostgresOperation() {
        registerOperation(RsqlOperation.JSON_EQ.operator, phase2Stub("=jsoneq="))
        registerOperation(RsqlOperation.JSONB_EQ.operator, phase2Stub("=jsonbeq="))
    }

    private fun phase2Stub(symbol: String): ProcessorParamsBuilder =
        { Processor { TODO("Processor for '$symbol' arrives in phase 2") } }
}
```

- [ ] **Step 2: Write `RsqlParserFactory.kt`**

The operator set is snapshot-copied (`toSet()`) so the returned parser is unaffected by later registry mutation.

```kotlin
package com.github.ichanzhar.rsql.jimmer.utils

import com.github.ichanzhar.rsql.jimmer.ParserContext
import cz.jirutka.rsql.parser.RSQLParser

public object RsqlParserFactory {
    public fun instance(context: ParserContext? = null): RSQLParser {
        if (context == ParserContext.POSTGRESQL) {
            RsqlOperationsRegistry.initDefaultPostgresOperation()
        }
        return RSQLParser(RsqlOperationsRegistry.operations.toSet())
    }
}
```

- [ ] **Step 3: Dump API and build**

```bash
./gradlew apiDump build
```

Expected: `BUILD SUCCESSFUL`; api dump gains `RsqlOperationsRegistry`, `RsqlParserFactory`, and the `ProcessorParamsBuilder` typealias surface.

- [ ] **Step 4: Commit**

```bash
git add jimmer-rsql-support/src jimmer-rsql-support/api
git commit -m "feat: operations registry and parser factory"
```

---

### Task 8: Smoke check (Phase 1 acceptance)

**Files:**
- Create then delete: `jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/Smoke.kt`
- Modify then revert: `jimmer-rsql-support/build.gradle.kts` (temporary JavaExec task)

**Interfaces:**
- Consumes: `RsqlParserFactory.instance()` from Task 7
- Produces: nothing permanent; verifies the Phase 1 acceptance criterion (parse `name==x;year>2000` into an AST) and leaves the tree clean

- [ ] **Step 1: Write temporary `Smoke.kt`**

```kotlin
package com.github.ichanzhar.rsql.jimmer

import com.github.ichanzhar.rsql.jimmer.utils.RsqlParserFactory

public fun main() {
    val node = RsqlParserFactory.instance().parse("name==x;year>2000")
    println(node.javaClass.simpleName)
    println(node)
}
```

If explicitApi complains about a missing return type, change the signature to `public fun main(): Unit {`.

- [ ] **Step 2: Append temporary run task to `jimmer-rsql-support/build.gradle.kts`**

```kotlin
tasks.register<JavaExec>("smoke") {
    classpath = sourceSets.main.get().runtimeClasspath + configurations.compileClasspath.get()
    mainClass.set("com.github.ichanzhar.rsql.jimmer.SmokeKt")
}
```

- [ ] **Step 3: Run the smoke check**

```bash
./gradlew :jimmer-rsql-support:smoke
```

Expected output: first line `AndNode`, second line the parsed AST containing both comparisons, e.g. `(name=='x';year>2000)` (exact quoting may vary by rsql-parser toString). This fulfills the Phase 1 acceptance criterion.

- [ ] **Step 4: Delete the temporary files**

```bash
rm jimmer-rsql-support/src/main/kotlin/com/github/ichanzhar/rsql/jimmer/Smoke.kt
git checkout jimmer-rsql-support/build.gradle.kts
```

- [ ] **Step 5: Final verification**

```bash
./gradlew build
git status --porcelain
```

Expected: `BUILD SUCCESSFUL` and empty `git status` output (clean tree, nothing to commit).
