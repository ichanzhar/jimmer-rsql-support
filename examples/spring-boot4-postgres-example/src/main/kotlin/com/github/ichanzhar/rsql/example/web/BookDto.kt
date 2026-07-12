package com.github.ichanzhar.rsql.example.web

data class BookDto(
    val id: Long,
    val title: String,
    val isbn: String?,
    val publicationYear: Int,
)
