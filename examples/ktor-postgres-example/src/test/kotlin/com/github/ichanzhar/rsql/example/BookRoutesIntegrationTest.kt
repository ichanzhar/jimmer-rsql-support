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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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

        private val SEED_SQL =
            """
            insert into author (id, name, email) values
                (1, 'J.R.R. Tolkien', 'tolkien@example.com'),
                (2, 'Frank Herbert', 'herbert@example.com');
            insert into category (id, name) values (1, 'Fantasy'), (2, 'Sci-Fi');
            insert into book (id, title, isbn, publication_year, author_id, width_cm, height_cm, weight_grams, metadata, details) values
                (1, 'The Hobbit', '9780618260300', 1937, 1, 13.0, 20.0, 340,
                 '{"genre":"fantasy","pages":310,"publisher":{"name":"Unwin"}}'::jsonb, '{"format":"hardcover"}'::json),
                (2, 'Dune', null, 1965, 2, 15.0, 23.0, 480,
                 '{"genre":"scifi","pages":412,"publisher":{"name":"Chilton"}}'::jsonb, '{"format":"paperback"}'::json);
            insert into review (id, rating, comment, book_id) values
                (1, 5, 'A timeless classic', 1),
                (2, 4, 'Great start to the saga', 1),
                (3, 5, 'Genre-defining', 2);
            insert into chapter (id, sequence, title, book_id) values
                (1, 1, 'An Unexpected Party', 1),
                (2, 2, 'Roast Mutton', 1),
                (3, 1, 'Prologue', 2);
            insert into book_tag (id, tag, book_id) values
                (1, 'fantasy', 1), (2, 'classic', 1), (3, 'scifi', 2), (4, 'epic', 2);
            insert into review_label (id, label, review_id) values
                (1, 'editorial', 1), (2, 'community', 2), (3, 'urgent', 3), (4, 'editorial', 3);
            insert into book_categories (book_id, category_id) values (1, 1), (2, 2);
            """.trimIndent()
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

    @Test
    fun `filters by wildcard equality`() = assertSingle("title==*Hobbit*", "The Hobbit")

    @Test
    fun `filters by exact equality`() = assertSingle("title==Dune", "Dune")

    @Test
    fun `filters by inequality`() = assertSingle("title!=Dune", "The Hobbit")

    @Test
    fun `filters by greater than with fiql alias`() = assertSingle("publicationYear=gt=1950", "Dune")

    @Test
    fun `combines range bounds with logical and`() =
        assertCount("publicationYear>=1937;publicationYear<=1965", 2)

    @Test
    fun `filters by in list`() = assertSingle("publicationYear=in=(1937,2000)", "The Hobbit")

    @Test
    fun `filters by not in list`() = assertSingle("publicationYear=out=(1937)", "Dune")

    @Test
    fun `filters by is null`() = assertSingle("isbn=isNull=true", "Dune")

    @Test
    fun `filters by is not null`() = assertSingle("isbn=isNull=false", "The Hobbit")

    @Test
    fun `filters by reference join path`() = assertSingle("author.name==*Tolkien*", "The Hobbit")

    @Test
    fun `filters case insensitively through a join`() =
        assertSingle("author.email=eqci=HERBERT@EXAMPLE.COM", "Dune")

    @Test
    fun `filters by embedded value object field`() = assertSingle("dimensions.weightGrams=gt=400", "Dune")

    @Test
    fun `combines predicates with logical or`() = assertCount("title==Dune,title==*Hobbit*", 2)

    @Test
    fun `rejects unknown property with 400`() = assertBadRequest("nosuchfield==1", "nosuchfield")

    @Test
    fun `rejects malformed rsql with 400`() = assertBadRequest("title==")

    @Test
    fun `filters by child entity tag through exists`() = assertSingle("tags.tag==classic", "The Hobbit")

    @Test
    fun `filters by one-to-many field`() = assertCount("reviews.rating==5", 2)

    @Test
    fun `negation across collections uses exists semantics`() =
        assertSingle("reviews.rating!=5", "The Hobbit")

    @Test
    fun `filters by two-level nested collection path`() =
        assertSingle("reviews.labels.label==urgent", "Dune")

    @Test
    fun `filters by list association field`() = assertSingle("chapters.title==Prologue", "Dune")

    @Test
    fun `filters by many-to-many association`() = assertSingle("categories.name==Fantasy", "The Hobbit")

    @Test
    fun `combines join and nested collection filter with logical and`() =
        assertSingle("author.name==*Tolkien*;reviews.labels.label==editorial", "The Hobbit")

    @Test
    fun `rejects unknown property inside collection path with 400`() =
        assertBadRequest("tags.nosuch==1", "tags.nosuch")

    @Test
    fun `rejects non-isEmpty operator on bare collection with 400`() =
        assertBadRequest("reviews==5", "=isEmpty=")

    @Test
    fun `rejects isEmpty on scalar property with 400`() = assertBadRequest("title=isEmpty=true")

    @Test
    fun `finds books with no reviews via isEmpty true`() {
        insertBookWithoutRelations()
        assertSingle("reviews=isEmpty=true", "The Silmarillion")
    }

    @Test
    fun `finds books with reviews via isEmpty false`() {
        insertBookWithoutRelations()
        assertCount("reviews=isEmpty=false", 2)
    }

    @Test
    fun `collection query is built as exists without distinct`() {
        CapturingExecutor.statements.clear()
        val (status, body) = searchRaw("reviews.rating==5")
        assertEquals(HttpStatusCode.OK, status, body)
        val sql = CapturingExecutor.statements.joinToString("\n").lowercase()
        assertTrue(sql.contains("exists"), sql)
        assertFalse(sql.contains("distinct"), sql)
    }

    @Test
    fun `reference join query uses left join`() {
        CapturingExecutor.statements.clear()
        val (status, body) = searchRaw("author.name==*Tolkien*")
        assertEquals(HttpStatusCode.OK, status, body)
        val sql = CapturingExecutor.statements.joinToString("\n").lowercase()
        assertTrue(sql.contains("left join"), sql)
    }

    @Test
    fun `isEmpty true is built as not exists`() {
        CapturingExecutor.statements.clear()
        val (status, body) = searchRaw("reviews=isEmpty=true")
        assertEquals(HttpStatusCode.OK, status, body)
        val sql = CapturingExecutor.statements.joinToString("\n").lowercase()
        assertTrue(sql.contains("not exists"), sql)
    }

    @Test
    fun `filters by jsonb path equality`() = assertSingle("metadata=jsonbeq=genre|scifi", "Dune")

    @Test
    fun `filters by nested jsonb path`() =
        assertSingle("metadata=jsonbeq=publisher.name|Chilton", "Dune")

    @Test
    fun `filters by json path equality`() = assertSingle("details=jsoneq=format|paperback", "Dune")

    @Test
    fun `rejects malformed json operator argument with 400`() =
        assertBadRequest("metadata=jsonbeq=nopipe", "<json.path>|<value>")

    @Test
    fun `collection match yields one row per parent`() = assertCount("reviews.rating>=4", 2)
}
