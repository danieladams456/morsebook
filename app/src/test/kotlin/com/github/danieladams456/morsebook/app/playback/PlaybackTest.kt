package com.github.danieladams456.morsebook.app.playback

import com.github.danieladams456.morsebook.*
import kotlin.test.*

class PlaybackTest {
    private var text = "E 😀 E T"
    private val settings = SynthesisOptions(20, 600)
    private val key = ChapterKey("book", "one")
    private var failLoad = false
    private var grantsFocus = true
    private var brokenOutput = false
    private var abandoned = 0
    private val track = FakeOutput()
    private val player = PlaybackController(
        { if (failLoad) throw java.io.IOException("missing chapter") else text }, object : AudioDevice {
            override fun acquireFocus() = grantsFocus
            override fun abandonFocus() { abandoned++ }
            override fun open(): PcmOutput {
                check(!brokenOutput)
                return track.apply { head = 0; accepted.clear() }
            }
        }
    )

    @Test fun pauseAndResumeUseWordTwoWhileThreeWordsAreQueued() {
        val reference = ShortArray(100000)
        val render = DefaultMorsePcmStream(StreamOptions(text, DEFAULT_SAMPLE_RATE_HZ))
            .read(settings, reference)
        assertEquals(listOf(0, 5, 7), render.wordStarts.map { it.sourceOffset })
        player.select(key)
        player.settings = settings
        val session = player.play()
        repeat(100) { player.tick(session) }
        assertEquals(reference.take(render.framesWritten), track.accepted)
        assertEquals(Phase.Starting, player.state.phase)
        assertNull(player.state.lastPlayed)
        track.head = render.wordStarts[1].frameOffset + 7
        player.pause()
        assertEquals(key, player.state.lastPlayed)
        assertEquals(5, player.state.bookmarks[key])
        assertEquals(listOf("pause", "head", "flush", "close"), track.events.takeLast(4))
        val resumed = player.play()
        player.tick(session)
        assertTrue(track.accepted.isEmpty())
        player.tick(resumed)
        assertEquals(reference.drop(render.wordStarts[1].frameOffset).take(track.accepted.size), track.accepted)
    }

    @Test fun pauseBeforeFirstFrameAndStartupFailureLeaveHistoryUntouched() {
        player.select(key)
        val canceled = player.play()
        player.tick(canceled)
        player.pause()
        track.head = 42
        player.tick(canceled)
        assertNull(player.state.lastPlayed)
        assertTrue(player.state.bookmarks.isEmpty())
        grantsFocus = false
        player.play()
        assertEquals(Phase.Paused, player.state.phase)
        assertEquals(StartupError.FocusDenied, player.state.startupError)
        grantsFocus = true
        brokenOutput = true
        player.play()
        assertEquals(Phase.Paused, player.state.phase)
        assertNull(player.state.lastPlayed)
        assertEquals(StartupError.PlaybackFailed, player.state.startupError)
        assertEquals(2, abandoned)
    }

    @Test fun switchSamplesAnUnreportedStartAndRejectsOldCompletion() {
        player.select(key)
        val first = player.play()
        player.tick(first)
        track.head = 1
        val second = ChapterKey("book", "two")
        player.select(second)
        assertEquals(0, player.state.bookmarks[key])
        assertEquals(key, player.state.lastPlayed)
        val next = player.play()
        track.head = 100000
        player.tick(first)
        assertEquals(Phase.Starting, player.state.phase)
        assertEquals(key, player.state.lastPlayed)
        track.head = 0
        player.tick(next)
        track.head = 1
        player.tick(next)
        assertEquals(second, player.state.lastPlayed)
        assertEquals(Phase.Playing, player.state.phase)
    }

