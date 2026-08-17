package com.kawaiipet.app.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads [UsageStatsManager] events to estimate how long [packageName] has been
 * continuously in the foreground (uninterrupted by another app).
 */
@Singleton
class UsageStatsTracker @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
) {
    /**
     * Milliseconds the [packageName] has been continuously foreground ending at [nowMs].
     * Returns 0 if it is not currently foreground or usage access is missing.
     */
    fun continuousForegroundMs(packageName: String, nowMs: Long = System.currentTimeMillis()): Long {
        if (packageName.isBlank()) return 0L
        val usm = usageStatsManager() ?: return 0L

        // Look back far enough to cover the longest supported limit + buffer.
        val begin = nowMs - CONTINUOUS_LOOKBACK_MS
        val events = usm.queryEvents(begin, nowMs)
        val event = UsageEvents.Event()

        var currentFg: String? = null
        var fgSince: Long? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when {
                isMoveToForeground(event) -> {
                    currentFg = event.packageName
                    fgSince = event.timeStamp
                }
                isMoveToBackground(event) -> {
                    if (event.packageName == currentFg) {
                        currentFg = null
                        fgSince = null
                    }
                }
            }
        }

        if (currentFg != packageName || fgSince == null) return 0L
        return (nowMs - fgSince).coerceAtLeast(0L)
    }

    /**
     * Last time each package was used (best-effort), for sorting app pickers.
     * Empty map if usage access is missing.
     */
    fun lastUsedMsByPackage(
        lookbackMs: Long = RECENT_LOOKBACK_MS,
        nowMs: Long = System.currentTimeMillis(),
    ): Map<String, Long> {
        val usm = usageStatsManager() ?: return emptyMap()
        val begin = nowMs - lookbackMs
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, begin, nowMs)
            ?: return emptyMap()
        if (stats.isEmpty()) return emptyMap()
        val out = HashMap<String, Long>(stats.size)
        for (stat in stats) {
            val pkg = stat.packageName ?: continue
            val last = stat.lastTimeUsed
            if (last <= 0L) continue
            val prev = out[pkg]
            if (prev == null || last > prev) out[pkg] = last
        }
        return out
    }

    private fun usageStatsManager(): UsageStatsManager? =
        appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    private fun isMoveToForeground(event: UsageEvents.Event): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) return true
        }
        @Suppress("DEPRECATION")
        return event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
    }

    private fun isMoveToBackground(event: UsageEvents.Event): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (event.eventType == UsageEvents.Event.ACTIVITY_PAUSED) return true
        }
        @Suppress("DEPRECATION")
        return event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND
    }

    companion object {
        private const val CONTINUOUS_LOOKBACK_MS = 4L * 60L * 60L * 1000L // 4h
        private const val RECENT_LOOKBACK_MS = 14L * 24L * 60L * 60L * 1000L // 14d
    }
}
