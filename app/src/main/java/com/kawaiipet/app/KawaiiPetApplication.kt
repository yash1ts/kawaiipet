package com.kawaiipet.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.kawaiipet.app.BuildConfig
import com.kawaiipet.app.audio.VoiceEngineWarmup
import com.kawaiipet.app.llm.LlmEngineWarmup
import com.kawaiipet.app.memory.MemoryRepository
import com.kawaiipet.app.memory.ShortTermMemory
import com.kawaiipet.app.util.PreferenceManager
import com.kawaiipet.app.usage.UsageReminderService
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class KawaiiPetApplication : Application() {

    @Inject lateinit var voiceEngineWarmup: VoiceEngineWarmup
    @Inject lateinit var llmEngineWarmup: LlmEngineWarmup
    @Inject lateinit var memoryRepository: MemoryRepository
    @Inject lateinit var shortTermMemory: ShortTermMemory
    @Inject lateinit var preferenceManager: PreferenceManager

    override fun onCreate() {
        super.onCreate()
        val apiKey = BuildConfig.POSTHOG_API_KEY.trim()
        if (apiKey.isNotEmpty()) {
            val host = BuildConfig.POSTHOG_HOST.trim().ifEmpty { "https://us.i.posthog.com" }
            PostHogAndroid.setup(this, PostHogAndroidConfig(apiKey = apiKey, host = host))
        }
        voiceEngineWarmup.startWarmup()
        llmEngineWarmup.startWarmup("app_start")
        createNotificationChannel()
        // Drop known contaminated memory lines that made SmolLM loop.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                preferenceManager.migratePersonalityDefaultIfNeeded()
                preferenceManager.migrateLlmSamplerIfNeeded()
                preferenceManager.migrateSttModelIfNeeded()
                preferenceManager.migrateTtsVolumeDefaultIfNeeded()
                memoryRepository.purgeContaminatedFacts()
                shortTermMemory.clear()
                UsageReminderService.syncWithPrefs(this@KawaiiPetApplication, preferenceManager)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "kawaiipet_overlay"
    }
}
