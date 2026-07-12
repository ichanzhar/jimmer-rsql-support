package com.github.ichanzhar.rsql.example.model

import org.babyfish.jimmer.sql.Embeddable

@Embeddable
interface Dimensions {
    val widthCm: Double
    val heightCm: Double
    val weightGrams: Int
}
