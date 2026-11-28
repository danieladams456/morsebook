package com.github.danieladams456.morsebook.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.danieladams456.morsebook.app.playback.ChapterKey
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingDatabaseTest {
    @Test fun failedLastPlayedUpdateRollsBackChapterStart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        try {
            val dao = db.reading()
            dao.started(ChapterKey("book", "02"), 5)
            assertEquals(listOf(ChapterReading("book", "02", 5)), dao.chapters())
            assertEquals(LastPlayed("book", "02"), dao.lastPlayed())
            db.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER reject_start BEFORE INSERT ON LastPlayed
                BEGIN SELECT RAISE(ABORT, 'injected start failure'); END
            """.trimIndent())
            db.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER reject_update BEFORE UPDATE ON LastPlayed
                BEGIN SELECT RAISE(ABORT, 'injected start failure'); END
            """.trimIndent())
            assertTrue(runCatching { dao.started(ChapterKey("book", "05"), 0) }.isFailure)
            assertEquals(listOf(ChapterReading("book", "02", 5)), dao.chapters())
            assertEquals(LastPlayed("book", "02"), dao.lastPlayed())
        } finally {
            db.close()
        }
    }
}
