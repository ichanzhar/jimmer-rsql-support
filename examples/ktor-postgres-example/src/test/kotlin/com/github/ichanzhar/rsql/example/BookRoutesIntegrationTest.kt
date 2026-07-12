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
}
