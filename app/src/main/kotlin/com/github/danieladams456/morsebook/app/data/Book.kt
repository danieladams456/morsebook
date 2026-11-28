package com.github.danieladams456.morsebook.app.data

import kotlinx.serialization.Serializable

@Serializable
data class Book(val id: String, val title: String, val chapters: List<Chapter>)

/** The ID is scoped to its book. [textLength] counts UTF-16 code units. */
@Serializable
data class Chapter(val id: String, val title: String, val textLength: Int)
