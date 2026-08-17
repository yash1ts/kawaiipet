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
 * Feed ungained float PCM (any length); native code buffers to the model window.
 * Do not send AGC-boosted audio — leveling inflates noise and ruins the threshold.
 */
class SherpaVad(private val modelManager: ModelManager) {

    private val lock = Any()
    private var vad: Vad? = null
    private var initializedThreshold: Float? = null
    private var initializedMinSilence: Float? = null

    val isInitialized: Boolean get() = synchronized(lock) { vad != null }

    fun initialize(
        threshold: Float = VadEngineConfig.THRESHOLD,
        minSilenceSec: Float = VadEngineConfig.MIN_SILENCE_SEC,
    ): Boolean = synchronized(lock) {
        val thr = threshold.coerceIn(VadEngineConfig.THRESHOLD_MIN, VadEngineConfig.THRESHOLD_MAX)
        val silence = minSilenceSec.coerceIn(
            VadEngineConfig.MIN_SILENCE_SEC_MIN,
            VadEngineConfig.MIN_SILENCE_SEC_MAX,
        )
        if (vad != null && initializedThreshold == thr && initializedMinSilence == silence) {
            return true
        }
        if (vad != null) {
            runCatching { vad?.release() }
            vad = null
        }
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
                threshold = thr,
                minSilenceDuration = silence,
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
            initializedThreshold = thr
            initializedMinSilence = silence
            Log.i(
                TAG,
                "Silero VAD ready threshold=$thr " +
                    "silence=${silence}s " +
                    "minSpeech=${VadEngineConfig.MIN_SPEECH_SEC}s " +
                    "maxSpeech=${VadEngineConfig.MAX_SPEECH_SEC}s " +
                    "window=${VadEngineConfig.WINDOW_SIZE} path=${modelFile.absolutePath}",
            )
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Silero VAD init failed", t)
            vad = null
            initializedThreshold = null
            initializedMinSilence = null
            false
        }
    }

    fun reset() = synchronized(lock) {
        runCatching { vad?.reset() }
        runCatching { vad?.clear() }
    }

    /**
     * Push mic floats (any length). Native Silero buffers to its window size internally
     * (same as sherpa-onnx Android samples).
     * @return true if Silero currently considers the stream to be speech
     */
    fun acceptAndIsSpeech(samples: FloatArray): Boolean = synchronized(lock) {
        val v = vad ?: return false
        if (samples.isNotEmpty()) {
            v.acceptWaveform(samples)
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
        runCatching { vad?.release() }
        vad = null
        initializedThreshold = null
        initializedMinSilence = null
    }

    companion object {
        private const val TAG = "SherpaVad"
    }
}
