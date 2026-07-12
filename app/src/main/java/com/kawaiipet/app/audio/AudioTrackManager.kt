package com.kawaiipet.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import kotlin.math.abs

class AudioTrackManager {

    private var audioTrack: AudioTrack? = null
    var onAmplitude: ((Float) -> Unit)? = null

    /** 0f–1f applied to TTS [AudioTrack] (not system volume). */
    @Volatile
    var outputVolume: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    fun play(samples: FloatArray, sampleRate: Int) {
        stop()

        val bufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufSize.coerceAtLeast(samples.size * 4))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack?.let { track ->
            applyPetVoicePlayback(track)
            applyOutputVolume(track)
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            track.play()
        }
    }

    suspend fun playWithAmplitudeCallback(
        samples: FloatArray,
        sampleRate: Int
    ) = withContext(Dispatchers.IO) {
        stop()
        val track = buildStreamTrack(sampleRate)
        audioTrack = track
        applyPetVoicePlayback(track)
        applyOutputVolume(track)
        track.play()
        writeWithAmplitude(samples, sampleRate)
        onAmplitude?.invoke(0f)
    }

    /**
     * Streaming variant: reads TTS chunks from a [channel] and writes them to
     * AudioTrack as they arrive. Playback starts on the very first chunk,
     * so the user hears audio while later sentences are still being synthesized.
     */
    suspend fun playStreaming(
        channel: ReceiveChannel<FloatArray>,
        sampleRate: Int
    ) = withContext(Dispatchers.IO) {
        stop()
        val track = buildStreamTrack(sampleRate)
        audioTrack = track
        applyPetVoicePlayback(track)
        applyOutputVolume(track)
        track.play()

        for (samples in channel) {
            writeWithAmplitude(samples, sampleRate)
        }

        onAmplitude?.invoke(0f)
    }

    private fun buildStreamTrack(sampleRate: Int): AudioTrack {
        val bufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(sampleRate * 4)

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /**
     * Playback speed + pitch for pet voice ([SherpaTTS.DEFAULT_SYNTH_SPEED] is set to the same factor).
     */
    private fun applyPetVoicePlayback(track: AudioTrack) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        track.playbackParams = track.playbackParams.apply {
            pitch = PLAYBACK_PITCH
            speed = PLAYBACK_SPEED
        }
    }

    private fun applyOutputVolume(track: AudioTrack) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            track.setVolume(outputVolume)
        }
    }

    private fun writeWithAmplitude(samples: FloatArray, sampleRate: Int) {
        val chunkSize = sampleRate / 10 // 100ms playback chunks
        var offset = 0
        while (offset < samples.size) {
            val end = (offset + chunkSize).coerceAtMost(samples.size)
            val chunk = samples.sliceArray(offset until end)
            audioTrack?.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
            val amplitude = chunk.map { abs(it) }.average().toFloat()
            onAmplitude?.invoke(amplitude)
            offset = end
        }
    }

    fun stop() {
        try {
            audioTrack?.stop()
        } catch (_: IllegalStateException) { }
        audioTrack?.release()
        audioTrack = null
    }

    fun release() {
        stop()
        onAmplitude = null
    }

    companion object {
        /** >1.0 plays the same samples in less wall-clock time (main “faster voice” control). */
        private const val PLAYBACK_SPEED = 1.2f

        private const val PLAYBACK_PITCH = 1.12f
    }
}
