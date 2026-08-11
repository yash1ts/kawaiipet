package com.kawaiipet.app.tools

/**
 * One tool invocation from the model: open an app, optionally with a search/play query.
 *
 * Tags the model emits:
 * - `[open:chrome]`
 * - `[play:youtubemusic:The Mandalorian]`
 * - `[open:spotify:Bohemian Rhapsody]` (query form also accepted on open)
 */
data class PetToolCall(
    val intent: PetToolIntent,
    /** Song / search text. Null or blank → just launch the app. */
    val query: String? = null,
) {
    val hasQuery: Boolean get() = !query.isNullOrBlank()
}
