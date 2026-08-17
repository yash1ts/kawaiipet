package com.kawaiipet.app.usage

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LaunchableApp(
    val packageName: String,
    val label: String,
    /** Epoch ms of last use when usage access is available; 0 if unknown. */
    val lastUsedMs: Long = 0L,
)

@Singleton
class InstalledAppsRepository @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val usageStatsTracker: UsageStatsTracker,
) {
    /**
     * Launchable apps sorted by recent usage (most recent first), then by label.
     * Requires usage access for meaningful ordering; otherwise falls back to A–Z.
     */
    suspend fun loadLaunchableApps(): List<LaunchableApp> = withContext(Dispatchers.IO) {
        val pm = appContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        val self = appContext.packageName
        val lastUsed = usageStatsTracker.lastUsedMsByPackage()
        resolveInfos
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == self) return@mapNotNull null
                val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
                    .ifBlank { pkg }
                LaunchableApp(
                    packageName = pkg,
                    label = label,
                    lastUsedMs = lastUsed[pkg] ?: 0L,
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(
                compareByDescending<LaunchableApp> { it.lastUsedMs }
                    .thenBy { it.label.lowercase() },
            )
    }
}
