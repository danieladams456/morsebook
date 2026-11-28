package com.github.danieladams456.morsebook.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.github.danieladams456.morsebook.DEFAULT_TONE_HZ
import com.github.danieladams456.morsebook.DEFAULT_WPM
import com.github.danieladams456.morsebook.SynthesisOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.danieladams456.morsebook.app.playback.ChapterKey
import org.junit.Assert.*
import org.xmlpull.v1.XmlPullParser
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingDatabaseTest {
    @Test fun reopeningFileRestoresPartialReadAndUnreadWithLastPlayed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "reopen-${java.util.UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, ReadingDatabase::class.java, name).build()
        try {
            val writer = open()
            try {
                writer.reading().save(ChapterReading("book", "01", null))
                writer.reading().started(ChapterKey("book", "02"), 0)
                writer.reading().checkpoint(ChapterKey("book", "02"), 17)
            } finally { writer.close() }
            val reader = open()
            try {
                assertEquals(setOf(ChapterReading("book", "01", null), ChapterReading("book", "02", 17)),
                    reader.reading().chapters().toSet())
                assertEquals(LastPlayed("book", "02"), reader.reading().lastPlayed())
            } finally { reader.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun settingsUseCoreDefaultsAndReopenWithBothEdits() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.cacheDir, "${java.util.UUID.randomUUID()}.preferences_pb")
        suspend fun withStore(check: suspend (PlaybackSettings) -> Unit) {
            val lifetime = Job()
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(lifetime + Dispatchers.IO)) { file }
            try {
                check(PlaybackSettings(store))
            } finally { lifetime.cancelAndJoin() }
        }
        try {
            withStore { settings ->
                assertEquals(SynthesisOptions(DEFAULT_WPM, DEFAULT_TONE_HZ), settings.load())
                settings.save(SynthesisOptions(27, DEFAULT_TONE_HZ))
            }
            withStore { settings ->
                assertEquals(SynthesisOptions(27, DEFAULT_TONE_HZ), settings.load())
                settings.save(SynthesisOptions(27, 750))
            }
            withStore { assertEquals(SynthesisOptions(27, 750), it.load()) }
        } finally { file.delete() }
    }

    @Test fun packagedBackupRulesExcludeHistoryAndSettingsForLegacyBackupCloudAndTransfer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val android = "http://schemas.android.com/apk/res/android"
        val (legacy, modern) = context.assets.openXmlResourceParser("AndroidManifest.xml").use { manifest ->
            while (manifest.next() != XmlPullParser.END_DOCUMENT) {
                if (manifest.eventType == XmlPullParser.START_TAG && manifest.name == "application") break
            }
            manifest.getAttributeResourceValue(android, "fullBackupContent", 0) to
                manifest.getAttributeResourceValue(android, "dataExtractionRules", 0)
        }
        fun exclusions(resource: Int, domain: String, location: String): Set<String> {
            val result = mutableSetOf<String>()
            val path = mutableListOf<String>()
            context.resources.getXml(resource).use { rules ->
                while (rules.next() != XmlPullParser.END_DOCUMENT) {
                    when (rules.eventType) {
                        XmlPullParser.START_TAG -> {
                            path += rules.name
                            if (rules.name == "exclude" && rules.getAttributeValue(null, "domain") == domain
                                && rules.getAttributeValue(null, "path") == location) {
                                result += path.joinToString("/")
                            }
                        }
                        XmlPullParser.END_TAG -> path.removeAt(path.lastIndex)
                    }
                }
            }
            return result
        }
        for ((domain, location) in listOf("database" to ".", "file" to "datastore")) {
            assertEquals(setOf("full-backup-content/exclude"), exclusions(legacy, domain, location))
            assertEquals(setOf("data-extraction-rules/cloud-backup/exclude",
                "data-extraction-rules/device-transfer/exclude"), exclusions(modern, domain, location))
        }
    }

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
