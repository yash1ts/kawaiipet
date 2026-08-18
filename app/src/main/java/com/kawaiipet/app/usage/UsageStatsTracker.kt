package com.kawaiipet.app.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class ForegroundSession(
    val packageName: String,
    val sinceMs: Long,
) {
    fun continuousMs(nowMs: Long): Long = (nowMs - sinceMs).coerceAtLeast(0L)
}

/**
 * Reads [UsageStatsManager] events — the Play-compliant way to learn which app
 * is in the foreground (no AccessibilityService).
 */
@Singleton
class UsageStatsTracker @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
) {
    /**
     * App currently in the foreground, with when this uninterrupted session began.
     * Null if the screen is off, usage access is missing, or only system UI is showing.
     */
    fun currentForegroundSession(nowMs: Long = System.currentTimeMillis()): ForegroundSession? {
        val usm = usageStatsManager() ?: return null
        val events = usm.queryEvents(nowMs - CONTINUOUS_LOOKBACK_MS, nowMs)
        val event = UsageEvents.Event()
        val ignored = ignoredForegroundPackages()

        var currentFg: String? = null
        var fgSince: Long? = null
        var screenInteractive = true

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when {
                isScreenNonInteractive(event) -> {
                    screenInteractive = false
                    currentFg = null
                    fgSince = null
                }
                isScreenInteractive(event) -> {
                    screenInteractive = true
                }
                isMoveToForeground(event) -> {
                    if (!screenInteractive) continue
                    val pkg = event.packageName ?: continue
                    if (pkg in ignored) continue
                    currentFg = pkg
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

        if (!screenInteractive) return null
        val pkg = currentFg ?: return null
        val since = fgSince ?: return null
        return ForegroundSession(packageName = pkg, sinceMs = since)
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

    private fun ignoredForegroundPackages(): Set<String> = setOf(
        appContext.packageName,
        "android",
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.google.android.packageinstaller",
    )

    private fun usageStatsManager(): UsageStatsManager? =
        appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    private fun isScreenInteractive(event: UsageEvents.Event): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            event.eventType == UsageEvents.Event.SCREEN_INTERACTIVE

    private fun isScreenNonInteractive(event: UsageEvents.Event): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            event.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE

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
        /** Long enough to cover the max 120-minute limit plus a long session. */
        private const val CONTINUOUS_LOOKBACK_MS = 4L * 60L * 60L * 1000L
        private const val RECENT_LOOKBACK_MS = 14L * 24L * 60L * 60L * 1000L
    }
}
