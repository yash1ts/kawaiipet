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
 * Owns the on-device LiteRT-LM [Engine] backed by Qwen3-0.6B INT4 (no-think).
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

    fun modelFile(): File =
        File(modelManager.getModelDir(RequiredAssets.LLM_MODEL_ID), RequiredAssets.LLM_FILE_NAME)

    fun isModelOnDisk(): Boolean = modelFile().isFile && modelFile().length() > 1_000_000L

    fun isReady(): Boolean = warmedUp && engine != null

    suspend fun ensureReady(): Engine = mutex.withLock {
        engine?.let { return it }
        val path = modelFile().absolutePath
        if (!isModelOnDisk()) {
            error("Qwen3 model missing at $path")
        }
        withContext(Dispatchers.Default) {
            Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
            // GPU first for throughput on Pixel; Tensor NPU next; CPU last resort.
            val backends = listOf(
                Backend.GPU(),
                Backend.GOOGLE_TENSOR(),
                Backend.CPU(),
            )
            var lastError: Throwable? = null
            for (backend in backends) {
                try {
                    val config = EngineConfig(
                        modelPath = path,
                        backend = backend,
                        // Must be <= model KV (ekv1280). Smaller = faster allocate for short turns.
                        maxNumTokens = MAX_NUM_TOKENS,
                        cacheDir = context.cacheDir.absolutePath,
                    )
                    Log.i(TAG, "Initializing LiteRT-LM engine backend=${backend.name} path=$path")
                    val created = Engine(config)
                    created.initialize()
                    engine = created
                    warmedUp = true
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
            .onFailure { Log.w(TAG, "Qwen3 warmUp failed", it) }
    }

    fun close() {
        runCatching { engine?.close() }
        engine = null
        warmedUp = false
    }

    companion object {
        private const val TAG = "SmolLmAvailability"
        /** Matches qwen3_*_ekv1280.litertlm KV cache length. */
        private const val MAX_NUM_TOKENS = 1280
    }
}
