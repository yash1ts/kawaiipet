package com.kawaiipet.app.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.kawaiipet.app.assets.RequiredAssets
import com.kawaiipet.app.audio.ModelManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the on-device LiteRT-LM [Engine] backed by LFM2.5-1.2B-Instruct INT4.
 */
@Singleton
class SmolLmAvailability @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val modelManager: ModelManager,
) {
    private val mutex = Mutex()

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var warmedUp = false

    @Volatile
    private var activeBackendName: String? = null

    fun modelFile(): File =
        File(modelManager.getModelDir(RequiredAssets.LLM_MODEL_ID), RequiredAssets.LLM_FILE_NAME)

    fun isModelOnDisk(): Boolean = modelFile().isFile && modelFile().length() > 1_000_000L

    fun isReady(): Boolean = warmedUp && engine != null

    fun currentBackendName(): String? = activeBackendName

    suspend fun ensureReady(): Engine = mutex.withLock {
        engine?.let { return it }
        createEngineLocked(preferCpu = false)
    }

    /**
     * Tear down the current engine and recreate on CPU.
     * Used when GPU decode fails with logits-shape errors on some devices.
     */
    suspend fun recreateOnCpu(): Engine = mutex.withLock {
        closeLocked()
        createEngineLocked(preferCpu = true)
    }

    private suspend fun createEngineLocked(preferCpu: Boolean): Engine {
        val path = modelFile().absolutePath
        if (!isModelOnDisk()) {
            error("LFM2.5 model missing at $path")
        }
        return withContext(Dispatchers.Default) {
            Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
            val backends = if (preferCpu) {
                listOf(Backend.CPU(), Backend.GOOGLE_TENSOR(), Backend.GPU())
            } else {
                // GPU first for throughput on Pixel; Tensor NPU next; CPU last resort.
                listOf(Backend.GPU(), Backend.GOOGLE_TENSOR(), Backend.CPU())
            }
            var lastError: Throwable? = null
            for (backend in backends) {
                try {
                    val config = EngineConfig(
                        modelPath = path,
                        backend = backend,
                        // Model KV is 4096; keep a short-chat budget so allocate stays cheap.
                        maxNumTokens = MAX_NUM_TOKENS,
                        cacheDir = context.cacheDir.absolutePath,
                    )
                    Log.i(TAG, "Initializing LiteRT-LM engine backend=${backend.name} path=$path")
                    val created = Engine(config)
                    created.initialize()
                    engine = created
                    warmedUp = true
                    activeBackendName = backend.name
                    Log.i(TAG, "LiteRT-LM engine ready on ${backend.name}")
                    return@withContext created
                } catch (t: Throwable) {
                    lastError = t
                    Log.w(TAG, "LiteRT-LM init failed on ${backend.name}", t)
                }
            }
            throw lastError ?: IllegalStateException("Failed to init LiteRT-LM")
        }
    }

    suspend fun warmUp() {
        runCatching { ensureReady() }
            .onFailure { Log.w(TAG, "LFM2.5 warmUp failed", it) }
    }

    fun close() {
        runCatching { engine?.close() }
        engine = null
        warmedUp = false
        activeBackendName = null
    }

    private fun closeLocked() {
        runCatching { engine?.close() }
        engine = null
        warmedUp = false
        activeBackendName = null
    }

    companion object {
        private const val TAG = "SmolLmAvailability"
        /** Cap below the model's 4096 KV so short pet turns allocate faster. */
        private const val MAX_NUM_TOKENS = 1280
    }
}
