package com.github.danieladams456.morsebook.cli

import com.github.danieladams456.morsebook.MorsePcmStream
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
    Defaults: morse.wav, 20 WPM, 600 Hz, 48000 Hz, 4096 samples.
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
    val options = SynthesisOptions.create(
        values.getOrDefault("--wpm", "20").toDouble(),
        values.getOrDefault("--tone-hz", "600").toDouble(),
        values.getOrDefault("--sample-rate", "48000").toInt()
    )
    val size = values.getOrDefault("--buffer-size", "4096").toInt()
    require(size in 1..1048576) { "buffer size must be between 1 and 1048576 samples" }
    val stream = MorsePcmStream(text, options)
    val buffer = ShortArray(size)
    val bytes = ByteBuffer.allocate(size * 2).order(ByteOrder.LITTLE_ENDIAN)
    val output = Path.of(values.getOrDefault("--output", "morse.wav")).toAbsolutePath()
    val temporary = Files.createTempFile(output.parent, ".morsebook-", ".wav")
    try {
        RandomAccessFile(temporary.toFile(), "rw").use { wav ->
            wav.write(header(0, stream.sampleRateHz))
            var payload = 0L
            while (true) {
                val count = stream.read(buffer)
                if (count == 0) break
                payload += count * 2L
                require(payload <= 0xffffffffL - 36) { "render exceeds RIFF size limit" }
                bytes.asShortBuffer().put(buffer, 0, count)
                wav.write(bytes.array(), 0, count * 2)
            }
            wav.seek(0)
            wav.write(header(payload, stream.sampleRateHz))
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
