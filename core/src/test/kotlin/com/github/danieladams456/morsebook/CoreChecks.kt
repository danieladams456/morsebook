package com.github.danieladams456.morsebook

class CoreChecks {
    private val options = SynthesisOptions(DEFAULT_WPM, DEFAULT_TONE_HZ)
    private val unit = 2880

    private fun render(text: String, size: Int): ShortArray {
        val stream = DefaultMorsePcmStream(StreamOptions(text, 48000))
        val buffer = ShortArray(size + 2) { -123 }
        val result = mutableListOf<Short>()
        while (true) {
            val n = stream.read(options, buffer, 1, size).framesWritten
            if (n == 0) break
            result.addAll(buffer.slice(1..n))
            check(buffer.first() == (-123).toShort() && buffer.last() == (-123).toShort())
            if (n < size) check(stream.read(options, buffer).framesWritten == 0)
        }
        check(stream.read(options, buffer).framesWritten == 0)
        return result.toShortArray()
    }

    @org.junit.Test
    fun streamOptionsValidateRateAndOffsetAtConstruction() {
        for (rate in listOf(7999, 192001)) {
            check(runCatching { StreamOptions("E", rate) }.exceptionOrNull() is IllegalArgumentException)
        }
        check(runCatching { StreamOptions("E", 48000, -1) }.exceptionOrNull() is IllegalArgumentException)
        check(StreamOptions("", 8000).textStartOffset == 0)
        check(StreamOptions("E", 192000).sampleRateHz == 192000)
    }

    @org.junit.Test
    fun readsAreContinuousAcrossBufferSlices() {
        check(render("AE T", 4096).contentEquals(render(" a!e \t t ", 257)))
        check(render(" ? ", 1).isEmpty())
    }

    @org.junit.Test
    fun marksAndGapsFollowStandardTiming() {
        val original = render("AE T", 4096)
        var start = 0
        for ((i, units) in listOf(1, 1, 3, 3, 1, 7, 3).withIndex()) {
            val end = start + units * unit
            check(original.slice(start until end).any { it != 0.toShort() } == (i % 2 == 0))
            start = end
        }
        check(start == original.size)
    }

    @org.junit.Test
    fun latestOptionsApplyAfterTheFollowingGap() {
        val original = render("AE T", 4096)
        val stream = DefaultMorsePcmStream(StreamOptions("AE T", 48000))
        val prefix = ShortArray(8 * unit)
        check(stream.read(options, prefix, 0, 317).framesWritten == 317)
        check(stream.read(SynthesisOptions(25, 900), prefix, 317, 1).framesWritten == 1)
        val latest = SynthesisOptions(40, 800)
        check(stream.read(latest, prefix, 318).framesWritten == prefix.size - 318)
        check(prefix.contentEquals(original.copyOf(prefix.size)))
        val tail = ShortArray(11 * unit / 2)
        check(stream.read(latest, tail).framesWritten == tail.size && stream.read(latest, tail).framesWritten == 0)
        val expected = ShortArray(tail.size)
        check(DefaultMorsePcmStream(StreamOptions("E T", 48000)).read(latest, expected).framesWritten == expected.size)
        check(tail.contentEquals(expected))
    }

    @org.junit.Test
    fun positionsFollowPlayedWordsAcrossSlicesAndResumes() {
        val text = "😀E!T  ... E T"
        val first = text.indexOf('E')
        val second = text.lastIndexOf('E')
        val third = text.lastIndexOf('T')
        val stream = DefaultMorsePcmStream(StreamOptions(text, 48000))
        val prefix = ShortArray(14 * unit)
        check(stream.read(options, prefix) == ReadResult(prefix.size, listOf(WordStart(first, 0))))
        val tail = ShortArray(12 * unit + 2)
        check(stream.read(options, tail, 1, 0) == ReadResult(0, emptyList()))
        val expected = ReadResult(11 * unit, listOf(WordStart(second, 0), WordStart(third, 8 * unit)))
        check(stream.read(options, tail, 1) == expected)
        check(stream.read(options, tail) == ReadResult(0, emptyList()))
        val resumed = DefaultMorsePcmStream(StreamOptions(text, 48000, textStartOffset = second + 1))
        check(resumed.read(options, tail) == expected)
        val last = DefaultMorsePcmStream(StreamOptions(text, 48000, textStartOffset = text.length + 10))
        check(last.read(options, tail, 0, 1).framesWritten == 1)
        check(last.read(options, tail) == ReadResult(3 * unit - 1, emptyList()))
    }
}
