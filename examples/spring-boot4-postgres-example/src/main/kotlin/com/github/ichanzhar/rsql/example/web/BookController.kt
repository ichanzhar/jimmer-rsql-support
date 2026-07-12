package com.github.ichanzhar.rsql.example.web

import com.github.ichanzhar.rsql.example.model.Book
import com.github.ichanzhar.rsql.jimmer.ParserContext
import com.github.ichanzhar.rsql.jimmer.createRsqlQuery
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/books")
class BookController(private val sqlClient: KSqlClient) {

    @GetMapping
    fun search(@RequestParam(required = false) query: String?): List<BookDto> {
        val books = if (query.isNullOrBlank()) {
            sqlClient.createQuery(Book::class) { select(table) }.execute()
        } else {
            sqlClient.createRsqlQuery(Book::class, query, ParserContext.POSTGRESQL).execute()
        }
        return books.map { BookDto(it.id, it.title, it.isbn, it.publicationYear) }
    }
}
