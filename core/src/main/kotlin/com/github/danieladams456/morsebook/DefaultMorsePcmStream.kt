package com.github.danieladams456.morsebook

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val codes = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toList().zip(
    ".- -... -.-. -.. . ..-. --. .... .. .--- -.- .-.. -- -. --- .--. --.- .-. ... - ..- ...- .-- -..- -.-- --.. ----- .---- ..--- ...-- ....- ..... -.... --... ---.. ----.".split(' ')
).toMap()

class DefaultMorsePcmStream(options: StreamOptions) : MorsePcmStream {
    private data class Word(val sourceOffset: Int, val messageOffset: Int)

    private val sampleRateHz = options.sampleRateHz
    private val words = mutableListOf<Word>()
    private val message = buildString {
        var separated = false
        for ((sourceOffset, c) in options.text.withIndex()) {
            if (c.isWhitespace()) separated = isNotEmpty()
            val key = if (c in 'a'..'z') c.uppercaseChar() else c
            if (key in codes) {
                if (separated) append(' ')
                if (separated || isEmpty()) words.add(Word(sourceOffset, length))
                append(key)
                separated = false
            }
        }
    }

    private var unitSamples = 0.0
    private var rampSamples = 0
    private var phaseStep = 0.0
    private var nextWord = words.indexOfLast { it.sourceOffset <= options.textStartOffset }.coerceAtLeast(0)
    private var next = words.getOrNull(nextWord)?.messageOffset ?: 0
    private var pattern = ""
    private var mark = 0
    private var keyed = false
    private var position = 0
    private var duration = 0
    private var phase = 0.0
    private var roundingError = 0.0

    private fun startSegment(options: SynthesisOptions, starts: MutableList<WordStart>, frameOffset: Int): Boolean {
        val units: Int
        if (keyed && mark < pattern.length) {
            keyed = false
            units = 1
        } else if (keyed && next < message.length) {
            keyed = false
            units = if (message[next] == ' ') {
                next++
                7
            } else 3
        } else {
            if (mark == pattern.length) {
                if (next == message.length) return false
                val word = words.getOrNull(nextWord)
                if (word != null && word.messageOffset == next) {
                    starts.add(WordStart(word.sourceOffset, frameOffset))
                    nextWord++
                }
                unitSamples = sampleRateHz * 1.2 / options.wpm
                rampSamples = minOf(sampleRateHz * 0.005, unitSamples / 2).roundToInt()
                phaseStep = 2 * PI * options.toneHz / sampleRateHz
                pattern = codes.getValue(message[next++])
                mark = 0
            }
            keyed = true
            units = if (pattern[mark++] == '.') 1 else 3
        }
        val exact = units * unitSamples + roundingError
        duration = exact.roundToInt()
        roundingError = exact - duration
        position = 0
        return true
    }

    @Synchronized
    override fun read(options: SynthesisOptions, buffer: ShortArray, bufferWriteOffset: Int, length: Int): ReadResult {
        require(bufferWriteOffset in 0..buffer.size && length in 0..buffer.size - bufferWriteOffset)
        val starts = mutableListOf<WordStart>()
        var written = 0
        while (written < length) {
            if (position == duration && !startSegment(options, starts, written)) break
            val count = minOf(length - written, duration - position)
            repeat(count) {
                val value = if (keyed) {
                    val edge = minOf(position, duration - 1 - position)
                    val gain = if (edge >= rampSamples) 1.0
                        else (1 - cos(PI * edge / rampSamples)) / 2
                    (24000 * gain * sin(phase)).roundToInt().toShort()
                } else 0.toShort()
                buffer[bufferWriteOffset + written++] = value
                phase += phaseStep
                if (phase >= 2 * PI) phase -= 2 * PI
                position++
            }
        }
        return ReadResult(written, starts)
    }
}
