package com.github.danieladams456.morsebook.app.data

import androidx.room.*
import com.github.danieladams456.morsebook.*
import com.github.danieladams456.morsebook.app.playback.*

/** Missing row = unread, a bookmark = partial, a row with no bookmark = read. */
@Entity(primaryKeys = ["book", "chapter"])
data class ChapterReading(val book: String, val chapter: String, val offset: Int?)

@Entity
data class LastPlayed(val book: String, val chapter: String, @PrimaryKey val singleton: Int = 1)

@Dao
abstract class ReadingDao : ReadingWriter {
    @Upsert abstract fun save(row: ChapterReading)
    @Upsert abstract fun save(row: LastPlayed)
    @Query("SELECT * FROM ChapterReading") abstract fun chapters(): List<ChapterReading>
    @Query("SELECT * FROM LastPlayed WHERE singleton = 1") abstract fun lastPlayed(): LastPlayed?

    @Transaction
    override fun started(chapter: ChapterKey, offset: Int) {
        checkpoint(chapter, offset)
        save(LastPlayed(chapter.book, chapter.chapter))
    }

    override fun checkpoint(chapter: ChapterKey, offset: Int) {
        save(ChapterReading(chapter.book, chapter.chapter, offset))
    }
}

@Database(entities = [ChapterReading::class, LastPlayed::class], version = 1, exportSchema = false)
abstract class ReadingDatabase : RoomDatabase() {
    abstract fun reading(): ReadingDao
}

/** Reconcile history with the installed catalog before publishing the first route. */
internal fun restoreReading(
    books: List<Book>,
    rows: List<ChapterReading>,
    lastPlayed: LastPlayed?,
    text: (ChapterKey) -> String
): PlaybackState {
    val chapters = books.flatMap { book -> book.chapters.map { ChapterKey(book.id, it.id) to it } }.toMap()
    val last = lastPlayed?.let { ChapterKey(it.book, it.chapter) }?.takeIf { it in chapters }
    val bookmarks = mutableMapOf<ChapterKey, Int>()
    val read = mutableSetOf<ChapterKey>()
    for (row in rows) {
        val key = ChapterKey(row.book, row.chapter)
        val chapter = chapters[key] ?: continue
        val offset = row.offset
        if (offset == null) read += key
        else bookmarks[key] = if (offset in 0 until chapter.textLength) offset else {
            DefaultMorsePcmStream(StreamOptions(text(key), DEFAULT_SAMPLE_RATE_HZ, offset.coerceAtLeast(0)))
                .startingWordOffset ?: error("No playable word in ${chapter.id}")
        }
    }
    return PlaybackState(selected = last, lastPlayed = last, bookmarks = bookmarks, readChapters = read)
}
