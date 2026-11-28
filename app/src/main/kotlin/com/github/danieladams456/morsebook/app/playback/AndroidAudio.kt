package com.github.danieladams456.morsebook.app.playback

import android.content.Context
import android.media.*
import com.github.danieladams456.morsebook.DEFAULT_SAMPLE_RATE_HZ

class AndroidAudio(
    context: Context,
    private val focusLost: (AudioFocusRequest) -> Unit,
    private val accepted: (ShortArray, Int, Int) -> Unit = { _, _, _ -> }
) : AudioDevice {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var request: AudioFocusRequest? = null

    fun owns(candidate: AudioFocusRequest) = request === candidate

    override fun acquireFocus(): Boolean {
        lateinit var candidate: AudioFocusRequest
        candidate = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { change -> if (change < 0) focusLost(candidate) }
            .build()
        if (manager.requestAudioFocus(candidate) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        request = candidate
        return true
    }

    override fun abandonFocus() {
        request?.let(manager::abandonAudioFocusRequest)
        request = null
    }

    override fun open(): PcmOutput {
        val format = AudioFormat.Builder().setSampleRate(DEFAULT_SAMPLE_RATE_HZ)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val minimum = AudioTrack.getMinBufferSize(DEFAULT_SAMPLE_RATE_HZ, format.channelMask, format.encoding)
        check(minimum > 0)
        val track = AudioTrack.Builder().setAudioFormat(format).setAudioAttributes(attributes)
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum, DEFAULT_SAMPLE_RATE_HZ / 2))
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            error("AudioTrack initialization failed")
        }
        return object : PcmOutput {
            override val bufferSizeFrames get() = track.bufferSizeInFrames
            override fun start() = track.play()
            override fun pause() = track.pause()
            override fun flush() = track.flush()
            override fun close() = track.release()
            override fun playedFrames() = Integer.toUnsignedLong(track.playbackHeadPosition)
            override fun write(samples: ShortArray, offset: Int, length: Int): Int {
                val count = track.write(samples, offset, length, AudioTrack.WRITE_NON_BLOCKING)
                if (count > 0) accepted(samples, offset, count)
                return count
            }
        }
    }
}
