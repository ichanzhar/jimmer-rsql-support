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

    public fun castArgument(
        arg: String,
        property: String?,
        javaType: Class<out Any>?,
    ): Any =
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

    private fun parsedOrRaw(
        arg: String,
        parse: (String) -> Any,
    ): Any = runCatching { parse(arg) }.getOrDefault(arg)

    private fun parseDate(
        arg: String,
        property: String?,
    ): Date =
        runCatching { LocalDateTime.parse(arg) }
            .recoverCatching { LocalDateTime.parse(arg, fallbackDateTimeFormat) }
            .map { Date.from(it.atZone(ZoneId.systemDefault()).toInstant()) }
            .getOrElse { throw InvalidDateFormatException(arg, property) }

    private fun enumValue(
        enumClass: Class<out Any>,
        value: String,
    ): Enum<*> =
        enumClass.enumConstants.filterIsInstance<Enum<*>>().firstOrNull { it.name == value }
            ?: throw InvalidEnumValueException(enumClass, value)
}
