package com.github.danieladams456.morsebook.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.danieladams456.morsebook.app.data.BundledCatalog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowseTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun browseEveryChapterAndReturnThroughBookAndLibrary() {
        val books = BundledCatalog(ui.activity.assets::open).loadBooks()
        ui.waitUntil(10000) { ui.onAllNodesWithText("Library").fetchSemanticsNodes().isNotEmpty() }
        for (book in books) {
            ui.onNodeWithText("Library").assertIsDisplayed()
            ui.onNodeWithText(book.title).performClick()
            for (chapter in book.chapters) {
                ui.onNodeWithText(chapter.title).performScrollTo().assertIsDisplayed()
            }
            val last = book.chapters.last()
            ui.onNodeWithText(last.title).performClick()
            ui.onNodeWithText("Now Playing").assertIsDisplayed()
            ui.onNodeWithText(last.title).assertIsDisplayed()
            ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }
            ui.onNodeWithText(book.title).assertIsDisplayed()
            ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }
        }
        ui.onNodeWithText("Library").assertIsDisplayed()
    }
}
