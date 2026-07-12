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
        it[Keys.STT_MODEL_ID] ?: DefaultVoiceModels.STT_MODEL_ID
    }
    val ttsModelId: Flow<String> = context.dataStore.data.map {
        it[Keys.TTS_MODEL_ID] ?: DefaultVoiceModels.TTS_MODEL_ID
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

    suspend fun getPetName(): String = petName.first()

    suspend fun getPersonalityPrompt(): String =
        personalityPrompt.first().ifBlank { DEFAULT_PERSONALITY }

    suspend fun getSttModelId(): String = sttModelId.first()

    suspend fun getTtsModelId(): String = ttsModelId.first()

    suspend fun getTtsSpeakerId(): Int = ttsSpeakerId.first()

    suspend fun getTtsVolume(): Float = ttsVolume.first()

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
        context.dataStore.edit { it[Keys.PERSONALITY] = value }
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
        val TTS_SPEAKER_ID = intPreferencesKey("tts_speaker_id")
        val TTS_VOLUME = floatPreferencesKey("tts_volume")
    }

    companion object {
        val DEFAULT_PERSONALITY: String = LlmPromptDefaults.DEFAULT_PERSONALITY
    }
}
