package com.kawaiipet.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class AudioRecordManager {

    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var isRecording = false

    val sampleRate = SttEngineConfig.SAMPLE_RATE

    val bufferSize: Int
        get() = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(sampleRate)

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isRecording) return true

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR_BAD_VALUE || minBuf == AudioRecord.ERROR) return false

        val bufBytes = (minBuf * BUFFER_MULTIPLIER).coerceAtLeast(minBuf)

        // VOICE_RECOGNITION: tuned for ASR (less music EQ / less speakerphone AEC than VOICE_COMMUNICATION).
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufBytes)
                .build()
        } catch (_: SecurityException) {
            return false
        }

        audioRecord = record

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            audioRecord = null
            return false
        }

        try {
            record.startRecording()
        } catch (_: SecurityException) {
            record.release()
            audioRecord = null
            return false
        }

        enableCaptureEffects(record.audioSessionId)
        isRecording = true
        return true
    }

    private fun enableCaptureEffects(sessionId: Int) {
        // Soft denoise helps in quiet rooms; keep AGC off in software path — we level in SttInputCleaner.
        if (NoiseSuppressor.isAvailable()) {
            try {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                Log.d(TAG, "NoiseSuppressor enabled")
            } catch (t: Throwable) {
                Log.w(TAG, "NoiseSuppressor failed", t)
                noiseSuppressor?.release()
                noiseSuppressor = null
            }
        }
        // AEC reduces pet-speaker bleed into the mic when the overlay is speaking nearby next turn.
        if (AcousticEchoCanceler.isAvailable()) {
            try {
                acousticEchoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                Log.d(TAG, "AcousticEchoCanceler enabled")
            } catch (t: Throwable) {
                Log.w(TAG, "AcousticEchoCanceler failed", t)
                acousticEchoCanceler?.release()
                acousticEchoCanceler = null
            }
        }
        // Prefer system AGC off — double AGC with SttInputCleaner makes VAD noisy.
        if (AutomaticGainControl.isAvailable()) {
            try {
                automaticGainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = false }
                Log.d(TAG, "AutomaticGainControl forced off")
            } catch (t: Throwable) {
                Log.w(TAG, "AutomaticGainControl failed", t)
                automaticGainControl?.release()
                automaticGainControl = null
            }
        }
    }

    suspend fun readLoop(onSamples: (ShortArray) -> Unit) = withContext(Dispatchers.IO) {
        val buffer = ShortArray(CHUNK_SIZE)
        while (isActive && isRecording) {
            val read = audioRecord?.read(buffer, 0, CHUNK_SIZE) ?: break
            if (read > 0) {
                onSamples(buffer.copyOf(read))
            }
        }
    }

    fun stop() {
        isRecording = false
        releaseEffect(noiseSuppressor)
        noiseSuppressor = null
        releaseEffect(automaticGainControl)
        automaticGainControl = null
        releaseEffect(acousticEchoCanceler)
        acousticEchoCanceler = null
        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
        }
        audioRecord?.release()
        audioRecord = null
    }

    fun release() {
        stop()
    }

    private fun releaseEffect(effect: Any?) {
        try {
            when (effect) {
                is NoiseSuppressor -> effect.release()
                is AutomaticGainControl -> effect.release()
                is AcousticEchoCanceler -> effect.release()
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "AudioRecordManager"
        /** 100ms chunks @ 16 kHz — snappier VAD than 200ms. */
        private const val CHUNK_SIZE = 1600
        private const val BUFFER_MULTIPLIER = 4
    }
}
