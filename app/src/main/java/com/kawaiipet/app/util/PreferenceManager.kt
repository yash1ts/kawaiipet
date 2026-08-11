package com.kawaiipet.app.util

import android.content.Context
import com.kawaiipet.app.audio.DefaultVoiceModels
import com.kawaiipet.app.llm.LlmPromptDefaults
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kawaiipet_prefs")

class PreferenceManager(private val context: Context) {

    val petName: Flow<String> = context.dataStore.data.map { it[Keys.PET_NAME] ?: "Mochi" }
    val sttModelId: Flow<String> = context.dataStore.data.map {
        migrateSttModelId(it[Keys.STT_MODEL_ID] ?: DefaultVoiceModels.STT_MODEL_ID)
    }
    val ttsModelId: Flow<String> = context.dataStore.data.map {
        migrateTtsModelId(it[Keys.TTS_MODEL_ID] ?: DefaultVoiceModels.TTS_MODEL_ID)
    }
    val personalityPrompt: Flow<String> = context.dataStore.data.map {
        it[Keys.PERSONALITY] ?: DEFAULT_PERSONALITY
    }
    val ttsSpeakerId: Flow<Int> = context.dataStore.data.map {
        it[Keys.TTS_SPEAKER_ID] ?: 1
    }
    val ttsVolume: Flow<Float> = context.dataStore.data.map {
        (it[Keys.TTS_VOLUME] ?: TTS_VOLUME_DEFAULT).coerceIn(TTS_VOLUME_MIN, TTS_VOLUME_MAX)
    }
    val ttsSpeed: Flow<Float> = context.dataStore.data.map {
        (it[Keys.TTS_SPEED] ?: TTS_SPEED_DEFAULT).coerceIn(TTS_SPEED_MIN, TTS_SPEED_MAX)
    }
    /**
     * Legacy long-term memory paragraph (pre-RAG). Kept for one-shot migration into the
     * vector store; new writes should go through [com.kawaiipet.app.memory.rag.RagMemoryStore].
     */
    val memoryParagraph: Flow<String> = context.dataStore.data.map {
        LlmPromptDefaults.clampMemoryParagraph(it[Keys.MEMORY_PARAGRAPH].orEmpty())
    }

    suspend fun getPetName(): String = petName.first()

    suspend fun getPersonalityPrompt(): String =
        personalityPrompt.first().ifBlank { DEFAULT_PERSONALITY }

    suspend fun getSttModelId(): String {
        val id = sttModelId.first()
        return migrateSttModelId(id)
    }

    suspend fun getTtsModelId(): String = ttsModelId.first()

    suspend fun getTtsSpeakerId(): Int = ttsSpeakerId.first()

    suspend fun getTtsVolume(): Float = ttsVolume.first()

    suspend fun getTtsSpeed(): Float = ttsSpeed.first()

    suspend fun getMemoryParagraph(): String =
        LlmPromptDefaults.clampMemoryParagraph(memoryParagraph.first())

    suspend fun setMemoryParagraph(value: String) {
        context.dataStore.edit {
            it[Keys.MEMORY_PARAGRAPH] = LlmPromptDefaults.clampMemoryParagraph(value)
        }
    }

    suspend fun setPetName(value: String) {
        context.dataStore.edit { it[Keys.PET_NAME] = value }
    }

    suspend fun setSttModelId(value: String) {
        context.dataStore.edit { it[Keys.STT_MODEL_ID] = value }
    }

    suspend fun setTtsModelId(value: String) {
        context.dataStore.edit { it[Keys.TTS_MODEL_ID] = value }
    }

    suspend fun setPersonalityPrompt(value: String) {
        context.dataStore.edit {
            it[Keys.PERSONALITY] = value
            it[Keys.PERSONALITY_VERSION] = LlmPromptDefaults.PERSONALITY_DEFAULT_VERSION
        }
    }

    /** Refresh shipped pet personality when the built-in default bumps. */
    suspend fun migratePersonalityDefaultIfNeeded() {
        context.dataStore.edit { prefs ->
            val version = prefs[Keys.PERSONALITY_VERSION] ?: 0
            if (version < LlmPromptDefaults.PERSONALITY_DEFAULT_VERSION) {
                prefs[Keys.PERSONALITY] = DEFAULT_PERSONALITY
                prefs[Keys.PERSONALITY_VERSION] = LlmPromptDefaults.PERSONALITY_DEFAULT_VERSION
            }
        }
    }

