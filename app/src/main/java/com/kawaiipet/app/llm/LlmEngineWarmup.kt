package com.kawaiipet.app.llm

import android.os.SystemClock
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Preloads the on-device LLM (and sticky conversation) the same way
 * [com.kawaiipet.app.audio.VoiceEngineWarmup] preloads Sherpa — so the first
 * pet tap does not pay engine init / sticky create cost.
 */
@Singleton
class LlmEngineWarmup @Inject constructor(
    private val smolLm: SmolLmAvailability,
    private val llmService: LlmService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var job: Job? = null

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /**
     * Best-effort: no-ops if the model file is not on disk yet.
     * Safe to call from app start, asset-ready, and overlay start.
     */
    fun startWarmup(reason: String = "start") {
        if (!smolLm.isModelOnDisk()) {
            Log.d(TAG, "Skip LLM warmup ($reason): model not on disk yet")
            return
        }
        if (job?.isActive == true) {
            Log.d(TAG, "LLM warmup already in flight ($reason)")
            return
        }
        job = scope.launch { warmLocked(reason) }
    }

    /** Await in-flight warmup (best-effort). */
    suspend fun awaitReady(timeoutMs: Long = 60_000L): Boolean {
        if (_ready.value && smolLm.isReady()) return true
        startWarmup("await")
        val current = job
        if (current != null) {
            withTimeoutOrNull(timeoutMs) { current.join() }
        }
        return smolLm.isReady()
    }

    private suspend fun warmLocked(reason: String) {
        mutex.withLock {
            if (!smolLm.isModelOnDisk()) {
                Log.d(TAG, "LLM warmup aborted ($reason): model missing")
                return@withLock
            }
            val t0 = SystemClock.elapsedRealtime()
            try {
                llmService.warmUp()
                _ready.value = smolLm.isReady()
                Log.i(
                    TAG,
                    "LLM warmup done ($reason) ready=${_ready.value} " +
                        "in ${SystemClock.elapsedRealtime() - t0}ms",
                )
            } catch (t: Throwable) {
                _ready.value = false
                Log.w(TAG, "LLM warmup failed ($reason)", t)
            }
        }
    }

    companion object {
        private const val TAG = "LlmEngineWarmup"
    }
}
