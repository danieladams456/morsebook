package com.github.danieladams456.morsebook.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.danieladams456.morsebook.app.playback.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SelectionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun playingTwoThenOpeningFiveKeepsHistoryAndRestoresFiveOnRecreation() {
        val runtime = (ui.activity.application as MorseApplication).playback
        val book = "The Call of the Wild"
        val two = "Chapter II. The Law of Club and Fang"
        val five = "Chapter V. The Toil of Trace and Trail"
        val second = ChapterKey("the-call-of-the-wild", "02")
        fun tap(title: String) = ui.onNodeWithText(title).performClick()
        fun marked(title: String) = ui.onNode(hasText(title) and hasContentDescription("Last played"))
        ui.waitUntil(10000) { ui.onAllNodesWithText(book).fetchSemanticsNodes().isNotEmpty() }
        tap(book)
        tap(two)
        tap("Play")
        ui.waitUntil(10000) { runtime.state.value.phase == Phase.Playing }
        tap("Back")
        marked(two).assertExists()
        tap("Back")
        marked(book).assertExists()
        tap(book)
        tap(two)
        ui.onNodeWithText("Pause").assertIsDisplayed()
        assertEquals(Phase.Playing, runtime.state.value.phase)
        tap("Back")
        tap(five)
        ui.waitUntil(10000) { runtime.state.value.phase == Phase.Paused }
        val paused = runtime.state.value
        assertEquals(second, paused.lastPlayed)
        assertEquals("05", paused.selected?.chapter)
        assertFalse(paused.bookmarks.containsKey(paused.selected))
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText(five).assertIsDisplayed()
        ui.onNodeWithText("Play").assertIsDisplayed()
        assertSame(paused, runtime.state.value)
        tap("Back")
        marked(two).assertExists()
        ui.onNode(hasText(five) and hasContentDescription("Last played")).assertDoesNotExist()
        tap("Back")
        marked(book).assertExists()
    }
}
