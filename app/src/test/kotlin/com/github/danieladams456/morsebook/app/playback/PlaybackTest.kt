package com.github.danieladams456.morsebook.app.playback

import com.github.danieladams456.morsebook.*
import kotlin.test.*

class PlaybackTest {
    private val text = "E 😀 E T"
    private val settings = SynthesisOptions(20, 600)
    private val key = ChapterKey("book", "one")
    private var grantsFocus = true
    private var brokenOutput = false
    private var rejectStart = false
    private var abandoned = 0
    private val track = FakeOutput()
    private val writes = mutableListOf<Triple<String, ChapterKey, Int>>()
    private val reading = object : ReadingWriter {
        override fun started(chapter: ChapterKey, offset: Int) {
            check(!rejectStart)
            writes += Triple("start", chapter, offset)
        }
        override fun checkpoint(chapter: ChapterKey, offset: Int) { writes += Triple("pause", chapter, offset) }
    }
    private val player = PlaybackController(
        { text }, object : AudioDevice {
            override fun acquireFocus() = grantsFocus
            override fun abandonFocus() { abandoned++ }
            override fun open(): PcmOutput {
                check(!brokenOutput)
                return track.apply { head = 0; accepted.clear() }
            }
        }, reading = reading
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
        val previous = ChapterKey("book", "previous")
        val history = mapOf(previous to 5, key to 7)
        player.restore(PlaybackState(lastPlayed = previous, bookmarks = history))
        player.select(key)
        val canceled = player.play()
        player.tick(canceled)
        player.pause()
        track.head = 42
        player.tick(canceled)
        assertEquals(previous, player.state.lastPlayed)
        assertEquals(history, player.state.bookmarks)
        grantsFocus = false
        player.play()
        assertEquals(Phase.Paused, player.state.phase)
        grantsFocus = true
        brokenOutput = true
        player.play()
        assertEquals(Phase.Paused, player.state.phase)
        assertEquals(previous, player.state.lastPlayed)
        assertEquals(history, player.state.bookmarks)
        assertTrue(writes.isEmpty())
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

    @Test fun rejectedStartTransactionReleasesOutputAndLeavesHistoryIntact() {
        val previous = ChapterKey("book", "previous")
        val history = PlaybackState(selected = key, lastPlayed = previous, bookmarks = mapOf(previous to 5))
        player.restore(history)
        rejectStart = true
        val session = player.play()
        player.tick(session)
        track.head = 1
        player.tick(session)
        assertEquals(history, player.state)
        assertTrue(writes.isEmpty())
        assertEquals("close", track.events.last())
        assertEquals(1, abandoned)
    }

    @Test fun browsingAndSelectionPreserveTheLastAudibleChapter() {
        val two = ChapterKey("book", "02")
        val five = ChapterKey("book", "05")
        player.select(two)
        val stream = player.play()
        player.tick(stream)
        assertTrue(writes.isEmpty())
        track.head = 1
        player.tick(stream)
        assertEquals(listOf(Triple("start", two, 0)), writes)
        player.select(two)
        assertEquals(Phase.Playing, player.state.phase)
        track.head = 25000
        player.tick(stream)
        assertEquals(0.625f, player.state.progress(two, text.length))
        player.select(five)
        assertEquals(listOf(Triple("start", two, 0), Triple("pause", two, 5)), writes)
        assertEquals(0.625f, player.state.progress(two, text.length))
        assertNull(player.state.progress(five, text.length))
        assertEquals(two, player.state.lastPlayed)
        val beforeLateCallback = player.state
        player.tick(stream)
        assertEquals(beforeLateCallback, player.state)
        assertEquals(2, writes.size)
        assertEquals(five, player.state.selected)
        assertEquals(Phase.Paused, player.state.phase)
    }

    private class FakeOutput : PcmOutput {
        var head = 0
        val events = mutableListOf<String>()
        val accepted = mutableListOf<Short>()
        override fun playedFrames(): Long { events += "head"; return head.toLong() }
        override fun write(samples: ShortArray, offset: Int, length: Int): Int {
            val count = minOf(length, 997)
            accepted.addAll(samples.slice(offset until offset + count))
            return count
        }
        override fun start() {}
        override fun pause() { events += "pause" }
        override fun flush() { events += "flush" }
        override fun close() { events += "close" }
    }
}
