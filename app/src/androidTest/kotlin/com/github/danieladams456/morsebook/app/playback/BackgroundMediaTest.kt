package com.github.danieladams456.morsebook.app.playback

import android.app.Notification
import android.app.NotificationManager
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState as SystemState
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.room.Room
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.danieladams456.morsebook.app.MainActivity
import com.github.danieladams456.morsebook.app.data.ReadingDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundMediaTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun shell(command: String) =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun mediaButton(label: String) {
        ui.waitUntil(10000) {
            automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)?.any {
                it.packageName == "com.android.systemui" && it.contentDescription == label &&
                    it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } == true
        }
    }

    private fun hasTrack(dump: String): Boolean = dump.lineSequence().any {
        "AudioPlaybackConfiguration piid:" in it && "type:android.media.AudioTrack" in it &&
            "u/pid:${Process.myUid()}/${Process.myPid()} " in it
    }

    private fun hasFocus(dump: String): Boolean =
        dump.substringAfter("Audio Focus stack entries").substringBefore("No external focus policy")
            .contains("AndroidAudio")

    @Test fun lockedHeadAdvancesAndMediaCardAndFocusControlTheAudibleWord() {
        val app = ui.activity.application as MorseApplication
        val playback = app.playback
        val manager = app.getSystemService(AudioManager::class.java)
        val other = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setOnAudioFocusChangeListener {}.build()
        val key = ChapterKey("the-call-of-the-wild", "03")
        val title = "Chapter III. The Dominant Primordial Beast"
        val notices = app.getSystemService(NotificationManager::class.java)
        try {
            ui.waitUntil(10000) { playback.state.value.historyLoaded }
            while (ui.onAllNodesWithText("Back").fetchSemanticsNodes().isNotEmpty()) {
                ui.onNodeWithText("Back").performClick()
            }
            ui.onNodeWithText("The Call of the Wild").performClick()
            ui.onNodeWithText(title).performClick()
            ui.onNodeWithText("Play").performClick()
            ui.waitUntil(10000) { playback.state.value.phase == Phase.Playing }
            val initial = playback.state.value.bookmarks.getValue(key)
            shell("input keyevent KEYCODE_HOME")
            shell("input keyevent KEYCODE_SLEEP")
            ui.waitUntil(5000) { "mWakefulness=Asleep" in shell("dumpsys power") }
            ui.waitUntil(15000) { playback.state.value.bookmarks.getValue(key) > initial }
            val first = playback.state.value.bookmarks.getValue(key)
            ui.waitUntil(15000) { playback.state.value.bookmarks.getValue(key) > first }
            assertTrue("mWakefulness=Asleep" in shell("dumpsys power"))
            val second = playback.state.value.bookmarks.getValue(key)
            val running = shell("dumpsys audio")
            assertTrue(hasTrack(running))
            assertTrue(hasFocus(running))
            val token = notices.activeNotifications.single { it.id == PlaybackMedia.ID }.notification.extras
                .getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)!!
            val card = MediaController(app, token)
            assertEquals(title, checkNotNull(card.metadata).description.title)
            assertEquals(0L, checkNotNull(card.playbackState).actions and SystemState.ACTION_SEEK_TO)

            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            shell("cmd statusbar expand-notifications")
            mediaButton("Pause")
            ui.waitUntil(5000) { playback.state.value.phase == Phase.Paused }
            val saved = playback.state.value.bookmarks.getValue(key)
            assertEquals(playback.state.value.audibleWord, saved)
            val idle = shell("dumpsys audio")
            assertFalse(hasTrack(idle))
            assertFalse(hasFocus(idle))
            shell("cmd statusbar collapse")
            shell("am start -W -n ${app.packageName}/${MainActivity::class.java.name}")
            ui.onNodeWithText("Play").assertIsDisplayed()
            ui.onNodeWithText(title).assertIsDisplayed()
            shell("cmd statusbar expand-notifications")
            mediaButton("Play")
            ui.waitUntil(10000) { playback.state.value.phase == Phase.Playing }
            assertEquals(SystemState.STATE_PLAYING, checkNotNull(card.playbackState).state)
            assertEquals(saved, playback.state.value.bookmarks.getValue(key))
            ui.waitUntil(10000) { playback.state.value.audibleWord!! > saved }
            shell("cmd statusbar collapse")
            ui.onNodeWithText("Pause").assertIsDisplayed()

            val beforeLoss = playback.state.value.audibleWord!!
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(other))
            ui.waitUntil(5000) { playback.state.value.phase == Phase.Paused }
            val interrupted = playback.state.value
            assertEquals(interrupted.audibleWord, interrupted.bookmarks[key])
            assertTrue(interrupted.audibleWord!! >= beforeLoss)
            ui.onNodeWithText("Play").assertIsDisplayed()
            manager.abandonAudioFocusRequest(other)
            SystemClock.sleep(500)
            val afterLoss = shell("dumpsys audio")
            assertFalse(hasTrack(afterLoss))
            assertFalse(hasFocus(afterLoss))
            assertEquals(interrupted, playback.state.value)
            val database = Room.databaseBuilder(app, ReadingDatabase::class.java, "reading.db").build()
            try {
                val durable = database.reading().chapters().single { it.book == key.book && it.chapter == key.chapter }
                assertEquals(interrupted.audibleWord, durable.offset)
                android.util.Log.i("MorseMediaTest", "locked offsets=$first,$second shade pause/play=$saved focus loss=${durable.offset}")
            } finally {
                database.close()
            }
        } finally {
            manager.abandonAudioFocusRequest(other)
            playback.pause()
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            shell("cmd statusbar collapse")
        }
    }
}
