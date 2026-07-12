package com.github.ichanzhar.rsql.jimmer.exception

public class UnknownPropertyException(
    selector: String,
    property: String,
    entityName: String,
) : JimmerRsqlSupportException("Unknown property '$property' in selector '$selector' on entity '$entityName'")
