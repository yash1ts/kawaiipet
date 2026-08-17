package com.kawaiipet.app.tools

/**
 * Apps / system screens the pet can open via `[open:<id>]` tool tags.
 * Keep [id] single-token (no underscores) so speech sanitizers don't mangle them.
 */
enum class PetToolIntent(
    /** Token used inside `[open:<id>]`. */
    val id: String,
    /** Short label for the system prompt. */
    val label: String,
    /** Preferred package names, tried in order before [fallbackAction]. */
    val packageNames: List<String> = emptyList(),
    /**
     * Fallback Intent action when no package resolves
     * (e.g. [android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA]).
     */
    val fallbackAction: String? = null,
    /** Optional data URI for VIEW / DIAL style intents. */
    val dataUri: String? = null,
) {
    CAMERA(
        id = "camera",
        label = "Camera",
        packageNames = listOf(
            "com.google.android.GoogleCamera",
            "com.android.camera2",
            "com.android.camera",
            "com.samsung.android.camera",
        ),
        fallbackAction = android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA,
    ),
    PHOTOS(
        id = "photos",
        label = "Photos",
        packageNames = listOf(
            "com.google.android.apps.photos",
            "com.sec.android.gallery3d",
            "com.android.gallery3d",
            "com.miui.gallery",
        ),
        fallbackAction = android.content.Intent.ACTION_MAIN,
    ),
    MAPS(
        id = "maps",
        label = "Maps",
        packageNames = listOf(
            "com.google.android.apps.maps",
            "com.waze",
        ),
        fallbackAction = android.content.Intent.ACTION_VIEW,
        dataUri = "geo:0,0?q=",
    ),
    YOUTUBE(
        id = "youtube",
        label = "YouTube",
        packageNames = listOf("com.google.android.youtube"),
        fallbackAction = android.content.Intent.ACTION_VIEW,
        dataUri = "https://www.youtube.com",
    ),
    YOUTUBEMUSIC(
        id = "youtubemusic",
        label = "YouTube Music",
        packageNames = listOf("com.google.android.apps.youtube.music"),
        fallbackAction = android.content.Intent.ACTION_VIEW,
        dataUri = "https://music.youtube.com",
    ),
    SPOTIFY(
        id = "spotify",
        label = "Spotify",
        packageNames = listOf("com.spotify.music"),
        fallbackAction = android.content.Intent.ACTION_VIEW,
        dataUri = "https://open.spotify.com",
    ),
    CHROME(
        id = "chrome",
        label = "Chrome",
        packageNames = listOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
        ),
        fallbackAction = android.content.Intent.ACTION_VIEW,
        dataUri = "https://www.google.com",
    ),
    BROWSER(
        id = "browser",
        label = "Browser",
        packageNames = listOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.microsoft.emmx",
            "com.brave.browser",
            "com.opera.browser",
            "com.android.browser",
        ),
        fallbackAction = android.content.Intent.ACTION_VIEW,
        dataUri = "https://www.google.com",
    ),
    SETTINGS(
        id = "settings",
        label = "Settings",
        packageNames = listOf("com.android.settings"),
        fallbackAction = android.provider.Settings.ACTION_SETTINGS,
    ),
    WIFI(
        id = "wifi",
        label = "Wi-Fi settings",
        fallbackAction = android.provider.Settings.ACTION_WIFI_SETTINGS,
    ),
    BLUETOOTH(
        id = "bluetooth",
        label = "Bluetooth settings",
        fallbackAction = android.provider.Settings.ACTION_BLUETOOTH_SETTINGS,
    ),
    PHONE(
        id = "phone",
        label = "Phone",
        packageNames = listOf(
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.android.dialer",
        ),
        fallbackAction = android.content.Intent.ACTION_DIAL,
    ),
    MESSAGES(
        id = "messages",
        label = "Messages",
        packageNames = listOf(
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.mms",
        ),
        fallbackAction = android.content.Intent.ACTION_MAIN,
    ),
    CLOCK(
        id = "clock",
        label = "Clock",
        packageNames = listOf(
            "com.google.android.deskclock",
            "com.sec.android.app.clockpackage",
            "com.android.deskclock",
        ),
        fallbackAction = android.provider.AlarmClock.ACTION_SHOW_ALARMS,
    ),
    CALCULATOR(
        id = "calculator",
        label = "Calculator",
        packageNames = listOf(
            "com.google.android.calculator",
            "com.sec.android.app.popupcalculator",
            "com.android.calculator2",
            "com.miui.calculator",
        ),
    ),
    CALENDAR(
        id = "calendar",
        label = "Calendar",
        packageNames = listOf(
            "com.google.android.calendar",
            "com.samsung.android.calendar",
            "com.android.calendar",
        ),
        fallbackAction = android.content.Intent.ACTION_MAIN,
    ),
    GMAIL(
        id = "gmail",
        label = "Gmail",
        packageNames = listOf(
            "com.google.android.gm",
            "com.samsung.android.email.provider",
            "com.android.email",
        ),
        fallbackAction = android.content.Intent.ACTION_MAIN,
    ),
    PLAYSTORE(
        id = "playstore",
        label = "Play Store",
        packageNames = listOf("com.android.vending"),
        fallbackAction = android.content.Intent.ACTION_VIEW,
        dataUri = "https://play.google.com/store",
    ),
    ;

    companion object {
        private val BY_ID = entries.associateBy { it.id.lowercase() }

        /** Extra tokens the model may emit for the same app. */
        private val ALIASES = mapOf(
            "yt" to YOUTUBE,
            "ytmusic" to YOUTUBEMUSIC,
            "googlechrome" to CHROME,
        )

        fun fromId(raw: String): PetToolIntent? {
            val key = raw.trim().lowercase()
            return BY_ID[key] ?: ALIASES[key]
        }

        /** Compact list for the system prompt, e.g. `camera, photos, maps`. */
        fun promptIdList(available: Collection<PetToolIntent> = entries): String =
            available.joinToString(", ") { it.id }
    }
}
