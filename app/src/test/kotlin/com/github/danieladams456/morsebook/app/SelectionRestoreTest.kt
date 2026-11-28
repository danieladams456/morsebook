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

    @Test fun restoringOpenChapterDoesNotSendPlaybackCommands() {
        val selected = ChapterKey("a", "5")
        val last = ChapterKey("a", "2")
        val commands = mutableListOf<String>()
        val controls = object : PlaybackControls {
            override val state = MutableStateFlow(PlaybackState(selected = selected, lastPlayed = last,
                bookmarks = mapOf(last to 7)))
            override fun select(chapter: ChapterKey) { commands += "select ${chapter.chapter}" }
            override fun requestPlay(chapter: ChapterKey) { commands += "play" }
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
    }
}
