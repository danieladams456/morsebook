package com.github.danieladams456.morsebook

class CoreChecks {
    private val unit = 2880

    private fun render(text: String, size: Int): ShortArray {
        val stream = MorsePcmStream(text)
        val buffer = ShortArray(size + 2) { -123 }
        val result = mutableListOf<Short>()
        while (true) {
            val n = stream.read(buffer, 1, size)
            if (n == 0) break
            result.addAll(buffer.slice(1..n))
            check(buffer.first() == (-123).toShort() && buffer.last() == (-123).toShort())
            if (n < size) check(stream.read(buffer) == 0)
        }
        check(stream.read(buffer) == 0)
        return result.toShortArray()
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
        val stream = MorsePcmStream("AE T")
        val prefix = ShortArray(8 * unit)
        check(stream.read(prefix, 0, 317) == 317)
        stream.updateOptions(SynthesisOptions.create(25.0, 900.0))
        val latest = SynthesisOptions.create(40.0, 800.0)
        stream.updateOptions(latest)
        check(stream.read(prefix, 317) == prefix.size - 317)
        check(prefix.contentEquals(original.copyOf(prefix.size)))
        val tail = ShortArray(11 * unit / 2)
        check(stream.read(tail) == tail.size && stream.read(tail) == 0)
        val expected = ShortArray(tail.size)
        check(MorsePcmStream("E T", latest).read(expected) == expected.size)
        check(tail.contentEquals(expected))
    }
}
