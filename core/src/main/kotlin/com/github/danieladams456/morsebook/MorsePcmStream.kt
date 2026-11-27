package com.github.danieladams456.morsebook

const val DEFAULT_SAMPLE_RATE_HZ = 48_000
const val DEFAULT_WPM = 20
const val DEFAULT_TONE_HZ = 500

/** Playback begins at the latest playable word start at or before [textStartOffset],
 * measured in UTF-16 code units. Text with no playable words yields an empty stream.
 */
data class StreamOptions(val text: String, val sampleRateHz: Int, val textStartOffset: Int = 0) {
    init {
        require(sampleRateHz in 8000..192000) { "sample rate must be between 8 and 192 kHz" }
        require(textStartOffset >= 0) { "text start offset must be nonnegative" }
    }
}

data class SynthesisOptions(val wpm: Int, val toneHz: Int) {
    init {
        require(wpm in 5..100) { "WPM must be between 5 and 100" }
        require(toneHz in 300..1200) { "tone must be between 300 and 1200 Hz" }
    }
}

/** A word's first mark. sourceOffset counts UTF-16 units in the original text.
 * frameOffset is zero-based within this read, independent of the destination slice.
 */
data class WordStart(val sourceOffset: Int, val frameOffset: Int)

data class ReadResult(val framesWritten: Int, val wordStarts: List<WordStart>)

interface MorsePcmStream {
    /** Fills the buffer slice and reports each word in the read containing its first frame.
     * Each new character uses this read's options, including for the gap that follows it.
     * The tone must be below half the stream's sample rate. At EOF, framesWritten is zero.
     */
    fun read(
        options: SynthesisOptions,
        buffer: ShortArray,
        bufferWriteOffset: Int = 0,
        length: Int = buffer.size - bufferWriteOffset
    ): ReadResult
}
