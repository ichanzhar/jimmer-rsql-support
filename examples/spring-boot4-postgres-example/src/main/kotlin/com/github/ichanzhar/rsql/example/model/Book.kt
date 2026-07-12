package com.github.ichanzhar.rsql.example.model

import org.babyfish.jimmer.sql.Entity
import org.babyfish.jimmer.sql.GeneratedValue
import org.babyfish.jimmer.sql.GenerationType
import org.babyfish.jimmer.sql.Id
import org.babyfish.jimmer.sql.JoinTable
import org.babyfish.jimmer.sql.ManyToMany
import org.babyfish.jimmer.sql.ManyToOne
import org.babyfish.jimmer.sql.OneToMany

@Entity
interface Book {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long
    val title: String
    val isbn: String?
    val publicationYear: Int

    @ManyToOne
    val author: Author?

    val dimensions: Dimensions

    @OneToMany(mappedBy = "book")
    val reviews: List<Review>

    @OneToMany(mappedBy = "book")
    val chapters: List<Chapter>

    @ManyToMany
    @JoinTable(name = "book_categories", joinColumnName = "book_id", inverseJoinColumnName = "category_id")
    val categories: List<Category>
}
