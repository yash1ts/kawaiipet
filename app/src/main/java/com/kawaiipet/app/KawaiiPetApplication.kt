package com.kawaiipet.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.kawaiipet.app.audio.VoiceEngineWarmup
import com.kawaiipet.app.llm.LlmEngineWarmup
import com.kawaiipet.app.memory.MemoryRepository
import com.kawaiipet.app.memory.ShortTermMemory
import com.kawaiipet.app.util.PreferenceManager
import com.kawaiipet.app.usage.UsageReminderService
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
        voiceEngineWarmup.startWarmup()
        llmEngineWarmup.startWarmup("app_start")
        createNotificationChannels()
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

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                USAGE_MONITOR_CHANNEL_ID,
                getString(R.string.usage_reminder_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.usage_reminder_notification_channel_description)
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "kawaiipet_overlay"
        const val USAGE_MONITOR_CHANNEL_ID = "kawaiipet_usage_monitor"
    }
}
