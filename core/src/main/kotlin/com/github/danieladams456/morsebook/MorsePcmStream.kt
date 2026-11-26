package com.github.danieladams456.morsebook

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

data class SynthesisOptions(val wpm: Double, val toneHz: Double, val sampleRateHz: Int) {
    init {
        require(wpm in 1.0..100.0) { "WPM must be between 1 and 100" }
        require(sampleRateHz in 8000..192000) { "sample rate must be between 8000 and 192000 Hz" }
        require(toneHz >= 20 && toneHz < sampleRateHz / 2.0) { "tone must be at least 20 Hz and below Nyquist" }
    }

    val unitSamples = sampleRateHz * 1.2 / wpm
    val rampSamples = minOf(sampleRateHz * 0.005, unitSamples / 2).roundToInt()
    internal val phaseStep = 2 * PI * toneHz / sampleRateHz

    companion object {
        fun create(wpm: Double = 20.0, toneHz: Double = 600.0, sampleRateHz: Int = 48000) =
            SynthesisOptions(wpm, toneHz, sampleRateHz)
    }
}

private val codes = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toList().zip(
    ".- -... -.-. -.. . ..-. --. .... .. .--- -.- .-.. -- -. --- .--. --.- .-. ... - ..- ...- .-- -..- -.-- --.. ----- .---- ..--- ...-- ....- ..... -.... --... ---.. ----.".split(' ')
).toMap()

class MorsePcmStream(text: String, options: SynthesisOptions = SynthesisOptions.create()) {
    val sampleRateHz = options.sampleRateHz
    private val message = buildString {
        var separated = false
        for (c in text) {
            if (c.isWhitespace()) separated = isNotEmpty()
            val key = if (c in 'a'..'z') c.uppercaseChar() else c
            if (key in codes) {
                if (separated) append(' ')
                append(key)
                separated = false
            }
        }
    }

    private var current = options
    private var pending = options
    private var next = 0
    private var pattern = ""
    private var mark = 0
    private var keyed = false
    private var position = 0
    private var duration = 0
    private var phase = 0.0
    private var roundingError = 0.0

    @Synchronized
    fun updateOptions(options: SynthesisOptions) {
        require(options.sampleRateHz == sampleRateHz) { "stream sample rate is fixed" }
        pending = options
    }

    private fun startSegment(): Boolean {
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
                current = pending
                pattern = codes.getValue(message[next++])
                mark = 0
            }
            keyed = true
            units = if (pattern[mark++] == '.') 1 else 3
        }
        val exact = units * current.unitSamples + roundingError
        duration = exact.roundToInt()
        roundingError = exact - duration
        position = 0
        return true
    }

    @Synchronized
    fun read(destination: ShortArray, offset: Int = 0, length: Int = destination.size - offset): Int {
        require(offset in 0..destination.size && length in 0..destination.size - offset)
        var written = 0
        while (written < length) {
            if (position == duration && !startSegment()) break
            val count = minOf(length - written, duration - position)
            repeat(count) {
                val value = if (keyed) {
                    val edge = minOf(position, duration - 1 - position)
                    val gain = if (edge >= current.rampSamples) 1.0
                        else (1 - cos(PI * edge / current.rampSamples)) / 2
                    (24000 * gain * sin(phase)).roundToInt().toShort()
                } else 0.toShort()
                destination[offset + written++] = value
                phase += current.phaseStep
                if (phase >= 2 * PI) phase -= 2 * PI
                position++
            }
        }
        return written
    }
}
