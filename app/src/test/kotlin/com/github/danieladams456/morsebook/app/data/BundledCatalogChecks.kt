package com.github.danieladams456.morsebook.app.data

import com.github.danieladams456.morsebook.DEFAULT_SAMPLE_RATE_HZ
import com.github.danieladams456.morsebook.DEFAULT_TONE_HZ
import com.github.danieladams456.morsebook.DEFAULT_WPM
import com.github.danieladams456.morsebook.DefaultMorsePcmStream
import com.github.danieladams456.morsebook.StreamOptions
import com.github.danieladams456.morsebook.SynthesisOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class BundledCatalogChecks {
    @Test
    fun loadsMetadataInCatalogOrderAndOpensChapterTextOnDemand() {
        val assets = mapOf(
            "catalog.json" to """
                {"books": [{"id": "example", "title": "Example Book", "chapters": [
                    {"id": "02", "title": "Second", "textLength": 5},
                    {"id": "01", "title": "First", "textLength": 1}
                ]}]}
            """.trimIndent(),
            "example/02.txt" to "😀 A\n"
        )
        val opened = mutableListOf<String>()
        val catalog = BundledCatalog { path ->
            opened += path
            assets.getValue(path).byteInputStream()
        }

        val book = catalog.loadBooks().single()
        assertEquals(
            Book("example", "Example Book", listOf(Chapter("02", "Second", 5), Chapter("01", "First", 1))),
            book
        )
        assertEquals(listOf("catalog.json"), opened)

        val text = catalog.loadChapterText(book.id, book.chapters.first().id)
        assertEquals("😀 A\n", text)
        assertEquals(book.chapters.first().textLength, text.length)
        assertEquals(listOf("catalog.json", "example/02.txt"), opened)
    }

    @Test
    fun rejectsDuplicateChapterIds() {
        val catalog = BundledCatalog { path ->
            assertEquals("catalog.json", path)
            """
                {"books": [{"id": "example", "title": "Example Book", "chapters": [
                    {"id": "01", "title": "First", "textLength": 1},
                    {"id": "01", "title": "Second", "textLength": 2}
                ]}]}
            """.trimIndent().byteInputStream()
        }

        assertFailsWith<IllegalArgumentException> { catalog.loadBooks() }
    }

    @Test
    fun bundledChaptersMatchTheCatalogAndCanPlay() {
        val catalog = BundledCatalog { path ->
            checkNotNull(javaClass.getResourceAsStream("/$path")) { "Missing bundled asset $path" }
        }
        val books = catalog.loadBooks()
        assertEquals(listOf("the-call-of-the-wild", "riders-of-the-purple-sage"), books.map { it.id })
        assertEquals(listOf(7, 23), books.map { it.chapters.size })
        assertEquals("Chapter I. Into the Primitive", books.first().chapters.first().title)
        assertEquals("Chapter I. Lassiter", books.last().chapters.first().title)

        val options = SynthesisOptions(DEFAULT_WPM, DEFAULT_TONE_HZ)
        for (book in books) {
            assertEquals((1..book.chapters.size).map { it.toString().padStart(2, '0') }, book.chapters.map { it.id })
            for (chapter in book.chapters) {
                val asset = "${book.id}/${chapter.id}.txt"
                val text = catalog.loadChapterText(book.id, chapter.id)
                assertEquals(chapter.textLength, text.length, asset)
                assertFalse(text.startsWith('\uFEFF') || '\r' in text, asset)
                val stream = DefaultMorsePcmStream(StreamOptions(text, DEFAULT_SAMPLE_RATE_HZ))
                assertEquals(1, stream.read(options, ShortArray(1)).framesWritten, asset)
            }
        }
    }
}
