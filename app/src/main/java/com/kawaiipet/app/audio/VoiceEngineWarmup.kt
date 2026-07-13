package com.kawaiipet.app.audio

import android.util.Log
import com.kawaiipet.app.util.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceEngineWarmup @Inject constructor(
    private val modelManager: ModelManager,
    private val preferenceManager: PreferenceManager,
    private val audioPipeline: AudioPipeline,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun startWarmup() {
        scope.launch(Dispatchers.IO) {
            modelManager.installBundledModelsIfNeeded()
            val sttId = preferenceManager.getSttModelId()
            val ttsId = preferenceManager.getTtsModelId()
            val loadStt = sttId.isNotBlank() && modelManager.isModelDownloaded(sttId)
            val loadTts = ttsId.isNotBlank() && modelManager.isModelDownloaded(ttsId)
            if (!loadStt && !loadTts) {
                Log.d(TAG, "No voice models on disk yet; skipping engine warmup")
                return@launch
            }
            withContext(Dispatchers.Default) {
                if (loadStt) {
                    Log.d(TAG, "Warmup STT id=$sttId ok=${audioPipeline.initializeSTT(sttId)}")
                }
                if (loadTts) {
                    Log.d(TAG, "Warmup TTS id=$ttsId ok=${audioPipeline.initializeTTS(ttsId)}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "VoiceEngineWarmup"
    }
}
