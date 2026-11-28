package com.github.danieladams456.morsebook.app.data

import androidx.room.*
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
