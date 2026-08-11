package com.kawaiipet.app.audio

import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * Neural VAD via sherpa-onnx Silero.
 *
 * Feed ungained float PCM (any length); samples are buffered into [VadEngineConfig.WINDOW_SIZE]
 * windows. Do not send AGC-boosted audio — leveling inflates noise and ruins the threshold.
 */
class SherpaVad(private val modelManager: ModelManager) {

    private val lock = Any()
    private var vad: Vad? = null
    private var windowSize: Int = VadEngineConfig.WINDOW_SIZE
    /** Leftover samples when mic chunks aren't a multiple of [windowSize]. */
    private var pending = FloatArray(0)

    val isInitialized: Boolean get() = synchronized(lock) { vad != null }

    val windowSamples: Int get() = windowSize

    fun initialize(): Boolean = synchronized(lock) {
        if (vad != null) return true
        val modelFile = File(
            modelManager.getModelDir(DefaultVoiceModels.VAD_MODEL_ID),
            DefaultVoiceModels.VAD_FILE_NAME,
        )
        if (!modelFile.isFile || modelFile.length() < 10_000L) {
            Log.w(TAG, "Silero VAD model missing: ${modelFile.absolutePath}")
            return false
        }
        return try {
            val silero = SileroVadModelConfig(
                model = modelFile.absolutePath,
                threshold = VadEngineConfig.THRESHOLD,
                minSilenceDuration = VadEngineConfig.MIN_SILENCE_SEC,
                minSpeechDuration = VadEngineConfig.MIN_SPEECH_SEC,
                windowSize = VadEngineConfig.WINDOW_SIZE,
                maxSpeechDuration = VadEngineConfig.MAX_SPEECH_SEC,
            )
            val config = VadModelConfig(
                sileroVadModelConfig = silero,
                tenVadModelConfig = TenVadModelConfig(),
                sampleRate = SttEngineConfig.SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            )
            // null AssetManager — model path is on disk.
            vad = Vad(null, config)
            windowSize = VadEngineConfig.WINDOW_SIZE
            pending = FloatArray(0)
            Log.i(
                TAG,
                "Silero VAD ready threshold=${VadEngineConfig.THRESHOLD} " +
                    "silence=${VadEngineConfig.MIN_SILENCE_SEC}s " +
                    "minSpeech=${VadEngineConfig.MIN_SPEECH_SEC}s " +
                    "maxSpeech=${VadEngineConfig.MAX_SPEECH_SEC}s " +
                    "window=$windowSize path=${modelFile.absolutePath}",
            )
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Silero VAD init failed", t)
            vad = null
            false
        }
    }

    fun reset() = synchronized(lock) {
        pending = FloatArray(0)
        runCatching { vad?.reset() }
        runCatching { vad?.clear() }
    }

    /**
     * Push mic floats (any length). Internally buffers to Silero's window size.
     * @return true if Silero currently considers the stream to be speech
     */
    fun acceptAndIsSpeech(samples: FloatArray): Boolean = synchronized(lock) {
        val v = vad ?: return false
        if (samples.isEmpty()) return v.isSpeechDetected()
        val merged = if (pending.isEmpty()) {
            samples
        } else {
            FloatArray(pending.size + samples.size).also {
                System.arraycopy(pending, 0, it, 0, pending.size)
                System.arraycopy(samples, 0, it, pending.size, samples.size)
            }
        }
        var offset = 0
        while (offset + windowSize <= merged.size) {
            val window = merged.copyOfRange(offset, offset + windowSize)
            v.acceptWaveform(window)
            offset += windowSize
        }
        pending = if (offset < merged.size) {
            merged.copyOfRange(offset, merged.size)
        } else {
            FloatArray(0)
        }
        return v.isSpeechDetected()
    }

    /** Pop completed speech segments (if any). */
    fun drainSegments(): List<SpeechSegment> = synchronized(lock) {
        val v = vad ?: return emptyList()
        val out = ArrayList<SpeechSegment>(2)
        while (!v.empty()) {
            out += v.front()
            v.pop()
        }
        out
    }

    fun flush() = synchronized(lock) {
        runCatching { vad?.flush() }
    }

    fun release() = synchronized(lock) {
        pending = FloatArray(0)
        runCatching { vad?.release() }
        vad = null
    }

    companion object {
        private const val TAG = "SherpaVad"
    }
}
