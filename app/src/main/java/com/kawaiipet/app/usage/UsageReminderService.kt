package com.kawaiipet.app.usage

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kawaiipet.app.KawaiiPetApplication
import com.kawaiipet.app.R
import com.kawaiipet.app.overlay.PetOverlayService
import com.kawaiipet.app.ui.MainActivity
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
 * Play-compliant monitor: [UsageStatsManager] polling from a special-use FGS
 * (same pattern as screen-time apps). Accessibility is not used.
 *
 * Android requires a visible notification for any FGS. The channel is silent/low.
 */
@AndroidEntryPoint
class UsageReminderService : Service() {

    @Inject lateinit var preferenceManager: PreferenceManager
    @Inject lateinit var usageStatsTracker: UsageStatsTracker

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollJob: Job? = null
    /** Per-package last nudge so each watched app re-arms independently. */
    private val lastNudgeAtByPackage = mutableMapOf<String, Long>()
    @Volatile private var screenOn = true

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> screenOn = false
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> screenOn = true
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        screenOn = isScreenInteractive()
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        startAsForeground()
        pollJob = scope.launch { pollLoop() }
        Log.i(TAG, "Usage reminder monitor started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        runCatching { unregisterReceiver(screenReceiver) }
        Log.i(TAG, "Usage reminder monitor stopped")
        super.onDestroy()
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            val watchedInForeground = runCatching { tick() }
                .onFailure { e -> Log.w(TAG, "poll tick failed", e) }
                .getOrDefault(false)
            delay(if (watchedInForeground) POLL_ACTIVE_MS else POLL_IDLE_MS)
        }
    }

    /** @return true if a watched app is currently in the foreground (screen on). */
    private suspend fun tick(): Boolean {
        val enabled = preferenceManager.getUsageReminderEnabled()
        val targets = preferenceManager.getUsageReminderTargets()
        val limitMinutes = preferenceManager.getUsageReminderLimitMinutes()
        if (!enabled || targets.isEmpty()) {
            stopSelf()
            return false
        }
        if (!PermissionHelper.hasUsageAccessPermission(this)) {
            Log.w(TAG, "Usage access revoked — stopping monitor")
            preferenceManager.setUsageReminderEnabled(false)
            stopSelf()
            return false
        }
        if (!screenOn || !isScreenInteractive()) {
            screenOn = false
            return false
        }

        val session = usageStatsTracker.currentForegroundSession() ?: return false
        val watched = targets.associateBy { it.packageName }
        val app = watched[session.packageName] ?: return false

        val now = System.currentTimeMillis()
        lastNudgeAtByPackage.keys.retainAll(watched.keys)

        val limitMs = limitMinutes * 60_000L
        val continuousMs = session.continuousMs(now)
        if (continuousMs < limitMs) return true
        val lastNudge = lastNudgeAtByPackage[app.packageName] ?: 0L
        if (now - lastNudge < limitMs) return true

        lastNudgeAtByPackage[app.packageName] = now
        Log.i(
            TAG,
            "Usage limit hit for ${app.packageName} continuous=${continuousMs / 1000}s limit=${limitMinutes}m",
        )
        fireNudge(
            appLabel = app.label.ifBlank { app.packageName },
            continuousMinutes = ((continuousMs + 30_000L) / 60_000L).toInt().coerceAtLeast(1),
        )
        return true
    }

    private fun isScreenInteractive(): Boolean {
        val pm = getSystemService(PowerManager::class.java) ?: return true
        return pm.isInteractive
    }

    private fun fireNudge(appLabel: String, continuousMinutes: Int) {
        if (!PermissionHelper.hasOverlayPermission(this)) {
            Log.w(TAG, "Cannot show pet — overlay permission missing")
            return
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
        return NotificationCompat.Builder(this, KawaiiPetApplication.USAGE_MONITOR_CHANNEL_ID)
            .setContentTitle(getString(R.string.usage_reminder_notification_title))
            .setContentText(getString(R.string.usage_reminder_notification_text))
            .setSmallIcon(R.drawable.ic_pet_notification)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(tapIntent)
            .build()
    }

    companion object {
        private const val TAG = "UsageReminderService"
        private const val NOTIFICATION_ID = 2
        /** Watched app is open — tight enough for a minute-scale limit. */
        private const val POLL_ACTIVE_MS = 15_000L
        /** Nothing to watch — UsageStats lag is ~1s; 45s is plenty and cheaper. */
        private const val POLL_IDLE_MS = 45_000L

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
