package com.github.ichanzhar.rsql.jimmer.exception

public class UnsupportedSelectorException(
    selector: String,
    reason: String,
) : JimmerRsqlSupportException("Unsupported selector '$selector': $reason")
