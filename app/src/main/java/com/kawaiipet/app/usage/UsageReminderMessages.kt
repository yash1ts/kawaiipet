package com.kawaiipet.app.usage

object UsageReminderMessages {

    fun next(
        appLabel: String,
        minutes: Int,
        seed: Long = System.currentTimeMillis(),
    ): String {
        val app = appLabel.trim().ifBlank { "that app" }
        val mins = minutes.coerceAtLeast(1)
        val lines = listOf(
            "You've been on $app for about $mins minutes — want a quick break with me?",
            "Hey — that's about $mins minutes on $app. Fancy a chat, or should I put on some music?",
            "Psst. You've spent a lot of time on $app (about $mins minutes). Soft reset with me?",
            "You've been in $app for about $mins minutes — taking a breather? I'm here if you want to talk.",
            "Hey friend — about $mins minutes on $app is plenty. Chat with me or I can play a song.",
        )
        return lines[(seed % lines.size).toInt().coerceAtLeast(0)]
    }
}
