package com.kawaiipet.app.util

import android.content.Context
import com.kawaiipet.app.audio.DefaultVoiceModels
import com.kawaiipet.app.audio.VadEngineConfig
import com.kawaiipet.app.llm.LlmPromptDefaults
import com.kawaiipet.app.usage.UsageReminderApp
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
import org.json.JSONArray
import org.json.JSONObject

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
    val vadThreshold: Flow<Float> = context.dataStore.data.map {
        (it[Keys.VAD_THRESHOLD] ?: VadEngineConfig.THRESHOLD)
            .coerceIn(VadEngineConfig.THRESHOLD_MIN, VadEngineConfig.THRESHOLD_MAX)
    }
    val vadMinSilenceSec: Flow<Float> = context.dataStore.data.map {
        resolveVadMinSilenceSec(it[Keys.VAD_MIN_SILENCE_SEC])
    }
    val repetitionPenalty: Flow<Float> = context.dataStore.data.map {
        (it[Keys.REPETITION_PENALTY] ?: LlmPromptDefaults.REPETITION_PENALTY)
            .coerceIn(LlmPromptDefaults.REPETITION_PENALTY_MIN, LlmPromptDefaults.REPETITION_PENALTY_MAX)
    }
    val presencePenalty: Flow<Float> = context.dataStore.data.map {
        (it[Keys.PRESENCE_PENALTY] ?: LlmPromptDefaults.PRESENCE_PENALTY)
            .coerceIn(LlmPromptDefaults.PRESENCE_PENALTY_MIN, LlmPromptDefaults.PRESENCE_PENALTY_MAX)
    }
    val frequencyPenalty: Flow<Float> = context.dataStore.data.map {
        (it[Keys.FREQUENCY_PENALTY] ?: LlmPromptDefaults.FREQUENCY_PENALTY)
            .coerceIn(LlmPromptDefaults.FREQUENCY_PENALTY_MIN, LlmPromptDefaults.FREQUENCY_PENALTY_MAX)
    }
    val noRepeatNgramSize: Flow<Int> = context.dataStore.data.map {
        (it[Keys.NO_REPEAT_NGRAM_SIZE] ?: LlmPromptDefaults.NO_REPEAT_NGRAM_SIZE)
            .coerceIn(LlmPromptDefaults.NO_REPEAT_NGRAM_SIZE_MIN, LlmPromptDefaults.NO_REPEAT_NGRAM_SIZE_MAX)
    }
    /**
     * Legacy long-term memory paragraph (pre-RAG). Kept for one-shot migration into the
     * vector store; new writes should go through [com.kawaiipet.app.memory.rag.RagMemoryStore].
     */
    val memoryParagraph: Flow<String> = context.dataStore.data.map {
        LlmPromptDefaults.clampMemoryParagraph(it[Keys.MEMORY_PARAGRAPH].orEmpty())
    }

    /** Soft nudge when the user stays in a distracting app too long. */
    val usageReminderEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.USAGE_REMINDER_ENABLED] ?: false
    }
    val usageReminderTargets: Flow<List<UsageReminderApp>> = context.dataStore.data.map { prefs ->
        parseUsageReminderTargets(prefs)
    }
    val usageReminderLimitMinutes: Flow<Int> = context.dataStore.data.map {
        (it[Keys.USAGE_REMINDER_LIMIT_MINUTES] ?: USAGE_REMINDER_LIMIT_DEFAULT_MIN)
            .coerceIn(USAGE_REMINDER_LIMIT_MIN_MIN, USAGE_REMINDER_LIMIT_MAX_MIN)
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

    suspend fun getVadThreshold(): Float = vadThreshold.first()
        .coerceIn(VadEngineConfig.THRESHOLD_MIN, VadEngineConfig.THRESHOLD_MAX)

    suspend fun getVadMinSilenceSec(): Float = vadMinSilenceSec.first()
        .coerceIn(VadEngineConfig.MIN_SILENCE_SEC_MIN, VadEngineConfig.MIN_SILENCE_SEC_MAX)

    suspend fun getRepetitionPenalty(): Float = repetitionPenalty.first()
        .coerceIn(LlmPromptDefaults.REPETITION_PENALTY_MIN, LlmPromptDefaults.REPETITION_PENALTY_MAX)

    suspend fun getPresencePenalty(): Float = presencePenalty.first()
        .coerceIn(LlmPromptDefaults.PRESENCE_PENALTY_MIN, LlmPromptDefaults.PRESENCE_PENALTY_MAX)

    suspend fun getFrequencyPenalty(): Float = frequencyPenalty.first()
        .coerceIn(LlmPromptDefaults.FREQUENCY_PENALTY_MIN, LlmPromptDefaults.FREQUENCY_PENALTY_MAX)

    suspend fun getNoRepeatNgramSize(): Int = noRepeatNgramSize.first()
        .coerceIn(LlmPromptDefaults.NO_REPEAT_NGRAM_SIZE_MIN, LlmPromptDefaults.NO_REPEAT_NGRAM_SIZE_MAX)

    suspend fun getMemoryParagraph(): String =
        LlmPromptDefaults.clampMemoryParagraph(memoryParagraph.first())

    suspend fun getUsageReminderEnabled(): Boolean = usageReminderEnabled.first()

    suspend fun getUsageReminderTargets(): List<UsageReminderApp> = usageReminderTargets.first()

    suspend fun getUsageReminderLimitMinutes(): Int =
        usageReminderLimitMinutes.first()
            .coerceIn(USAGE_REMINDER_LIMIT_MIN_MIN, USAGE_REMINDER_LIMIT_MAX_MIN)

    suspend fun setMemoryParagraph(value: String) {
        context.dataStore.edit {
            it[Keys.MEMORY_PARAGRAPH] = LlmPromptDefaults.clampMemoryParagraph(value)
        }
    }

    suspend fun setUsageReminderEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.USAGE_REMINDER_ENABLED] = enabled }
    }

    suspend fun setUsageReminderTargets(targets: List<UsageReminderApp>) {
        val capped = targets
            .filter { it.packageName.isNotBlank() }
            .distinctBy { it.packageName }
            .take(USAGE_REMINDER_MAX_APPS)
        context.dataStore.edit { prefs ->
            prefs[Keys.USAGE_REMINDER_TARGETS_JSON] = encodeUsageReminderTargets(capped)
            // Drop legacy single-target keys once the list format is written.
            prefs.remove(Keys.USAGE_REMINDER_PACKAGE)
            prefs.remove(Keys.USAGE_REMINDER_APP_LABEL)
        }
    }

    suspend fun setUsageReminderLimitMinutes(minutes: Int) {
        context.dataStore.edit {
            it[Keys.USAGE_REMINDER_LIMIT_MINUTES] =
                minutes.coerceIn(USAGE_REMINDER_LIMIT_MIN_MIN, USAGE_REMINDER_LIMIT_MAX_MIN)
        }
    }

    suspend fun clearUsageReminderTargets() {
        context.dataStore.edit {
            it[Keys.USAGE_REMINDER_TARGETS_JSON] = "[]"
            it.remove(Keys.USAGE_REMINDER_PACKAGE)
            it.remove(Keys.USAGE_REMINDER_APP_LABEL)
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

    /** Pick up LFM2.5 card sampler penalties when the shipped defaults bump. */
    suspend fun migrateLlmSamplerIfNeeded() {
        context.dataStore.edit { prefs ->
            val version = prefs[Keys.LLM_SAMPLER_VERSION] ?: 0
            if (version < LLM_SAMPLER_PREF_VERSION) {
                prefs[Keys.REPETITION_PENALTY] = LlmPromptDefaults.REPETITION_PENALTY
                prefs[Keys.PRESENCE_PENALTY] = LlmPromptDefaults.PRESENCE_PENALTY
                prefs[Keys.FREQUENCY_PENALTY] = LlmPromptDefaults.FREQUENCY_PENALTY
                prefs[Keys.NO_REPEAT_NGRAM_SIZE] = LlmPromptDefaults.NO_REPEAT_NGRAM_SIZE
                prefs[Keys.LLM_SAMPLER_VERSION] = LLM_SAMPLER_PREF_VERSION
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

    suspend fun setVadThreshold(value: Float) {
        context.dataStore.edit {
            it[Keys.VAD_THRESHOLD] = value.coerceIn(
                VadEngineConfig.THRESHOLD_MIN,
                VadEngineConfig.THRESHOLD_MAX,
            )
        }
    }

    suspend fun setVadMinSilenceSec(value: Float) {
        context.dataStore.edit {
            it[Keys.VAD_MIN_SILENCE_SEC] = value.coerceIn(
                VadEngineConfig.MIN_SILENCE_SEC_MIN,
                VadEngineConfig.MIN_SILENCE_SEC_MAX,
            )
        }
    }

    suspend fun setRepetitionPenalty(value: Float) {
        context.dataStore.edit {
            it[Keys.REPETITION_PENALTY] = value.coerceIn(
                LlmPromptDefaults.REPETITION_PENALTY_MIN,
                LlmPromptDefaults.REPETITION_PENALTY_MAX,
            )
        }
    }

    suspend fun setPresencePenalty(value: Float) {
        context.dataStore.edit {
            it[Keys.PRESENCE_PENALTY] = value.coerceIn(
                LlmPromptDefaults.PRESENCE_PENALTY_MIN,
                LlmPromptDefaults.PRESENCE_PENALTY_MAX,
            )
        }
    }

    suspend fun setFrequencyPenalty(value: Float) {
        context.dataStore.edit {
            it[Keys.FREQUENCY_PENALTY] = value.coerceIn(
                LlmPromptDefaults.FREQUENCY_PENALTY_MIN,
                LlmPromptDefaults.FREQUENCY_PENALTY_MAX,
            )
        }
    }

    suspend fun setNoRepeatNgramSize(value: Int) {
        context.dataStore.edit {
            it[Keys.NO_REPEAT_NGRAM_SIZE] = value.coerceIn(
                LlmPromptDefaults.NO_REPEAT_NGRAM_SIZE_MIN,
                LlmPromptDefaults.NO_REPEAT_NGRAM_SIZE_MAX,
            )
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

    private fun parseUsageReminderTargets(prefs: Preferences): List<UsageReminderApp> {
        val json = prefs[Keys.USAGE_REMINDER_TARGETS_JSON]
        if (!json.isNullOrBlank()) {
            return decodeUsageReminderTargets(json)
        }
        // Migrate legacy single-app prefs.
        val pkg = prefs[Keys.USAGE_REMINDER_PACKAGE].orEmpty()
        if (pkg.isBlank()) return emptyList()
        val label = prefs[Keys.USAGE_REMINDER_APP_LABEL].orEmpty().ifBlank { pkg }
        return listOf(UsageReminderApp(packageName = pkg, label = label))
    }

    private fun encodeUsageReminderTargets(targets: List<UsageReminderApp>): String {
        val arr = JSONArray()
        for (app in targets) {
            arr.put(
                JSONObject()
                    .put("packageName", app.packageName)
                    .put("label", app.label),
            )
        }
        return arr.toString()
    }

    private fun decodeUsageReminderTargets(json: String): List<UsageReminderApp> {
        return runCatching {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    if (size >= USAGE_REMINDER_MAX_APPS) break
                    val obj = arr.optJSONObject(i) ?: continue
                    val pkg = obj.optString("packageName").trim()
                    if (pkg.isBlank()) continue
                    val label = obj.optString("label").trim().ifBlank { pkg }
                    add(UsageReminderApp(packageName = pkg, label = label))
                }
            }.distinctBy { it.packageName }
        }.getOrDefault(emptyList())
    }

    private fun resolveVadMinSilenceSec(stored: Float?): Float {
        val raw = stored ?: return VadEngineConfig.MIN_SILENCE_SEC
        // 0.20 / 0.40 / 0.80 defaults still cut mid-sentence; pick up the new wait.
        if (raw <= SHORT_VAD_SILENCE_SEC) return VadEngineConfig.MIN_SILENCE_SEC
        return raw.coerceIn(VadEngineConfig.MIN_SILENCE_SEC_MIN, VadEngineConfig.MIN_SILENCE_SEC_MAX)
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
        val VAD_THRESHOLD = floatPreferencesKey("vad_threshold")
        val VAD_MIN_SILENCE_SEC = floatPreferencesKey("vad_min_silence_sec")
        val REPETITION_PENALTY = floatPreferencesKey("llm_repetition_penalty")
        val PRESENCE_PENALTY = floatPreferencesKey("llm_presence_penalty")
        val FREQUENCY_PENALTY = floatPreferencesKey("llm_frequency_penalty")
        val NO_REPEAT_NGRAM_SIZE = intPreferencesKey("llm_no_repeat_ngram_size")
        val LLM_SAMPLER_VERSION = intPreferencesKey("llm_sampler_version")
        val MEMORY_PARAGRAPH = stringPreferencesKey("memory_paragraph")
        val USAGE_REMINDER_ENABLED = booleanPreferencesKey("usage_reminder_enabled")
        val USAGE_REMINDER_TARGETS_JSON = stringPreferencesKey("usage_reminder_targets_json")
        /** Legacy single-target keys — read for migration only. */
        val USAGE_REMINDER_PACKAGE = stringPreferencesKey("usage_reminder_package")
        val USAGE_REMINDER_APP_LABEL = stringPreferencesKey("usage_reminder_app_label")
        val USAGE_REMINDER_LIMIT_MINUTES = intPreferencesKey("usage_reminder_limit_minutes")
    }

    companion object {
        val DEFAULT_PERSONALITY: String = LlmPromptDefaults.DEFAULT_PERSONALITY

        /** Soft gain applied to TTS PCM (1.0 = full / max). */
        const val TTS_VOLUME_MIN = 0.2f
        const val TTS_VOLUME_MAX = 1.0f
        const val TTS_VOLUME_DEFAULT = 1.0f
        private const val TTS_VOLUME_PREF_VERSION = 2

        /** Bump when shipped LFM sampler penalties change. */
        private const val LLM_SAMPLER_PREF_VERSION = 1

        /** Pet voice rate for Sherpa synth + AudioTrack playback (both use this). */
        const val TTS_SPEED_MIN = 0.8f
        const val TTS_SPEED_MAX = 1.5f
        const val TTS_SPEED_DEFAULT = 1.1f

        const val USAGE_REMINDER_LIMIT_MIN_MIN = 1
        const val USAGE_REMINDER_LIMIT_MAX_MIN = 120
        const val USAGE_REMINDER_LIMIT_DEFAULT_MIN = 30
        const val USAGE_REMINDER_MAX_APPS = 5

        /** Stored silence at or below this is an older aggressive default and is migrated. */
        private const val SHORT_VAD_SILENCE_SEC = 1.21f

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
