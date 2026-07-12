package com.github.ichanzhar.rsql.jimmer.exception

public class InvalidDateFormatException(
    argument: String?,
    property: String?,
) : JimmerRsqlSupportException(
        "The datetime parameter: '$argument' for the field: '$property' has an invalid date format.",
    )
