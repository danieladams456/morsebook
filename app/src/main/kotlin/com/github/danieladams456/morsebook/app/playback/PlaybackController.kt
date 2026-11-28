package com.github.danieladams456.morsebook.app.playback

import com.github.danieladams456.morsebook.*

data class ChapterKey(val book: String, val chapter: String)
enum class StartupError { FocusDenied, PlaybackFailed }
enum class Phase { Paused, Starting, Playing }
data class PlaybackState(
    val selected: ChapterKey? = null,
    val phase: Phase = Phase.Paused,
    val lastPlayed: ChapterKey? = null,
    val bookmarks: Map<ChapterKey, Int> = emptyMap(),
    val audibleWord: Int? = null,
    val startupError: StartupError? = null
)

interface AudioDevice {
    fun acquireFocus(): Boolean
    fun abandonFocus()
    fun open(): PcmOutput
}

interface PcmOutput : AutoCloseable {
    val bufferSizeFrames: Int
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
    private val changed: (PlaybackState) -> Unit = {}
) {
    var state = PlaybackState()
        private set(value) { field = value; changed(value) }
    var settings = SynthesisOptions(DEFAULT_WPM, DEFAULT_TONE_HZ)
    private var generation = 0L
    private var output: PcmOutput? = null
    private var queue: RenderQueue? = null

    fun select(chapter: ChapterKey) {
        if (chapter == state.selected) return
        pause()
        state = state.copy(selected = chapter, audibleWord = null, startupError = null)
    }

    fun play(): Long {
        val chapter = state.selected ?: return generation
        if (output != null) return generation
        state = state.copy(startupError = null)
        if (!device.acquireFocus()) {
            state = state.copy(startupError = StartupError.FocusDenied)
            return generation
        }
        generation++
        try {
            queue = RenderQueue(text(chapter), state.bookmarks[chapter] ?: 0, settings)
            output = device.open()
            output!!.start()
            state = state.copy(phase = Phase.Starting)
        } catch (e: java.io.IOException) {
            release(StartupError.PlaybackFailed)
        } catch (e: RuntimeException) {
            release(StartupError.PlaybackFailed)
        }
        return generation
    }

    fun tick(session: Long) {
        if (session != generation) return
        val track = output ?: return
        val rendered = checkNotNull(queue)
        try {
            val head = track.playedFrames()
            observe(head)
            if (rendered.finished(head)) {
                pause()
            } else {
                rendered.feed(track, head)
            }
        } catch (e: RuntimeException) {
            pause()
        }
    }

    private fun observe(head: Long) {
        val chapter = state.selected ?: return
        val word = queue?.wordAt(head) ?: return
        if (state.phase == Phase.Starting) {
            state = state.copy(phase = Phase.Playing, lastPlayed = chapter,
                audibleWord = word, bookmarks = state.bookmarks + (chapter to word))
        } else if (word != state.audibleWord) {
            state = state.copy(audibleWord = word)
        }
    }

    fun pause() {
        val track = output ?: return
        try {
            track.pause()
            observe(track.playedFrames())
        } finally {
            val chapter = state.selected
            val word = state.audibleWord
            if (state.phase == Phase.Playing && chapter != null && word != null) {
                state = state.copy(bookmarks = state.bookmarks + (chapter to word))
            }
            release()
        }
    }

    private fun release(error: StartupError? = null) {
        generation++
        val track = output
        output = null
        queue = null
        try {
            track?.flush()
        } finally {
            try { track?.close() } finally {
                device.abandonFocus()
                state = state.copy(phase = Phase.Paused, startupError = error)
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
    private var paddingWritten = 0
    private var headAdvanced = false
    private var silence: ShortArray? = null
    private var eof = false
    private var audible: Int? = null

    fun feed(output: PcmOutput, head: Long) {
        if (head > 0) headAdvanced = true
        // Limit synthesis per tick so that commands can interrupt the initial buffer fill.
        repeat(4) {
            if (!eof && rendered - head < 12L * DEFAULT_SAMPLE_RATE_HZ) {
                val samples = ShortArray(4096)
                val read = stream.read(settings, samples)
                eof = read.framesWritten == 0
                if (!eof) {
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
        // Only prime a fresh track whose entire real stream fits below its start threshold.
        if (eof && !headAdvanced && rendered in 1L until output.bufferSizeFrames.toLong()) {
            val remaining = output.bufferSizeFrames - rendered.toInt() - paddingWritten
            if (remaining > 0) {
                val zeros = silence ?: ShortArray(minOf(4096, remaining)).also { silence = it }
                val length = minOf(remaining, zeros.size)
                val written = output.write(zeros, 0, length)
                check(written in 0..length) { "Audio output rejected silence ($written)" }
                paddingWritten += written
            }
        }
    }

    fun wordAt(head: Long): Int? {
        while (starts.isNotEmpty() && starts.first().frame < head) audible = starts.removeFirst().word
        return audible
    }

    fun finished(head: Long) = eof && head >= rendered
}
