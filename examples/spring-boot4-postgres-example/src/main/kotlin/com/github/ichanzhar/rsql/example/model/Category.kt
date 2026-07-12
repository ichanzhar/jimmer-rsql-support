package com.github.ichanzhar.rsql.example.model

import org.babyfish.jimmer.sql.Entity
import org.babyfish.jimmer.sql.GeneratedValue
import org.babyfish.jimmer.sql.GenerationType
import org.babyfish.jimmer.sql.Id
import org.babyfish.jimmer.sql.ManyToMany

@Entity
interface Category {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long
    val name: String

    @ManyToMany(mappedBy = "categories")
    val books: List<Book>
}
