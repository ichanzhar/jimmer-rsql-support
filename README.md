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
