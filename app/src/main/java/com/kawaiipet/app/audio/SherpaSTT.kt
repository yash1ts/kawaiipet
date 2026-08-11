package com.kawaiipet.app.audio

import android.util.Log
import com.k2fsa.sherpa.onnx.HomophoneReplacerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OnlineLMConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * Sherpa-ONNX STT: streaming Zipformer transducer, offline Moonshine, or offline NeMo CTC.
 */
class SherpaSTT(private val modelManager: ModelManager) {

    /** OfflineRecognizer JNI — must not overlap decode/acceptWaveform across threads. */
    private val offlineJniLock = Any()

    private var onlineRecognizer: OnlineRecognizer? = null
    private var onlineStream: OnlineStream? = null

    private var offlineRecognizer: OfflineRecognizer? = null
    private var offlineStream: OfflineStream? = null

    private var useOffline: Boolean = false
    private var offlineKind: String = ""

    private var initializedForModelId: String? = null

    val isInitialized: Boolean
        get() = onlineRecognizer != null || offlineRecognizer != null

    fun initialize(modelId: String): Boolean {
        if (modelId.isBlank()) {
            release()
            return false
        }
        if (initializedForModelId == modelId && isInitialized) return true

        release()
        if (!modelManager.isModelDownloaded(modelId)) {
            Log.w(TAG, "Model not on disk: $modelId")
            return false
        }

        val transducer = modelManager.resolveSherpaStreamingTransducer(modelId)
        if (transducer != null) {
            useOffline = false
            return initOnlineTransducer(modelId, transducer)
        }

        val nemo = modelManager.resolveSherpaNemoCtc(modelId)
        if (nemo != null) {
            return initNemoCtcOffline(modelId, nemo)
        }

        val moonshine = modelManager.resolveSherpaMoonshine(modelId)
        if (moonshine != null) {
            return initMoonshineOffline(modelId, moonshine)
        }

        Log.e(TAG, "No supported STT layout for $modelId")
        return false
    }

