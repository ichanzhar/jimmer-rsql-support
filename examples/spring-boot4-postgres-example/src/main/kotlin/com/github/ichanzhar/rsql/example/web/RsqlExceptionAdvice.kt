package com.github.ichanzhar.rsql.example.web

import com.github.ichanzhar.rsql.jimmer.exception.JimmerRsqlSupportException
import cz.jirutka.rsql.parser.RSQLParserException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class RsqlExceptionAdvice {

    @ExceptionHandler(JimmerRsqlSupportException::class)
    fun handleRsql(ex: JimmerRsqlSupportException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.badRequest().body(mapOf("error" to ex.message))

    @ExceptionHandler(RSQLParserException::class)
    fun handleParse(ex: RSQLParserException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.badRequest().body(mapOf("error" to (ex.message ?: "invalid RSQL query")))
}
