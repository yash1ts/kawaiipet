package com.kawaiipet.app.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves [PetToolIntent] enums against installed packages and launches them.
 * With a [query], opens the app's search / play deep link when possible.
 */
@Singleton
class AppLauncher @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
) {
    @Volatile
    private var cachedAvailable: List<PetToolIntent>? = null

    /** Tools that can actually be opened on this device right now. */
    fun availableTools(): List<PetToolIntent> {
        cachedAvailable?.let { return it }
        val resolved = PetToolIntent.entries.filter { resolveLaunchIntent(it) != null }
        cachedAvailable = resolved
        return resolved
    }

    fun isAvailable(tool: PetToolIntent): Boolean = availableTools().contains(tool)

    fun launch(call: PetToolCall): Boolean = launch(call.intent, call.query)

    /**
     * Launch [tool] in a new task (safe from overlay / service context).
     * @param query optional search / song / place text for deep-link play
     * @return true if an activity was started
     */
    fun launch(tool: PetToolIntent, query: String? = null): Boolean {
        val intent = if (!query.isNullOrBlank()) {
            resolveSearchIntent(tool, query.trim()) ?: resolveLaunchIntent(tool)
        } else {
            resolveLaunchIntent(tool)
        } ?: run {
            Log.w(TAG, "No resolvable intent for ${tool.id} query=${query?.take(40)}")
            return false
        }
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            Log.i(TAG, "Launched ${tool.id} query=${query?.take(60)}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch ${tool.id}", e)
            false
        }
    }

    fun launchById(id: String, query: String? = null): Boolean {
        val tool = PetToolIntent.fromId(id) ?: return false
        return launch(tool, query)
    }

    fun resolveSearchIntent(tool: PetToolIntent, query: String): Intent? {
        val encoded = Uri.encode(query)
        val uri = when (tool) {
            PetToolIntent.YOUTUBEMUSIC ->
                Uri.parse("https://music.youtube.com/search?q=$encoded")
            PetToolIntent.SPOTIFY ->
                Uri.parse("https://open.spotify.com/search/${encoded.replace("+", "%20")}")
            PetToolIntent.YOUTUBE ->
                Uri.parse("https://www.youtube.com/results?search_query=$encoded")
            PetToolIntent.CHROME, PetToolIntent.BROWSER ->
                Uri.parse("https://www.google.com/search?q=$encoded")
            PetToolIntent.MAPS ->
                Uri.parse("geo:0,0?q=$encoded")
            PetToolIntent.PLAYSTORE ->
                Uri.parse("https://play.google.com/store/search?q=$encoded&c=apps")
            else -> return null
        }
        val pm = appContext.packageManager
        // Prefer the target app package so Chrome doesn't steal music/youtube links.
        for (pkg in tool.packageNames) {
            if (!isPackageInstalled(pm, pkg)) continue
            val packaged = Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)
            if (canResolve(pm, packaged)) return packaged
        }
        val open = Intent(Intent.ACTION_VIEW, uri)
        return open.takeIf { canResolve(pm, it) }
    }

    fun resolveLaunchIntent(tool: PetToolIntent): Intent? {
        val pm = appContext.packageManager

        for (pkg in tool.packageNames) {
            if (!isPackageInstalled(pm, pkg)) continue
            val launch = pm.getLaunchIntentForPackage(pkg)
            if (launch != null) return launch
        }

        val action = tool.fallbackAction ?: return null
        val intent = when (action) {
            Intent.ACTION_MAIN -> {
                tool.packageNames.firstNotNullOfOrNull { pkg ->
                    if (isPackageInstalled(pm, pkg)) pm.getLaunchIntentForPackage(pkg) else null
                } ?: categoryAppIntent(tool)
            }
            else -> {
                Intent(action).apply {
                    tool.dataUri?.let { data = Uri.parse(it) }
                }
            }
        } ?: return null

        return intent.takeIf { canResolve(pm, it) }
    }

    /** API 33+ category shortcuts when package names aren't present. */
    private fun categoryAppIntent(tool: PetToolIntent): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val category = when (tool) {
            PetToolIntent.MESSAGES -> Intent.CATEGORY_APP_MESSAGING
            PetToolIntent.GMAIL -> Intent.CATEGORY_APP_EMAIL
            PetToolIntent.CALENDAR -> Intent.CATEGORY_APP_CALENDAR
            PetToolIntent.MAPS -> Intent.CATEGORY_APP_MAPS
            PetToolIntent.BROWSER, PetToolIntent.CHROME -> Intent.CATEGORY_APP_BROWSER
            PetToolIntent.PHOTOS -> Intent.CATEGORY_APP_GALLERY
            else -> return null
        }
        return Intent(Intent.ACTION_MAIN).addCategory(category)
    }

    private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean =
        try {
            pm.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    private fun canResolve(pm: PackageManager, intent: Intent): Boolean =
        intent.resolveActivity(pm) != null

    companion object {
        private const val TAG = "AppLauncher"
    }
}
