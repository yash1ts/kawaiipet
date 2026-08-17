package com.kawaiipet.app.pet

import com.kawaiipet.app.audio.DefaultVoiceModels
import com.kawaiipet.app.audio.VadEngineConfig
import com.kawaiipet.app.llm.LlmPromptDefaults
import com.kawaiipet.app.util.PreferenceManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Snapshot of DataStore prefs used on the chat/speak hot path.
 * Avoids per-turn [PreferenceManager] `flow.first()` reads.
 */
data class SessionConfig(
    val petName: String = "Mochi",
    val personality: String = LlmPromptDefaults.DEFAULT_PERSONALITY,
    val sttModelId: String = DefaultVoiceModels.STT_MODEL_ID,
    val ttsModelId: String = DefaultVoiceModels.TTS_MODEL_ID,
    val ttsSpeakerId: Int = 1,
    val ttsVolume: Float = PreferenceManager.TTS_VOLUME_DEFAULT,
    val ttsSpeed: Float = PreferenceManager.TTS_SPEED_DEFAULT,
    val vadThreshold: Float = VadEngineConfig.THRESHOLD,
    val vadMinSilenceSec: Float = VadEngineConfig.MIN_SILENCE_SEC,
    val repetitionPenalty: Float = LlmPromptDefaults.REPETITION_PENALTY,
    val presencePenalty: Float = LlmPromptDefaults.PRESENCE_PENALTY,
    val frequencyPenalty: Float = LlmPromptDefaults.FREQUENCY_PENALTY,
    val noRepeatNgramSize: Int = LlmPromptDefaults.NO_REPEAT_NGRAM_SIZE,
)

@Singleton
class SessionConfigStore @Inject constructor(
    private val prefs: PreferenceManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _config = MutableStateFlow(SessionConfig())
    val config: StateFlow<SessionConfig> = _config.asStateFlow()

    init {
        scope.launch {
            refresh()
            merge(
                prefs.petName.map { },
                prefs.personalityPrompt.map { },
                prefs.sttModelId.map { },
                prefs.ttsModelId.map { },
                prefs.ttsSpeakerId.map { },
                prefs.ttsVolume.map { },
                prefs.ttsSpeed.map { },
                prefs.vadThreshold.map { },
                prefs.vadMinSilenceSec.map { },
                prefs.repetitionPenalty.map { },
                prefs.presencePenalty.map { },
                prefs.frequencyPenalty.map { },
                prefs.noRepeatNgramSize.map { },
            ).collect { refresh() }
        }
    }

    private suspend fun refresh() {
        _config.value = SessionConfig(
            petName = prefs.getPetName(),
            personality = prefs.getPersonalityPrompt(),
            sttModelId = prefs.getSttModelId(),
            ttsModelId = prefs.getTtsModelId(),
            ttsSpeakerId = prefs.getTtsSpeakerId(),
            ttsVolume = prefs.getTtsVolume(),
            ttsSpeed = prefs.getTtsSpeed(),
            vadThreshold = prefs.getVadThreshold(),
            vadMinSilenceSec = prefs.getVadMinSilenceSec(),
            repetitionPenalty = prefs.getRepetitionPenalty(),
            presencePenalty = prefs.getPresencePenalty(),
            frequencyPenalty = prefs.getFrequencyPenalty(),
            noRepeatNgramSize = prefs.getNoRepeatNgramSize(),
        )
    }

    fun snapshot(): SessionConfig = _config.value
}
