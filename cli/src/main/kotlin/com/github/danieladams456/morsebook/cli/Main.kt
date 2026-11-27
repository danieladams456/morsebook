package com.github.danieladams456.morsebook.cli

import com.github.danieladams456.morsebook.DEFAULT_SAMPLE_RATE_HZ
import com.github.danieladams456.morsebook.DEFAULT_TONE_HZ
import com.github.danieladams456.morsebook.DEFAULT_WPM
import com.github.danieladams456.morsebook.DefaultMorsePcmStream
import com.github.danieladams456.morsebook.StreamOptions
import com.github.danieladams456.morsebook.SynthesisOptions
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.system.exitProcess

private val help = """
    Usage: morsebook --text TEXT [--output FILE] [--wpm N] [--tone-hz N]
                     [--sample-rate N] [--buffer-size SAMPLES]
    Defaults: morse.wav, $DEFAULT_WPM WPM, $DEFAULT_TONE_HZ Hz, $DEFAULT_SAMPLE_RATE_HZ Hz, 4096 samples.
    Example: morsebook --text "AE T" --output practice.wav
""".trimIndent()

private fun header(bytes: Long, rate: Int): ByteArray = ByteBuffer.allocate(44)
    .order(ByteOrder.LITTLE_ENDIAN)
    .put("RIFF".toByteArray()).putInt((36 + bytes).toInt())
    .put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
    .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
    .put("data".toByteArray()).putInt(bytes.toInt()).array()

internal fun render(args: Array<String>) {
    val flags = setOf("--text", "--output", "--wpm", "--tone-hz", "--sample-rate", "--buffer-size")
    val values = mutableMapOf<String, String>()
    for (i in args.indices step 2) {
        val flag = args[i]
        require(flag in flags) { "unknown option $flag" }
        require(i + 1 < args.size && args[i + 1] !in flags) { "missing value for $flag" }
        require(values.put(flag, args[i + 1]) == null) { "duplicate option $flag" }
    }
    val text = requireNotNull(values["--text"]) { "--text is required" }
    val options = SynthesisOptions(
        values["--wpm"]?.toInt() ?: DEFAULT_WPM,
        values["--tone-hz"]?.toInt() ?: DEFAULT_TONE_HZ
    )
    val sampleRateHz = values["--sample-rate"]?.toInt() ?: DEFAULT_SAMPLE_RATE_HZ
    val size = values.getOrDefault("--buffer-size", "4096").toInt()
    require(size in 1..1048576) { "buffer size must be between 1 and 1048576 samples" }
    val stream = DefaultMorsePcmStream(StreamOptions(text, sampleRateHz))
    val buffer = ShortArray(size)
    val bytes = ByteBuffer.allocate(size * 2).order(ByteOrder.LITTLE_ENDIAN)
    val output = Path.of(values.getOrDefault("--output", "morse.wav")).toAbsolutePath()
    val temporary = Files.createTempFile(output.parent, ".morsebook-", ".wav")
    try {
        RandomAccessFile(temporary.toFile(), "rw").use { wav ->
            wav.write(header(0, sampleRateHz))
            var payload = 0L
            while (true) {
                val count = stream.read(options, buffer).framesWritten
                if (count == 0) break
                payload += count * 2L
                require(payload <= 0xffffffffL - 36) { "render exceeds RIFF size limit" }
                bytes.asShortBuffer().put(buffer, 0, count)
                wav.write(bytes.array(), 0, count * 2)
            }
            wav.seek(0)
            wav.write(header(payload, sampleRateHz))
        }
        Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

fun main(args: Array<String>) {
    if (args.contentEquals(arrayOf("--help"))) println(help)
    else try {
        render(args)
    } catch (e: Exception) {
        System.err.println("morsebook: ${e.message}. See --help.")
        exitProcess(1)
    }
}
