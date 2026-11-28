package com.github.danieladams456.morsebook.app.playback

import com.github.danieladams456.morsebook.*

data class ChapterKey(val book: String, val chapter: String)
enum class Phase { Paused, Starting, Playing }
data class PlaybackState(
    val selected: ChapterKey? = null,
    val phase: Phase = Phase.Paused,
    val lastPlayed: ChapterKey? = null,
    val bookmarks: Map<ChapterKey, Int> = emptyMap(),
    val audibleWord: Int? = null
)

fun PlaybackState.progress(chapter: ChapterKey, length: Int): Float? {
    val saved = bookmarks[chapter] ?: return null
    val offset = if (selected == chapter && phase == Phase.Playing) audibleWord ?: saved else saved
    return offset.toFloat() / length
}

interface ReadingWriter {
    fun started(chapter: ChapterKey, offset: Int)
    fun checkpoint(chapter: ChapterKey, offset: Int)
}

interface AudioDevice {
    fun acquireFocus(): Boolean
    fun abandonFocus()
    fun open(): PcmOutput
}

interface PcmOutput : AutoCloseable {
    fun start()
    fun write(samples: ShortArray, offset: Int, length: Int): Int
    fun playedFrames(): Long
    fun pause()
    fun flush()
}

/** All commands, including head observations, run on the same playback worker. */
class PlaybackController(
    private val text: (ChapterKey) -> String,
    private val device: AudioDevice,
    private val reading: ReadingWriter? = null,
    private val changed: (PlaybackState) -> Unit = {}
) {
    var state = PlaybackState()
        private set(value) { field = value; changed(value) }
    var settings = SynthesisOptions(DEFAULT_WPM, DEFAULT_TONE_HZ)
    private var generation = 0L
    private var output: PcmOutput? = null
    private var queue: RenderQueue? = null

    fun restore(history: PlaybackState) {
        check(output == null)
        state = history
    }

    fun select(chapter: ChapterKey) {
        if (chapter == state.selected) return
        pause()
        state = state.copy(selected = chapter, audibleWord = null)
    }

    fun play(): Long {
        val chapter = state.selected ?: return generation
        if (output != null || !device.acquireFocus()) return generation
        generation++
        try {
            queue = RenderQueue(text(chapter), state.bookmarks[chapter] ?: 0, settings)
            output = device.open()
            output!!.start()
            state = state.copy(phase = Phase.Starting)
        } catch (e: RuntimeException) {
            release()
        }
        return generation
    }

    fun tick(session: Long) {
        if (session != generation) return
        val track = output ?: return
        val rendered = checkNotNull(queue)
        try {
            val head = track.playedFrames()
            if (!observe(head)) {
                release()
                return
            }
            if (rendered.finished(head)) {
                pause()
            } else {
                rendered.feed(track, head)
            }
        } catch (e: RuntimeException) {
            pause()
        }
    }

    /** A rejected start transaction leaves the previous reading history intact. */
    private fun observe(head: Long): Boolean {
        val chapter = state.selected ?: return true
        val word = queue?.wordAt(head) ?: return true
        if (state.phase == Phase.Starting) {
            val first = checkNotNull(queue?.firstWord)
            try {
                reading?.started(chapter, first)
            } catch (e: RuntimeException) {
                return false
            }
            state = state.copy(phase = Phase.Playing, lastPlayed = chapter,
                audibleWord = word, bookmarks = state.bookmarks + (chapter to first))
        } else if (word != state.audibleWord) {
            state = state.copy(audibleWord = word)
        }
        return true
    }

    fun pause() {
        val track = output ?: return
        try {
            track.pause()
            observe(track.playedFrames())
        } finally {
            try {
                checkpoint()
            } finally {
                release()
            }
        }
    }

    private fun checkpoint() {
        val chapter = state.selected
        val word = state.audibleWord
        if (state.phase == Phase.Playing && chapter != null && word != null) {
            reading?.checkpoint(chapter, word)
            state = state.copy(bookmarks = state.bookmarks + (chapter to word))
        }
    }

    private fun release() {
        generation++
        val track = output
        output = null
        queue = null
        try {
            track?.flush()
        } finally {
            try { track?.close() } finally {
                device.abandonFocus()
                state = state.copy(phase = Phase.Paused)
            }
        }
    }
}

private class RenderQueue(text: String, offset: Int, private val settings: SynthesisOptions) {
    private val stream = DefaultMorsePcmStream(StreamOptions(text, DEFAULT_SAMPLE_RATE_HZ, offset))
    private data class Block(val samples: ShortArray, val length: Int, var position: Int = 0)
    private data class Start(val frame: Long, val word: Int)
    private val blocks = ArrayDeque<Block>()
    private val starts = ArrayDeque<Start>()
    private var rendered = 0L
    private var eof = false
    private var audible: Int? = null
    var firstWord: Int? = null
        private set

    fun feed(output: PcmOutput, head: Long) {
        // Limit synthesis per tick so that commands can interrupt the initial buffer fill.
        repeat(4) {
            if (!eof && rendered - head < 12L * DEFAULT_SAMPLE_RATE_HZ) {
                val samples = ShortArray(4096)
                val read = stream.read(settings, samples)
                eof = read.framesWritten == 0
                if (!eof) {
                    if (firstWord == null) firstWord = read.wordStarts.firstOrNull()?.sourceOffset
                    for (word in read.wordStarts) starts += Start(rendered + word.frameOffset, word.sourceOffset)
                    rendered += read.framesWritten
                    blocks += Block(samples, read.framesWritten)
                }
            }
        }
        while (blocks.isNotEmpty()) {
            val block = blocks.first()
            val remaining = block.length - block.position
            val written = output.write(block.samples, block.position, remaining)
            check(written in 0..remaining) { "Audio output rejected PCM ($written)" }
            block.position += written
            if (written < remaining) return
            blocks.removeFirst()
        }
    }

    fun wordAt(head: Long): Int? {
        while (starts.isNotEmpty() && starts.first().frame < head) audible = starts.removeFirst().word
        return audible
    }

    fun finished(head: Long) = eof && head >= rendered
}
