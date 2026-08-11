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

        /**
         * Build a [PetToolCall] from the user's words, including a play/search query when present.
         * SmolLM2-360M often skips tool tags — this keeps song names working.
         *
         * Examples:
         * - "Play country roads on youtube music" → youtubemusic + "country roads"
         * - "Open spotify" → spotify, no query
         */
        fun detectToolCallFromUserUtterance(text: String): PetToolCall? {
            val n = text.lowercase()
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (n.isEmpty()) return null
            if (!TOOL_VERB_REGEX.containsMatchIn(n)) return null

            var intent: PetToolIntent? = null
            for ((pattern, tool) in UTTERANCE_PATTERNS) {
                if (pattern.containsMatchIn(n)) {
                    intent = tool
                    break
                }
            }
            val query = extractPlayQuery(n)?.take(80)
            // Bare "play <song>" with no app → default to YouTube Music search.
            if (intent == null && query != null) {
                intent = YOUTUBEMUSIC
            }
            intent ?: return null
            return PetToolCall(intent = intent, query = query)
        }

        /** @deprecated Prefer [detectToolCallFromUserUtterance]. */
        fun detectFromUserUtterance(text: String): PetToolIntent? =
            detectToolCallFromUserUtterance(text)?.intent

        private fun extractPlayQuery(normalized: String): String? {
            // "play QUERY on youtube music|spotify|youtube"
            Regex(
                """\bplay\s+(.+?)\s+on\s+(?:youtube\s*music|yt\s*music|youtubemusic|spotify|youtube|chrome)\b""",
            ).find(normalized)?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { it.isNotEmpty() && it !in setOf("a", "the", "some", "my") }
                ?.let { return it }

            // "open youtube music and play QUERY"
            Regex(
                """\b(?:open|launch|start).+?\band\s+play\s+(.+)$""",
            ).find(normalized)?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }

            // "play QUERY" — drop trailing app / "into music" / STT junk
            Regex("""\bplay\s+(.+)$""").find(normalized)?.groupValues?.getOrNull(1)?.trim()
                ?.replace(
                    Regex(
                        """\s+on\s+(?:youtube\s*music|yt\s*music|youtubemusic|spotify|youtube|chrome)\s*$""",
                    ),
                    "",
                )
                ?.replace(Regex("""\s+(into|in|on)\s+(?:youtube\s*)?music\s*$"""), "")
                ?.replace(Regex("""\s*,?\s*native music\s*$"""), "")
                ?.replace(Regex("""\s+on\s+your\s+\w+\s*$"""), "")
                ?.replace(Regex("""^an?\s+"""), "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it !in setOf("a", "the", "some", "my", "music") }
                ?.let { return it }

            return null
        }

        private val TOOL_VERB_REGEX =
            Regex("\\b(open|launch|start|play|pull up|bring up|go to|open up|fire up)\\b")

        private val UTTERANCE_PATTERNS: List<Pair<Regex, PetToolIntent>> = listOf(
            Regex("youtube\\s*music|yt\\s*music|youtubemusic") to YOUTUBEMUSIC,
            Regex("\\bspotify\\b") to SPOTIFY,
            Regex("\\b(google\\s*)?chrome\\b") to CHROME,
            Regex("\\byoutube\\b|\\byt\\b") to YOUTUBE,
            Regex("\\bplay\\s*store\\b|\\bplaystore\\b") to PLAYSTORE,
            Regex("\\bgmail\\b") to GMAIL,
            Regex("\\bmaps?\\b|\\bwaze\\b") to MAPS,
            Regex("\\bphotos?\\b|\\bgallery\\b") to PHOTOS,
            Regex("\\bcamera\\b") to CAMERA,
            Regex("\\bmessages?\\b|\\btexts?\\b|\\bsms\\b") to MESSAGES,
            Regex("\\bphone\\b|\\bdialer\\b") to PHONE,
            Regex("\\bsettings?\\b") to SETTINGS,
            Regex("\\bwifi\\b|wi fi") to WIFI,
            Regex("\\bbluetooth\\b") to BLUETOOTH,
            Regex("\\bclock\\b|\\balarm\\b") to CLOCK,
            Regex("\\bcalculator\\b") to CALCULATOR,
            Regex("\\bcalendar\\b") to CALENDAR,
            Regex("\\bbrowser\\b") to BROWSER,
        )
    }
}
