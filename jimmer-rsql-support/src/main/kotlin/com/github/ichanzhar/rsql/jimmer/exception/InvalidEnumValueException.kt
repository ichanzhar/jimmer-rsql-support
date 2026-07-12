package com.github.ichanzhar.rsql.jimmer.exception

public class InvalidEnumValueException(
    javaType: Class<out Any>?,
    arg: String,
) : JimmerRsqlSupportException("can't find '$arg' value in ${javaType?.simpleName} enum")
