package com.github.danieladams456.morsebook.app.playback

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.danieladams456.morsebook.*
import com.github.danieladams456.morsebook.app.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AudioFixtureTest {
    @Test fun acceptedFixtureReachesAnAdvancingAndroidTrack() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val heads = mutableListOf<Long>()
            val capture = File(context.cacheDir, "ae-t.pcm")
            capture.outputStream().buffered().use { pcm ->
                val audio = AndroidAudio(context, {}) { samples, offset, count ->
                    for (i in offset until offset + count) {
                        val sample = samples[i].toInt()
                        pcm.write(sample and 255)
                        pcm.write(sample shr 8 and 255)
                    }
                }
                val device = object : AudioDevice by audio {
                    override fun open(): PcmOutput {
                        val track = audio.open()
                        return object : PcmOutput by track {
                            override fun playedFrames() = track.playedFrames().also { heads += it }
                        }
                    }
                }
                val player = PlaybackController({ "AE T" }, device)
                player.changeSettings(SynthesisOptions(20, 600))
                player.select(ChapterKey("fixture", "ae-t"))
                val session = player.play()
                try {
                    val deadline = SystemClock.elapsedRealtime() + 10000
                    while (player.state.phase != Phase.Paused && SystemClock.elapsedRealtime() < deadline) {
                        player.tick(session)
                        SystemClock.sleep(10)
                    }
                    assertEquals(Phase.Paused, player.state.phase)
                    assertTrue("real playback head must advance twice", heads.filter { it > 0 }.distinct().size >= 2)
                    assertEquals(54720L, heads.max())
                } finally { player.pause() }
            }
            assertEquals(54720L * 2, capture.length())
        }
    }

    @Test fun consecutiveMidWordEditsReplayTheWholeWordOnTheReplacementTrack() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val chapter = ChapterKey("fixture", "edited")
            val text = "T AE T"
            var opened = 0
            val capture = java.io.ByteArrayOutputStream()
            val audio = AndroidAudio(context, {}) { samples, offset, count ->
                if (opened == 2) {
                    for (i in offset until offset + count) {
                        capture.write(samples[i].toInt())
                        capture.write(samples[i].toInt() shr 8)
                    }
                }
            }
            val device = object : AudioDevice by audio {
                override fun open(): PcmOutput = audio.open().also { opened++ }
            }
            val player = PlaybackController({ text }, device)
            player.select(chapter)
            val old = player.play()
            try {
                val deadline = SystemClock.elapsedRealtime() + 10000
                while (player.state.audibleWord != 2 && SystemClock.elapsedRealtime() < deadline) {
                    player.tick(old)
                    SystemClock.sleep(10)
                }
                assertEquals(2, player.state.audibleWord)
                player.changeSettings(SynthesisOptions(25, DEFAULT_TONE_HZ))
                val finalSettings = SynthesisOptions(25, 700)
                val rebuilt = player.changeSettings(finalSettings)
                assertEquals(2, player.state.bookmarks[chapter])
                while (player.state.phase != Phase.Paused && SystemClock.elapsedRealtime() < deadline) {
                    player.tick(rebuilt)
                    SystemClock.sleep(10)
                }
                assertEquals(Phase.Paused, player.state.phase)
                assertEquals(2, opened)
                val expected = ShortArray(100000)
                val read = DefaultMorsePcmStream(StreamOptions(text, DEFAULT_SAMPLE_RATE_HZ, 2))
                    .read(finalSettings, expected)
                val bytes = java.nio.ByteBuffer.allocate(read.framesWritten * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                bytes.asShortBuffer().put(expected, 0, read.framesWritten)
                assertArrayEquals(bytes.array(), capture.toByteArray())
                File(context.cacheDir, "ae-t-edited.pcm").writeBytes(capture.toByteArray())
            } finally { player.pause() }
        }
    }
}
