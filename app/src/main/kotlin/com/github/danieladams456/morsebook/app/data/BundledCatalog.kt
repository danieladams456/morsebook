package com.github.danieladams456.morsebook.app.data

import java.io.InputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream

/** [openAsset] opens a fresh stream for a path relative to the bundled content directory. */
class BundledCatalog(private val openAsset: (String) -> InputStream) {
    @OptIn(ExperimentalSerializationApi::class)
    fun loadBooks(): List<Book> {
        val catalog = openAsset("catalog.json").use { json.decodeFromStream<Catalog>(it) }
        require(catalog.books.isNotEmpty()) { "catalog.json requires at least one book" }
        val bookIds = mutableSetOf<String>()
        for (book in catalog.books) {
            validateEntry(book.id, book.title, bookIds)
            require(book.chapters.isNotEmpty()) { "Book ${book.id} requires at least one chapter" }
            val chapterIds = mutableSetOf<String>()
            for (chapter in book.chapters) {
                validateEntry(chapter.id, chapter.title, chapterIds)
                require(chapter.textLength > 0) { "Invalid text length for ${book.id}/${chapter.id} in catalog.json" }
            }
        }
        return catalog.books
    }

    /** The returned text's length and bookmark offsets both count UTF-16 code units. */
    fun loadChapterText(bookId: String, chapterId: String): String {
        require(assetId.matches(bookId) && assetId.matches(chapterId)) { "Invalid book or chapter ID" }
        return openAsset("$bookId/$chapterId.txt").bufferedReader().use { it.readText() }
    }

    private fun validateEntry(id: String, title: String, seenIds: MutableSet<String>) {
        require(assetId.matches(id)) { "Invalid ID $id in catalog.json" }
        require(seenIds.add(id)) { "Duplicate ID $id in catalog.json" }
        require(title.isNotBlank()) { "Blank title for $id in catalog.json" }
    }

    @Serializable
    private data class Catalog(val books: List<Book>)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val assetId = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    }
}