    @Test fun partialFinalBufferOfPlayingChapterContainsOnlyRealPcm() {
        text = "AE T"
        player.select(key)
        player.settings = settings
        // 54,720 frames fill four buffers and about 2% of the fifth.
        track.bufferSizeFrames = 13612
        val session = player.play()
        player.tick(session)
        track.head = 1
        repeat(100) { player.tick(session) }
        val pcm = ShortArray(54720)
        DefaultMorsePcmStream(StreamOptions(text, DEFAULT_SAMPLE_RATE_HZ)).read(settings, pcm)
        assertEquals(pcm.toList(), track.accepted)
        track.head = pcm.size
        player.tick(session)
        assertEquals(Phase.Paused, player.state.phase)
    }

    @Test fun resumeOnFinalWordPrimesTrackAndFinishesAtRealBoundary() {
        text = "AE T"
        player.settings = settings
        player.select(key)
        val first = player.play()
        repeat(100) { player.tick(first) }
        track.head = 54720
        player.tick(first)
        assertEquals(3, player.state.bookmarks[key])

        val resumed = player.play()
        repeat(9) { player.tick(resumed) }
        track.blockWrites = true
        val before = track.accepted.size
        player.tick(resumed)
        assertTrue(before in 8640 until track.bufferSizeFrames)
        assertEquals(before, track.accepted.size)
        track.blockWrites = false
        repeat(100) { player.tick(resumed) }
        assertEquals(track.bufferSizeFrames, track.accepted.size)
        assertTrue(track.accepted.drop(8640).all { it == 0.toShort() })
        track.head = 8640
        player.tick(resumed)
        assertEquals(Phase.Paused, player.state.phase)
        assertEquals(3, player.state.bookmarks[key])
    }

    @Test fun cancelDuringPrimingDoesNotBookmarkOrWriteMoreSilence() {
        text = "T"
        player.settings = settings
        player.select(key)
        val session = player.play()
        repeat(9) { player.tick(session) }
        val accepted = track.accepted.size
        assertTrue(accepted in 8640 until track.bufferSizeFrames)
        player.pause()
        player.tick(session)
        assertEquals(accepted, track.accepted.size)
        assertNull(player.state.lastPlayed)
        assertTrue(player.state.bookmarks.isEmpty())
    }

    @Test fun emptyChapterCompletesWithoutPadding() {
        text = ""
        player.select(key)
        val session = player.play()
        repeat(3) { player.tick(session) }
        assertEquals(Phase.Paused, player.state.phase)
        assertTrue(track.accepted.isEmpty())
    }

    @Test fun trackStartFailureClosesTheOpenedTrack() {
        player.select(key)
        track.failStart = true
        player.play()
        assertEquals(Phase.Paused, player.state.phase)
        assertEquals(StartupError.PlaybackFailed, player.state.startupError)
        assertEquals(listOf("flush", "close"), track.events)
        assertEquals(1, abandoned)
        player.select(ChapterKey("book", "two"))
        assertNull(player.state.startupError)
    }

    @Test fun loadFailureReleasesFocusAndRetryClearsError() {
        player.select(key)
        failLoad = true
        player.play()
        assertEquals(StartupError.PlaybackFailed, player.state.startupError)
        assertEquals(Phase.Paused, player.state.phase)
        assertEquals(1, abandoned)
        assertTrue(player.state.bookmarks.isEmpty())
        failLoad = false
        player.play()
        assertNull(player.state.startupError)
        assertEquals(Phase.Starting, player.state.phase)
    }

    private class FakeOutput : PcmOutput {
        override var bufferSizeFrames = 12000
        var blockWrites = false
        var failStart = false
        var head = 0
        val events = mutableListOf<String>()
        val accepted = mutableListOf<Short>()
        override fun playedFrames(): Long { events += "head"; return head.toLong() }
        override fun write(samples: ShortArray, offset: Int, length: Int): Int {
            if (blockWrites) return 0
            val count = minOf(length, 997)
            accepted.addAll(samples.slice(offset until offset + count))
            return count
        }
        override fun start() { check(!failStart) }
        override fun pause() { events += "pause" }
        override fun flush() { events += "flush" }
        override fun close() { events += "close" }
    }
}
