package com.github.ichanzhar.rsql.example.web

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(SqlCaptureConfig::class)
class BookControllerIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun seed() {
        jdbcTemplate.execute(
            "truncate table review_label, book_tag, book_categories, review, chapter, book, category, author restart identity cascade",
        )
        jdbcTemplate.execute(
            """
            insert into author (id, name, email) values
                (1, 'J.R.R. Tolkien', 'tolkien@example.com'),
                (2, 'Frank Herbert', 'herbert@example.com');
            insert into category (id, name) values (1, 'Fantasy'), (2, 'Sci-Fi');
            insert into book (id, title, isbn, publication_year, author_id, width_cm, height_cm, weight_grams) values
                (1, 'The Hobbit', '9780618260300', 1937, 1, 13.0, 20.0, 340),
                (2, 'Dune', null, 1965, 2, 15.0, 23.0, 480);
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
            """.trimIndent(),
        )
    }

    @Test
    fun `returns all books when query is absent`() {
        mockMvc.perform(get("/books"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `filters by wildcard equality`() {
        mockMvc.perform(get("/books").param("query", "title==*Hobbit*"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("The Hobbit"))
    }

    @Test
    fun `filters by exact equality`() {
        mockMvc.perform(get("/books").param("query", "title==Dune"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("Dune"))
    }

    @Test
    fun `filters by inequality`() {
        mockMvc.perform(get("/books").param("query", "title!=Dune"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("The Hobbit"))
    }

    @Test
    fun `filters by greater than with fiql alias`() {
        mockMvc.perform(get("/books").param("query", "publicationYear=gt=1950"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("Dune"))
    }

    @Test
    fun `combines range bounds with logical and`() {
        mockMvc.perform(get("/books").param("query", "publicationYear>=1937;publicationYear<=1965"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `filters by in list`() {
        mockMvc.perform(get("/books").param("query", "publicationYear=in=(1937,2000)"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("The Hobbit"))
    }

    @Test
    fun `filters by not in list`() {
        mockMvc.perform(get("/books").param("query", "publicationYear=out=(1937)"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("Dune"))
    }

    @Test
    fun `filters by is null`() {
        mockMvc.perform(get("/books").param("query", "isbn=isNull=true"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("Dune"))
    }

    @Test
    fun `filters by is not null`() {
        mockMvc.perform(get("/books").param("query", "isbn=isNull=false"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("The Hobbit"))
    }

    @Test
    fun `filters by reference join path`() {
        mockMvc.perform(get("/books").param("query", "author.name==*Tolkien*"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("The Hobbit"))
    }

    @Test
    fun `filters case insensitively through a join`() {
        mockMvc.perform(get("/books").param("query", "author.email=eqci=HERBERT@EXAMPLE.COM"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("Dune"))
    }

    @Test
    fun `filters by embedded value object field`() {
        mockMvc.perform(get("/books").param("query", "dimensions.weightGrams=gt=400"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("Dune"))
    }

    @Test
    fun `combines predicates with logical or`() {
        mockMvc.perform(get("/books").param("query", "title==Dune,title==*Hobbit*"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `rejects unknown property with 400`() {
        mockMvc.perform(get("/books").param("query", "nosuchfield==1"))
            .andExpect(status().isBadRequest)
            .andExpect(content().string(containsString("nosuchfield")))
    }

    @Test
    fun `rejects malformed rsql with 400`() {
        mockMvc.perform(get("/books").param("query", "title=="))
            .andExpect(status().isBadRequest)
    }

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
}
