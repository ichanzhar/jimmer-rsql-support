package com.github.ichanzhar.rsql.example

import com.github.ichanzhar.rsql.example.model.Book
import com.github.ichanzhar.rsql.jimmer.ParserContext
import com.github.ichanzhar.rsql.jimmer.createRsqlQuery
import com.github.ichanzhar.rsql.jimmer.exception.JimmerRsqlSupportException
import cz.jirutka.rsql.parser.RSQLParserException
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.babyfish.jimmer.sql.kt.KSqlClient

data class BookDto(
    val id: Long,
    val title: String,
    val isbn: String?,
    val publicationYear: Int,
)

fun Application.bookModule(sqlClient: KSqlClient) {
    install(ContentNegotiation) { jackson() }
    install(StatusPages) {
        exception<JimmerRsqlSupportException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "invalid RSQL query")))
        }
        exception<RSQLParserException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "invalid RSQL query")))
        }
    }
    routing {
        get("/books") {
            val query = call.request.queryParameters["query"]
            val books =
                withContext(Dispatchers.IO) {
                    if (query.isNullOrBlank()) {
                        sqlClient.createQuery(Book::class) { select(table) }.execute()
                    } else {
                        sqlClient.createRsqlQuery(Book::class, query, ParserContext.POSTGRESQL).execute()
                    }
                }
            call.respond(books.map { BookDto(it.id, it.title, it.isbn, it.publicationYear) })
        }
    }
}
