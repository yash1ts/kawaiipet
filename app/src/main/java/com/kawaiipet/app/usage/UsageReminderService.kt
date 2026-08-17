package com.kawaiipet.app.usage

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kawaiipet.app.KawaiiPetApplication
import com.kawaiipet.app.R
import com.kawaiipet.app.overlay.PetOverlayService
import com.kawaiipet.app.ui.MainActivity
import com.kawaiipet.app.util.Analytics
import com.kawaiipet.app.util.PermissionHelper
import com.kawaiipet.app.util.PreferenceManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lightweight FGS that polls continuous foreground time for watched apps (up to 5).
 * When a limit is hit, starts the pet overlay and asks it to speak a soft nudge.
 * Ignoring the pet and staying in that app re-arms after another full continuous limit.
 */
@AndroidEntryPoint
class UsageReminderService : Service() {

    @Inject lateinit var preferenceManager: PreferenceManager
    @Inject lateinit var usageStatsTracker: UsageStatsTracker

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollJob: Job? = null
    /** Per-package last nudge so each watched app re-arms independently. */
    private val lastNudgeAtByPackage = mutableMapOf<String, Long>()

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        pollJob = scope.launch { pollLoop() }
        Log.i(TAG, "Usage reminder monitor started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        Log.i(TAG, "Usage reminder monitor stopped")
        super.onDestroy()
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            runCatching { tick() }
                .onFailure { e -> Log.w(TAG, "poll tick failed", e) }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun tick() {
        val enabled = preferenceManager.getUsageReminderEnabled()
        val targets = preferenceManager.getUsageReminderTargets()
        val limitMinutes = preferenceManager.getUsageReminderLimitMinutes()
        if (!enabled || targets.isEmpty()) {
            stopSelf()
            return
        }
        if (!PermissionHelper.hasUsageAccessPermission(this)) {
            Log.w(TAG, "Usage access revoked — stopping monitor")
            preferenceManager.setUsageReminderEnabled(false)
            stopSelf()
            return
        }

        val limitMs = limitMinutes * 60_000L
        val now = System.currentTimeMillis()
        val watched = targets.associateBy { it.packageName }
        lastNudgeAtByPackage.keys.retainAll(watched.keys)

        for (app in targets) {
            val continuousMs = usageStatsTracker.continuousForegroundMs(app.packageName, now)
            if (continuousMs < limitMs) continue
            val lastNudge = lastNudgeAtByPackage[app.packageName] ?: 0L
            if (now - lastNudge < limitMs) continue

            lastNudgeAtByPackage[app.packageName] = now
            Log.i(
                TAG,
                "Usage limit hit for ${app.packageName} continuous=${continuousMs / 1000}s limit=${limitMinutes}m",
            )
            Analytics.capture(
                event = "usage reminder triggered",
                properties = mapOf(
                    "package" to app.packageName,
                    "limit_minutes" to limitMinutes,
                    "continuous_seconds" to (continuousMs / 1000L),
                ),
            )
            fireNudge(
                appLabel = app.label.ifBlank { app.packageName },
                continuousMinutes = ((continuousMs + 30_000L) / 60_000L).toInt().coerceAtLeast(1),
            )
            // One nudge per poll tick — avoid stacking overlays if several somehow match.
            break
        }
    }

    private fun fireNudge(appLabel: String, continuousMinutes: Int) {
        if (!PermissionHelper.hasOverlayPermission(this)) {
            Log.w(TAG, "Cannot show pet — overlay permission missing")
            return
        }
        // Leave the watched app so the pet can grab attention on Home.
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
            )
        }.onFailure { e ->
            Log.w(TAG, "Could not go Home before usage nudge", e)
        }
        val message = UsageReminderMessages.next(appLabel = appLabel, minutes = continuousMinutes)
        val intent = Intent(this, PetOverlayService::class.java).apply {
            action = PetOverlayService.ACTION_USAGE_NUDGE
            putExtra(PetOverlayService.EXTRA_NUDGE_MESSAGE, message)
            putExtra(PetOverlayService.EXTRA_NUDGE_APP_LABEL, appLabel)
            putExtra(PetOverlayService.EXTRA_NUDGE_MINUTES, continuousMinutes)
        }
        runCatching {
            ContextCompat.startForegroundService(this, intent)
        }.onFailure { e ->
            Log.e(TAG, "Failed to start pet for usage nudge", e)
        }
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, 0)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, KawaiiPetApplication.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.usage_reminder_notification_title))
            .setContentText(getString(R.string.usage_reminder_notification_text))
            .setSmallIcon(R.drawable.ic_pet_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "UsageReminderService"
        private const val NOTIFICATION_ID = 2
        private const val POLL_INTERVAL_MS = 15_000L

        fun start(context: Context) {
            val app = context.applicationContext
            ContextCompat.startForegroundService(
                app,
                Intent(app, UsageReminderService::class.java),
            )
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            app.stopService(Intent(app, UsageReminderService::class.java))
        }

        /** Start or stop the monitor to match current prefs + usage-access grant. */
        suspend fun syncWithPrefs(context: Context, prefs: PreferenceManager) {
            val shouldRun = prefs.getUsageReminderEnabled() &&
                prefs.getUsageReminderTargets().isNotEmpty() &&
                PermissionHelper.hasUsageAccessPermission(context)
            if (shouldRun) start(context) else stop(context)
        }
    }
}
