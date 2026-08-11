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
    /** Guards initialize/release and online recognizer pointer lifetime. */
    private val lifecycleLock = Any()
    private val onlineJniLock = Any()

    private var onlineRecognizer: OnlineRecognizer? = null
    private var onlineStream: OnlineStream? = null

    private var offlineRecognizer: OfflineRecognizer? = null
    private var offlineStream: OfflineStream? = null

    private var useOffline: Boolean = false
    private var offlineKind: String = ""

    private var initializedForModelId: String? = null

    val isInitialized: Boolean
        get() = onlineRecognizer != null || offlineRecognizer != null

    fun initialize(modelId: String): Boolean = synchronized(lifecycleLock) {
        if (modelId.isBlank()) {
            releaseLocked()
            return false
        }
        if (initializedForModelId == modelId && isInitialized) return true

        releaseLocked()
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
        val threads = SherpaOnnxRuntime.numThreads()
        var lastError: Throwable? = null
        for (provider in SherpaOnnxRuntime.preferredProviders()) {
            val modelConfig = OnlineModelConfig(
                transducer = transducer,
                tokens = paths.tokensPath,
                numThreads = threads,
                provider = provider,
                debug = false
            )
            val config = OnlineRecognizerConfig(
                featConfig = SttEngineConfig.featureConfig(),
                modelConfig = modelConfig,
                lmConfig = OnlineLMConfig(),
                endpointConfig = SttEngineConfig.endpointConfig(),
                enableEndpoint = true
            )
            try {
                onlineRecognizer = OnlineRecognizer(assetManager = null, config = config)
                initializedForModelId = modelId
                Log.i(TAG, "STT initialized (transducer): $modelId provider=$provider threads=$threads")
                return true
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "STT transducer init failed provider=$provider", t)
            }
        }
        Log.e(TAG, "STT transducer init failed for all providers", lastError)
        onlineRecognizer = null
        initializedForModelId = null
        return false
    }

    private fun initNemoCtcOffline(modelId: String, paths: SherpaNemoCtcPaths): Boolean {
        return initOfflineWithProviders(modelId, kind = "NeMo CTC") { provider, threads ->
            OfflineModelConfig().apply {
                nemo = OfflineNemoEncDecCtcModelConfig(model = paths.modelPath)
                tokens = paths.tokensPath
                numThreads = threads
                debug = false
                this.provider = provider
            }
        }
    }

    private fun initMoonshineOffline(modelId: String, paths: SherpaMoonshinePaths): Boolean {
        val moon = OfflineMoonshineModelConfig(
            preprocessor = "",
            encoder = paths.encoderPath,
            uncachedDecoder = "",
            cachedDecoder = "",
            mergedDecoder = paths.mergedDecoderPath
        )
        return initOfflineWithProviders(modelId, kind = "Moonshine") { provider, threads ->
            OfflineModelConfig().apply {
                moonshine = moon
                tokens = paths.tokensPath
                numThreads = threads
                debug = false
                this.provider = provider
            }
        }
    }

    private fun initOfflineWithProviders(
        modelId: String,
        kind: String,
        buildModel: (provider: String, threads: Int) -> OfflineModelConfig,
    ): Boolean {
        val threads = SherpaOnnxRuntime.numThreads()
        for (provider in SherpaOnnxRuntime.preferredProviders()) {
            if (initOfflineRecognizer(modelId, buildModel(provider, threads), kind, provider, threads)) {
                return true
            }
        }
        Log.e(TAG, "STT $kind init failed for all providers")
        return false
    }

    private fun initOfflineRecognizer(
        modelId: String,
        modelCfg: OfflineModelConfig,
        kind: String,
        provider: String,
        threads: Int,
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
            Log.i(TAG, "STT initialized ($kind offline): $modelId provider=$provider threads=$threads")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "STT $kind init failed provider=$provider — trying next", t)
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
        synchronized(onlineJniLock) {
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
    }

    fun isEndpoint(): Boolean {
        if (useOffline) return false
        return synchronized(onlineJniLock) {
            val s = onlineStream ?: return false
            val rec = onlineRecognizer ?: return false
            try {
                rec.isEndpoint(s)
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun getPartialResult(): String {
        if (useOffline) return ""
        return synchronized(onlineJniLock) {
            val s = onlineStream ?: return ""
            val rec = onlineRecognizer ?: return ""
            try {
                rec.getResult(s).text
            } catch (_: Throwable) {
                ""
            }
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

    fun release() = synchronized(lifecycleLock) { releaseLocked() }

    private fun releaseLocked() {
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
    }
}
