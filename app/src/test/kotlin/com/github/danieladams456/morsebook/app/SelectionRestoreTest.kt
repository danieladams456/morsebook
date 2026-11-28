package com.github.danieladams456.morsebook.app

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.danieladams456.morsebook.app.data.*
import com.github.danieladams456.morsebook.app.playback.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SelectionRestoreTest {
    @get:Rule val ui = createComposeRule()

    private fun restoredControls(history: PlaybackState) = object : PlaybackControls {
        override val state = MutableStateFlow(history)
        override fun select(chapter: ChapterKey) = error("unexpected selection")
        override fun adjustSettings(wpmDelta: Int, toneDelta: Int) = error("unexpected edit")
        override fun pause() = error("unexpected pause")
        override fun requestPlay(chapter: ChapterKey) = error("unexpected play")
    }

    @Test fun freshRouteOpensLastPlayedChapterPausedWithBothIconsAndReadStatuses() {
        val last = ChapterKey("a", "2")
        val read = ChapterKey("a", "1")
        val book = Book("a", "Book", listOf(Chapter("1", "One", 10), Chapter("2", "Two", 10)))
        val history = restoreReading(listOf(book),
            listOf(ChapterReading("a", "1", null), ChapterReading("a", "2", 300)),
            LastPlayed("a", "2")) { "A  😀 E   " }
        assertEquals(last, history.selected)
        assertEquals(6, history.bookmarks[last])
        assertEquals(setOf(read), history.readChapters)
        val controls = restoredControls(history)
        ui.setContent { LibraryBrowser(listOf(book), controls) }
        ui.onNodeWithText("Two").assertIsDisplayed()
        ui.onNodeWithText("Play").assertIsDisplayed()
        ui.onNodeWithText("Back").performClick()
        ui.onNode(hasText("One") and hasContentDescription("Read")).assertExists()
        ui.onNode(hasText("Two") and hasContentDescription("Last played")).assertExists()
        ui.onNodeWithText("Back").performClick()
        ui.onNode(hasText("Book") and hasContentDescription("Last played")).assertExists()
    }

    @Test fun removedLastChapterDoesNotRestoreSelectionOrOrphanedIcon() {
        val book = Book("a", "Book", listOf(Chapter("1", "One", 10)))
        val history = restoreReading(listOf(book), emptyList(), LastPlayed("a", "removed")) { error("unexpected read") }
        assertEquals(null, history.lastPlayed)
        assertEquals(null, history.selected)
        ui.setContent { LibraryBrowser(listOf(book), restoredControls(history)) }
        ui.onNodeWithText("Library").assertIsDisplayed()
        ui.onNodeWithContentDescription("Last played").assertDoesNotExist()
    }

    @Test fun restoringOpenChapterDoesNotSendPlaybackCommands() {
        val last = ChapterKey("a", "2")
        val commands = mutableListOf<String>()
        val controls = object : PlaybackControls {
            override val state = MutableStateFlow(PlaybackState(lastPlayed = last,
                bookmarks = mapOf(last to 7)))
            override fun select(chapter: ChapterKey) { commands += "select ${chapter.chapter}" }
            override fun requestPlay(chapter: ChapterKey) { commands += "play" }
            override fun adjustSettings(wpmDelta: Int, toneDelta: Int) { commands += "adjust $wpmDelta $toneDelta" }
            override fun pause() { commands += "pause" }
        }
        val book = Book("a", "Book", listOf(Chapter("2", "Two", 20), Chapter("5", "Five", 20)))
        val restoration = StateRestorationTester(ui)
        restoration.setContent { LibraryBrowser(listOf(book), controls) }
        ui.onNodeWithText("Book").performClick()
        val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
        ui.onNodeWithText("Two").assert(hasAnyDescendant(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.35f, 0f..1f))))
        ui.onNodeWithText("Five").assert(hasAnyDescendant(progress).not())
        ui.onNodeWithText("Five").performClick()
        assertEquals(listOf("select 5"), commands)
        restoration.emulateSavedInstanceStateRestore()
        ui.onNodeWithText("Five").assertIsDisplayed()
        ui.onNodeWithText("Play").assertIsDisplayed()
        assertEquals(listOf("select 5"), commands)
        ui.onNodeWithText("CW speed 20 WPM").assertIsDisplayed()
        ui.onNodeWithText("Tone 500 Hz").assertIsDisplayed()
        ui.onNodeWithContentDescription("Increase CW speed").performClick()
        ui.onNodeWithContentDescription("Decrease Tone").performClick()
        assertEquals(listOf("select 5", "adjust 1 0", "adjust 0 -50"), commands)
    }
}