    private fun initOnlineTransducer(modelId: String, paths: SherpaStreamingTransducerPaths): Boolean {
        val transducer = OnlineTransducerModelConfig(
            encoder = paths.encoderPath,
            decoder = paths.decoderPath,
            joiner = paths.joinerPath
        )
        val modelConfig = OnlineModelConfig(
            transducer = transducer,
            tokens = paths.tokensPath,
            numThreads = sttNumThreads(),
            provider = "cpu",
            debug = false
        )
        val config = OnlineRecognizerConfig(
            featConfig = SttEngineConfig.featureConfig(),
            modelConfig = modelConfig,
            lmConfig = OnlineLMConfig(),
            endpointConfig = SttEngineConfig.endpointConfig(),
            enableEndpoint = true
        )
        return try {
            onlineRecognizer = OnlineRecognizer(assetManager = null, config = config)
            initializedForModelId = modelId
            Log.i(TAG, "STT initialized (transducer): $modelId")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "STT transducer init failed", t)
            onlineRecognizer = null
            initializedForModelId = null
            false
        }
    }

    private fun initNemoCtcOffline(modelId: String, paths: SherpaNemoCtcPaths): Boolean {
        val modelCfg = OfflineModelConfig().apply {
            nemo = OfflineNemoEncDecCtcModelConfig(model = paths.modelPath)
            tokens = paths.tokensPath
            numThreads = sttNumThreads()
            debug = false
            provider = "cpu"
        }
        return initOfflineRecognizer(modelId, modelCfg, kind = "NeMo CTC")
    }

    private fun initMoonshineOffline(modelId: String, paths: SherpaMoonshinePaths): Boolean {
        val moon = OfflineMoonshineModelConfig(
            preprocessor = "",
            encoder = paths.encoderPath,
            uncachedDecoder = "",
            cachedDecoder = "",
            mergedDecoder = paths.mergedDecoderPath
        )
        val modelCfg = OfflineModelConfig().apply {
            moonshine = moon
            tokens = paths.tokensPath
            numThreads = sttNumThreads()
            debug = false
            provider = "cpu"
        }
        return initOfflineRecognizer(modelId, modelCfg, kind = "Moonshine")
    }

    private fun initOfflineRecognizer(
        modelId: String,
        modelCfg: OfflineModelConfig,
        kind: String,
    ): Boolean {
        val config = OfflineRecognizerConfig().apply {
            featConfig = SttEngineConfig.featureConfig()
            modelConfig = modelCfg
            hr = HomophoneReplacerConfig("", "", "")
            decodingMethod = "greedy_search"
            maxActivePaths = 4
            hotwordsFile = ""
            hotwordsScore = 1.5f
            ruleFsts = ""
            ruleFars = ""
            // Mild blank penalty reduces dropped short words / trailing cutoffs on CTC/Moonshine.
            blankPenalty = SttEngineConfig.OFFLINE_BLANK_PENALTY
        }
        return try {
            offlineRecognizer = OfflineRecognizer(assetManager = null, config = config)
            useOffline = true
            offlineKind = kind
            initializedForModelId = modelId
            Log.i(TAG, "STT initialized ($kind offline): $modelId")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "STT $kind init failed", t)
            offlineRecognizer = null
            useOffline = false
            offlineKind = ""
            initializedForModelId = null
            false
        }
    }

    fun startStream() {
        endStream()
        if (useOffline) {
            synchronized(offlineJniLock) {
                val rec = offlineRecognizer ?: return
                offlineStream = try {
                    rec.createStream()
                } catch (t: Throwable) {
                    Log.e(TAG, "$offlineKind createStream failed", t)
                    null
                }
            }
            return
        }
        val rec = onlineRecognizer ?: return
        onlineStream = try {
            rec.createStream()
        } catch (t: Throwable) {
            Log.e(TAG, "createStream failed", t)
            null
        }
    }

    fun acceptWaveform(samples: FloatArray) {
        if (useOffline) {
            synchronized(offlineJniLock) {
                val s = offlineStream ?: return
                try {
                    s.acceptWaveform(samples, SttEngineConfig.SAMPLE_RATE)
                } catch (t: Throwable) {
                    Log.e(TAG, "$offlineKind acceptWaveform failed", t)
                }
            }
            return
        }
        val s = onlineStream ?: return
        val rec = onlineRecognizer ?: return
        try {
            s.acceptWaveform(samples, sampleRate = SttEngineConfig.SAMPLE_RATE)
            while (rec.isReady(s)) {
                rec.decode(s)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "acceptWaveform failed", t)
        }
    }

    fun isEndpoint(): Boolean {
        if (useOffline) return false
        val s = onlineStream ?: return false
        val rec = onlineRecognizer ?: return false
        return try {
            rec.isEndpoint(s)
        } catch (_: Throwable) {
            false
        }
    }

    fun getPartialResult(): String {
        if (useOffline) return ""
        val s = onlineStream ?: return ""
        val rec = onlineRecognizer ?: return ""
        return try {
            rec.getResult(s).text
        } catch (_: Throwable) {
            ""
        }
    }

    fun getFinalResult(): String {
        if (useOffline) {
            synchronized(offlineJniLock) {
                val s = offlineStream ?: return ""
                val rec = offlineRecognizer ?: return ""
                return try {
                    rec.decode(s)
                    rec.getResult(s).text.trim()
                } catch (t: Throwable) {
                    Log.e(TAG, "$offlineKind final decode failed", t)
                    ""
                }
            }
        }
        return getPartialResult().trim()
    }

    fun endStream() {
        try {
            onlineStream?.release()
        } catch (_: Exception) {
        }
        onlineStream = null
        synchronized(offlineJniLock) {
            try {
                offlineStream?.release()
            } catch (_: Exception) {
            }
            offlineStream = null
        }
    }

    fun release() {
        endStream()
        try {
            onlineRecognizer?.release()
        } catch (_: Exception) {
        }
        onlineRecognizer = null
        synchronized(offlineJniLock) {
            try {
                offlineRecognizer?.release()
            } catch (_: Exception) {
            }
            offlineRecognizer = null
        }
        useOffline = false
        offlineKind = ""
        initializedForModelId = null
    }

    companion object {
        private const val TAG = "SherpaSTT"

        private fun sttNumThreads(): Int =
            minOf(4, maxOf(2, Runtime.getRuntime().availableProcessors()))
    }
}
