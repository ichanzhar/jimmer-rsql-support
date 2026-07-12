package com.github.ichanzhar.rsql.example.model

import org.babyfish.jimmer.sql.Entity
import org.babyfish.jimmer.sql.GeneratedValue
import org.babyfish.jimmer.sql.GenerationType
import org.babyfish.jimmer.sql.Id

@Entity
interface Author {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long
    val name: String
    val email: String?
}
