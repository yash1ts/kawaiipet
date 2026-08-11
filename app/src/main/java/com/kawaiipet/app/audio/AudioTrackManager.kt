package com.kawaiipet.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.kawaiipet.app.util.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

class AudioTrackManager {

    private val lock = Any()
    private var audioTrack: AudioTrack? = null
    private var generation = 0

    var onAmplitude: ((Float) -> Unit)? = null

    /** Soft gain applied to TTS PCM (1.0 = full). */
    @Volatile
    var outputVolume: Float = PreferenceManager.TTS_VOLUME_DEFAULT
        set(value) {
            field = value.coerceIn(PreferenceManager.TTS_VOLUME_MIN, PreferenceManager.TTS_VOLUME_MAX)
        }

    /** Playback rate paired with Sherpa synth speed (default 1.1x). */
    @Volatile
    var playbackSpeed: Float = PreferenceManager.TTS_SPEED_DEFAULT
        set(value) {
            field = value.coerceIn(PreferenceManager.TTS_SPEED_MIN, PreferenceManager.TTS_SPEED_MAX)
        }

    fun play(samples: FloatArray, sampleRate: Int) {
        stop()
        val gen: Int
        val track: AudioTrack
        synchronized(lock) {
            gen = ++generation
            track = buildStreamTrack(sampleRate)
            audioTrack = track
        }
        applyPetVoicePlayback(track)
        applyOutputVolume(track)
        track.play()
        writeWithAmplitude(track, samples, sampleRate, gen)
        blockingDrainPlayback(track, samples.size, sampleRate, gen)
        onAmplitude?.invoke(0f)
    }

    suspend fun playWithAmplitudeCallback(
        samples: FloatArray,
        sampleRate: Int
    ) = withContext(Dispatchers.IO) {
        stop()
        val gen: Int
        val track: AudioTrack
        synchronized(lock) {
            gen = ++generation
            track = buildStreamTrack(sampleRate)
            audioTrack = track
        }
        applyPetVoicePlayback(track)
        applyOutputVolume(track)
        track.play()
        writeWithAmplitude(track, samples, sampleRate, gen)
        drainPlayback(track, samples.size, sampleRate, gen)
        onAmplitude?.invoke(0f)
    }

    /**
     * Streaming variant: reads TTS chunks from a [channel] and writes them to
     * AudioTrack as they arrive. Waits until playback finishes before returning.
     */
    suspend fun playStreaming(
        channel: ReceiveChannel<FloatArray>,
        sampleRate: Int
    ) = withContext(Dispatchers.IO) {
        stop()
        val gen: Int
        val track: AudioTrack
        synchronized(lock) {
            gen = ++generation
            track = buildStreamTrack(sampleRate)
            audioTrack = track
        }
        applyPetVoicePlayback(track)
        applyOutputVolume(track)
        track.play()

        var framesWritten = 0
        for (samples in channel) {
            if (!isCurrent(gen)) break
            writeWithAmplitude(track, samples, sampleRate, gen)
            framesWritten += samples.size
        }

        if (isCurrent(gen) && framesWritten > 0) {
            drainPlayback(track, framesWritten, sampleRate, gen)
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

    private fun applyPetVoicePlayback(track: AudioTrack) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val speed = playbackSpeed
        track.playbackParams = track.playbackParams.apply {
            pitch = PLAYBACK_PITCH
            this.speed = speed
        }
    }

    private fun applyOutputVolume(track: AudioTrack) {
        // Hardware track volume tops out at 1.0; quieter levels use soft-gain in [applyGain].
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            track.setVolume(1f)
        }
    }

    private fun applyGain(samples: FloatArray): FloatArray {
        val gain = outputVolume
        if (kotlin.math.abs(gain - 1f) < 0.01f) return samples
        val out = FloatArray(samples.size)
        for (i in samples.indices) {
            out[i] = (samples[i] * gain).coerceIn(-1f, 1f)
        }
        return out
    }

    private fun writeWithAmplitude(
        track: AudioTrack,
        samples: FloatArray,
        sampleRate: Int,
        gen: Int,
    ) {
        val gained = applyGain(samples)
        val chunkSize = sampleRate / 10 // 100ms playback chunks
        var offset = 0
        while (offset < gained.size) {
            if (!isCurrent(gen)) return
            val end = (offset + chunkSize).coerceAtMost(gained.size)
            val chunk = gained.sliceArray(offset until end)
            try {
                track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
            } catch (_: IllegalStateException) {
                return
            }
            val amplitude = chunk.map { abs(it) }.average().toFloat()
            onAmplitude?.invoke(amplitude)
            offset = end
        }
    }

    /**
     * Wait until the playback head has consumed [framesWritten] frames
     * (scaled by playback speed), so the speak coroutine does not return early
     * and truncate the AudioTrack buffer on stop/release.
     */
    private suspend fun drainPlayback(
        track: AudioTrack,
        framesWritten: Int,
        sampleRate: Int,
        gen: Int,
    ) {
        if (framesWritten <= 0 || !isCurrent(gen)) return
        val timeoutMs = drainTimeoutMs(track, framesWritten, sampleRate)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (isCurrent(gen) && SystemClock.elapsedRealtime() < deadline) {
            val head = runCatching { track.playbackHeadPosition }.getOrDefault(0)
            if (head >= framesWritten - 1) break
            delay(20)
        }
        if (!isCurrent(gen)) return
        delay(40)
    }

    private fun blockingDrainPlayback(
        track: AudioTrack,
        framesWritten: Int,
        sampleRate: Int,
        gen: Int,
    ) {
        if (framesWritten <= 0 || !isCurrent(gen)) return
        val timeoutMs = drainTimeoutMs(track, framesWritten, sampleRate)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (isCurrent(gen) && SystemClock.elapsedRealtime() < deadline) {
            val head = runCatching { track.playbackHeadPosition }.getOrDefault(0)
            if (head >= framesWritten - 1) break
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                return
            }
        }
        if (!isCurrent(gen)) return
        try {
            Thread.sleep(40)
        } catch (_: InterruptedException) {
        }
    }

    private fun drainTimeoutMs(track: AudioTrack, framesWritten: Int, sampleRate: Int): Long {
        val speed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { track.playbackParams.speed }.getOrDefault(playbackSpeed)
                .coerceAtLeast(0.5f)
        } else {
            playbackSpeed
        }
        return ((framesWritten.toDouble() / sampleRate / speed) * 1000.0 + 1500.0)
            .toLong()
            .coerceIn(500L, 30_000L)
    }

    private fun isCurrent(gen: Int): Boolean = synchronized(lock) {
        generation == gen && audioTrack != null
    }

    fun stop() {
        val track: AudioTrack?
        synchronized(lock) {
            generation++
            track = audioTrack
            audioTrack = null
        }
        if (track == null) return
        try {
            track.pause()
        } catch (_: IllegalStateException) {
        }
        try {
            track.flush()
        } catch (_: Exception) {
        }
        try {
            track.stop()
        } catch (_: IllegalStateException) {
        }
        try {
            track.release()
        } catch (_: Exception) {
        }
    }

    fun release() {
        stop()
        onAmplitude = null
    }

    companion object {
        private const val TAG = "AudioTrackManager"

        private const val PLAYBACK_PITCH = 1.08f
    }
}
