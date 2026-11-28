package com.github.danieladams456.morsebook.app.playback

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.danieladams456.morsebook.SynthesisOptions
import com.github.danieladams456.morsebook.app.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AudioFixtureTest {
    @Test fun finalWordResumesOnAFreshAndroidTrack() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val key = ChapterKey("fixture", "ae-t")
            val player = PlaybackController({ "AE T" }, AndroidAudio(context, {}))
            player.settings = SynthesisOptions(20, 600)
            player.select(key)
            repeat(2) {
                val session = player.play()
                try {
                    assertEquals(Phase.Starting, player.state.phase)
                    val deadline = SystemClock.elapsedRealtime() + 10000
                    while (player.state.phase != Phase.Paused && SystemClock.elapsedRealtime() < deadline) {
                        player.tick(session)
                        SystemClock.sleep(10)
                    }
                    assertEquals(Phase.Paused, player.state.phase)
                    assertEquals(3, player.state.bookmarks[key])
                } finally { player.pause() }
            }
        }
    }

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
                player.settings = SynthesisOptions(20, 600)
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
}