    /** Point prefs at the current default STT when an older tiny model id is stored. */
    suspend fun migrateSttModelIfNeeded() {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.STT_MODEL_ID] ?: return@edit
            val migrated = migrateSttModelId(current)
            if (migrated != current) {
                prefs[Keys.STT_MODEL_ID] = migrated
            }
        }
    }

    suspend fun setTtsSpeakerId(value: Int) {
        context.dataStore.edit { it[Keys.TTS_SPEAKER_ID] = value }
    }

    suspend fun setTtsVolume(value: Float) {
        context.dataStore.edit {
            it[Keys.TTS_VOLUME] = value.coerceIn(TTS_VOLUME_MIN, TTS_VOLUME_MAX)
            it[Keys.TTS_VOLUME_VERSION] = TTS_VOLUME_PREF_VERSION
        }
    }

    suspend fun setTtsSpeed(value: Float) {
        context.dataStore.edit {
            it[Keys.TTS_SPEED] = value.coerceIn(TTS_SPEED_MIN, TTS_SPEED_MAX)
        }
    }

    /** Cap volume at 100% and reset boosted defaults from older builds. */
    suspend fun migrateTtsVolumeDefaultIfNeeded() {
        context.dataStore.edit { prefs ->
            val version = prefs[Keys.TTS_VOLUME_VERSION] ?: 0
            if (version >= TTS_VOLUME_PREF_VERSION) return@edit
            val current = prefs[Keys.TTS_VOLUME]
            prefs[Keys.TTS_VOLUME] = when {
                current == null || current > TTS_VOLUME_MAX -> TTS_VOLUME_DEFAULT
                else -> current.coerceIn(TTS_VOLUME_MIN, TTS_VOLUME_MAX)
            }
            prefs[Keys.TTS_VOLUME_VERSION] = TTS_VOLUME_PREF_VERSION
        }
    }


    private object Keys {
        val PET_NAME = stringPreferencesKey("pet_name")
        val STT_MODEL_ID = stringPreferencesKey("stt_model_id")
        val TTS_MODEL_ID = stringPreferencesKey("tts_model_id")
        val PERSONALITY = stringPreferencesKey("personality")
        val PERSONALITY_VERSION = intPreferencesKey("personality_version")
        val TTS_SPEAKER_ID = intPreferencesKey("tts_speaker_id")
        val TTS_VOLUME = floatPreferencesKey("tts_volume")
        val TTS_VOLUME_VERSION = intPreferencesKey("tts_volume_version")
        val TTS_SPEED = floatPreferencesKey("tts_speed")
        val MEMORY_PARAGRAPH = stringPreferencesKey("memory_paragraph")
    }

    companion object {
        val DEFAULT_PERSONALITY: String = LlmPromptDefaults.DEFAULT_PERSONALITY

        /** Soft gain applied to TTS PCM (1.0 = full / max). */
        const val TTS_VOLUME_MIN = 0.2f
        const val TTS_VOLUME_MAX = 1.0f
        const val TTS_VOLUME_DEFAULT = 1.0f
        private const val TTS_VOLUME_PREF_VERSION = 2

        /** Pet voice rate for Sherpa synth + AudioTrack playback (both use this). */
        const val TTS_SPEED_MIN = 0.8f
        const val TTS_SPEED_MAX = 1.5f
        const val TTS_SPEED_DEFAULT = 1.1f

        private val LEGACY_TTS_MODEL_IDS = setOf(
            "piper-en_US-amy-medium",
            "kitten-nano-en-v0_1-fp16",
            "kitten-nano-en-v0_2-fp16",
        )

        private val LEGACY_STT_MODEL_IDS = setOf(
            "moonshine-tiny-en-quantized",
            "sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27",
            // Previous default — upgrade to Moonshine base for better English accuracy.
            "sherpa-onnx-nemo-ctc-en-conformer-small",
        )

        private fun migrateSttModelId(id: String): String =
            if (id in LEGACY_STT_MODEL_IDS) DefaultVoiceModels.STT_MODEL_ID else id

        private fun migrateTtsModelId(id: String): String =
            if (id in LEGACY_TTS_MODEL_IDS) DefaultVoiceModels.TTS_MODEL_ID else id
    }
}
