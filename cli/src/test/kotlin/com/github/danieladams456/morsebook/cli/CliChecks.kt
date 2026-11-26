package com.github.danieladams456.morsebook.cli

import java.nio.file.Files
import javax.sound.sampled.AudioSystem

class CliChecks {
    @org.junit.Test
    fun renderContract() {
        val output = Files.createTempFile("cli-check", ".wav")
        try {
            val args = arrayOf("--text", "E", "--output", output.toString(), "--buffer-size", "257")
            render(args)
            val good = Files.readAllBytes(output)
            AudioSystem.getAudioInputStream(output.toFile()).use {
                check(it.frameLength == 2880L && it.readAllBytes().size == 5760)
            }
            check(runCatching { render(args + arrayOf("--wpm", "0")) }.isFailure)
            check(Files.readAllBytes(output).contentEquals(good))
        } finally {
            Files.delete(output)
        }
    }
}
