package com.kawaiipet.app.util

import android.content.Context
import com.kawaiipet.app.audio.DefaultVoiceModels
import com.kawaiipet.app.llm.LlmPromptDefaults
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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
        val id = it[Keys.TTS_MODEL_ID] ?: DefaultVoiceModels.TTS_MODEL_ID
        if (id == LEGACY_TTS_MODEL_ID) DefaultVoiceModels.TTS_MODEL_ID else id
    }
    val personalityPrompt: Flow<String> = context.dataStore.data.map {
        it[Keys.PERSONALITY] ?: DEFAULT_PERSONALITY
    }
    val ttsSpeakerId: Flow<Int> = context.dataStore.data.map {
        it[Keys.TTS_SPEAKER_ID] ?: 1
    }
    val ttsVolume: Flow<Float> = context.dataStore.data.map {
        it[Keys.TTS_VOLUME] ?: 1f
    }
    /** Long-term pet memory as one short paragraph. */
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

    suspend fun getTtsModelId(): String {
        val id = ttsModelId.first()
        return if (id == LEGACY_TTS_MODEL_ID) DefaultVoiceModels.TTS_MODEL_ID else id
    }

    suspend fun getTtsSpeakerId(): Int = ttsSpeakerId.first()

    suspend fun getTtsVolume(): Float = ttsVolume.first()

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
        context.dataStore.edit { it[Keys.TTS_VOLUME] = value.coerceIn(0f, 1f) }
    }

    private object Keys {
        val PET_NAME = stringPreferencesKey("pet_name")
        val STT_MODEL_ID = stringPreferencesKey("stt_model_id")
        val TTS_MODEL_ID = stringPreferencesKey("tts_model_id")
        val PERSONALITY = stringPreferencesKey("personality")
        val PERSONALITY_VERSION = intPreferencesKey("personality_version")
        val TTS_SPEAKER_ID = intPreferencesKey("tts_speaker_id")
        val TTS_VOLUME = floatPreferencesKey("tts_volume")
        val MEMORY_PARAGRAPH = stringPreferencesKey("memory_paragraph")
    }

    companion object {
        val DEFAULT_PERSONALITY: String = LlmPromptDefaults.DEFAULT_PERSONALITY
        private const val LEGACY_TTS_MODEL_ID = "piper-en_US-amy-medium"

        private val LEGACY_STT_MODEL_IDS = setOf(
            "moonshine-tiny-en-quantized",
            "sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27",
            "sherpa-onnx-moonshine-base-en-quantized-2026-02-27",
        )

        private fun migrateSttModelId(id: String): String =
            if (id in LEGACY_STT_MODEL_IDS) DefaultVoiceModels.STT_MODEL_ID else id
    }
}
