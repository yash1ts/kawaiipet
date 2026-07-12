package com.kawaiipet.app.llm

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

sealed interface NanoAiState {
    data object Checking : NanoAiState
    data object Available : NanoAiState
    data object Downloadable : NanoAiState
    data object Downloading : NanoAiState
    data class DownloadProgress(val bytesDownloaded: Long) : NanoAiState
    data object Unavailable : NanoAiState
    data class Failed(val message: String) : NanoAiState
}

/**
 * Owns the on-device Gemini Nano [GenerativeModel] and exposes readiness for UI gating.
 */
@Singleton
class GeminiNanoAvailability @Inject constructor() {

    val model: GenerativeModel = Generation.getClient(
        generationConfig {
            modelConfig = modelConfig {
                releaseStage = ModelReleaseStage.STABLE
                preference = ModelPreference.FULL
            }
        },
    )

    private val mutex = Mutex()
    private var warmedUp = false

    private val _state = MutableStateFlow<NanoAiState>(NanoAiState.Checking)
    val state: StateFlow<NanoAiState> = _state.asStateFlow()

    suspend fun refreshStatus() {
        mutex.withLock {
            _state.value = NanoAiState.Checking
            try {
                _state.value = mapFeatureStatus(model.checkStatus())
            } catch (e: Exception) {
                Log.w(TAG, "checkStatus failed", e)
                _state.value = NanoAiState.Failed(e.message ?: "On-device AI check failed")
            }
        }
    }

    /**
     * Best-effort warmup while the user is still giving input. Never starts a
     * download and never throws — [ensureReady] at chat time remains the authority.
     */
    suspend fun prewarm() {
        mutex.withLock {
            if (warmedUp && _state.value is NanoAiState.Available) return
            val status = try {
                model.checkStatus()
            } catch (e: Exception) {
                Log.w(TAG, "prewarm checkStatus failed (skipping)", e)
                return
            }
            _state.value = mapFeatureStatus(status)
            if (status != FeatureStatus.AVAILABLE) return
            if (!warmedUp) {
                try {
                    model.warmup()
                    warmedUp = true
                    Log.d(TAG, "prewarm complete")
                } catch (e: Exception) {
                    Log.w(TAG, "prewarm warmup failed (will retry at chat time)", e)
                }
            }
        }
    }

    /**
     * Ensures the model is downloaded and warmed up. Throws if unavailable or download fails.
     */
    suspend fun ensureReady() {
        mutex.withLock {
            // Fast path: model already warm — skip the per-turn AICore status IPC.
            if (warmedUp && _state.value is NanoAiState.Available) return
            val status = try {
                model.checkStatus()
            } catch (e: Exception) {
                val msg = e.message ?: "On-device AI check failed"
                _state.value = NanoAiState.Failed(msg)
                error(msg)
            }
            when (status) {
                FeatureStatus.AVAILABLE -> {
                    _state.value = NanoAiState.Available
                }
                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                    downloadLocked()
                }
                FeatureStatus.UNAVAILABLE -> {
                    _state.value = NanoAiState.Unavailable
                    error(UNAVAILABLE_MESSAGE)
                }
                else -> {
                    _state.value = NanoAiState.Unavailable
                    error(UNAVAILABLE_MESSAGE)
                }
            }
            if (!warmedUp) {
                try {
                    model.warmup()
                    warmedUp = true
                } catch (e: Exception) {
                    Log.w(TAG, "warmup failed (continuing)", e)
                }
            }
        }
    }

    /** Starts a user-initiated download when status is [NanoAiState.Downloadable]. */
    suspend fun download() {
        mutex.withLock {
            downloadLocked()
        }
    }

    private suspend fun downloadLocked() {
        _state.value = NanoAiState.Downloading
        try {
            model.download().collect { status ->
                when (status) {
                    is DownloadStatus.DownloadStarted -> {
                        _state.value = NanoAiState.Downloading
                    }
                    is DownloadStatus.DownloadProgress -> {
                        _state.value = NanoAiState.DownloadProgress(status.totalBytesDownloaded)
                    }
                    DownloadStatus.DownloadCompleted -> {
                        _state.value = NanoAiState.Available
                    }
                    is DownloadStatus.DownloadFailed -> {
                        val msg = status.e.message ?: "On-device AI download failed"
                        _state.value = NanoAiState.Failed(msg)
                        error(msg)
                    }
                }
            }
            if (_state.value !is NanoAiState.Available && _state.value !is NanoAiState.Failed) {
                val after = mapFeatureStatus(model.checkStatus())
                _state.value = after
                if (after !is NanoAiState.Available) {
                    error("On-device AI download did not complete")
                }
            }
        } catch (e: Exception) {
            if (_state.value !is NanoAiState.Failed) {
                _state.value = NanoAiState.Failed(e.message ?: "On-device AI download failed")
            }
            throw e
        }
    }

    fun isReady(): Boolean = _state.value is NanoAiState.Available

    companion object {
        private const val TAG = "GeminiNanoAvailability"
        const val UNAVAILABLE_MESSAGE =
            "On-device AI is not available on this device. Gemini Nano requires a supported device with AICore and a locked bootloader."

        private fun mapFeatureStatus(status: Int): NanoAiState = when (status) {
            FeatureStatus.AVAILABLE -> NanoAiState.Available
            FeatureStatus.DOWNLOADABLE -> NanoAiState.Downloadable
            FeatureStatus.DOWNLOADING -> NanoAiState.Downloading
            FeatureStatus.UNAVAILABLE -> NanoAiState.Unavailable
            else -> NanoAiState.Unavailable
        }
    }
}
