package com.kawaiipet.app.llm

/**
 * Prompt + sampler defaults for on-device SmolLM2-135M-Instruct via LiteRT-LM.
 *
 * Memory / recent-chat are single sanitized paragraphs (not fact lists).
 * Engine context is sized for long-term + short-term notes + reply.
 */
object LlmPromptDefaults {

    /**
     * Debug: pure SmolLM — no pet personality, no chat history, no reply filters.
     * Flip to false to reconnect the normal pet pipeline (code stays in place).
     */
    const val PURE_SMOLLM_DEBUG = false

    const val DEFAULT_PERSONALITY =
        "A sharp, witty living companion — warm and playful, never babyish or dim. " +
            "Curious, observant, and good at explaining things clearly. Speaks like a clever friend."

    /** Bump when [DEFAULT_PERSONALITY] changes so stored prefs refresh. */
    const val PERSONALITY_DEFAULT_VERSION = 4

    const val MAX_REPLY_WORDS = 24
    const val MAX_CHARS_PER_TURN = 120

    /** Long-term memory as one paragraph. */
    const val MAX_MEMORY_WORDS = 500
    const val MAX_MEMORY_CHARS = 3200

    /** Recent chat as one sanitized paragraph (not raw turn list). */
    const val MAX_SHORT_TERM_WORDS = 200
    const val MAX_SHORT_TERM_CHARS = 1300

    const val DIDNT_CATCH_REPLY = "Sorry, I didn't catch that."
    const val GREETING_FALLBACK = "Hey — good to see you. What's on your mind?"
    const val CAPABILITY_FALLBACK =
        "I can talk with you, answer questions, and keep you company."
    const val CURIOUS_FALLBACK = "Interesting — tell me more about that."

    const val SAMPLER_TOP_K = 40
    const val SAMPLER_TOP_P = 0.9
    const val SAMPLER_TEMPERATURE = 0.65
    const val MAX_OUTPUT_TOKENS = 72

    /** Utility tasks (memory / short-term consolidate) — cooler, longer output. */
    const val UTILITY_TOP_K = 20
    const val UTILITY_TOP_P = 0.85
    const val UTILITY_TEMPERATURE = 0.3
    const val UTILITY_MAX_OUTPUT_TOKENS = 768

    fun buildSystemPrompt(
        petName: String,
        personality: String,
        memoryParagraph: String,
        shortTermParagraph: String = "",
    ): String {
        val name = petName.trim().ifEmpty { "Mochi" }
        val vibe = personality.trim().ifEmpty { DEFAULT_PERSONALITY }.take(220)
        val memory = clampMemoryParagraph(memoryParagraph)
        val recent = clampShortTermParagraph(shortTermParagraph)
        return buildString {
            append("You are $name, an intelligent companion creature — not a dumb pet, not a chatbot. ")
            append("Personality: $vibe ")
            if (memory.isNotBlank()) {
                append("What you remember about your friend: ")
                append(memory)
                append(' ')
            }
            if (recent.isNotBlank()) {
                append("Recent conversation: ")
                append(recent)
                append(' ')
            }
            append("Answer thoughtfully in one or two short sentences. ")
            append("Be clear and smart; if they ask what something is, give a real explanation. ")
            append("Stay warm and witty. Never baby-talk. Never act like customer support. ")
            append("End with [happy] or [thinking].")
        }
    }

    fun clampMemoryParagraph(text: String): String =
        clampParagraph(text, MAX_MEMORY_WORDS, MAX_MEMORY_CHARS)

    fun clampShortTermParagraph(text: String): String =
        clampParagraph(text, MAX_SHORT_TERM_WORDS, MAX_SHORT_TERM_CHARS)

    /**
     * Sanitize then cap a memory/chat paragraph. Empty / placeholder / junk → "".
     * Uses simple string ops (no fragile regex) — Android ICU rejects some escapes.
     */
    fun sanitizeParagraph(text: String): String {
        var s = collapseWhitespace(text.replace('\u0000', ' '))
        if (s.isEmpty()) return ""

        // Emotion tags like [happy]
        for (tag in EMOTION_TAGS) {
            s = s.replace(tag, " ", ignoreCase = true)
        }

        // Leading labels: "Memory:", "Recent conversation:", etc.
        for (prefix in LABEL_PREFIXES) {
            if (s.startsWith(prefix, ignoreCase = true)) {
                s = s.substring(prefix.length).trimStart(' ', ':').trim()
            }
        }

        // Inline role labels: "Friend: ", "User: "
        for (role in ROLE_LABELS) {
            s = s.replace("$role:", " ", ignoreCase = true)
            s = s.replace("$role :", " ", ignoreCase = true)
        }

        s = s.replace("*", " ")
            .replace("_", " ")
            .replace("`", " ")
            .replace("#", " ")
            .replace("•", " ")
            .replace("\"", " ")
            .replace('\'', ' ')
            .replace("«", " ")
            .replace("»", " ")
            .replace("()", " ")
            .replace("[]", " ")
            .replace("{}", " ")
        s = collapseWhitespace(s)
            .trim(',', ';', ':', '-', '.', '!', '?')
            .let(::collapseWhitespace)

        if (s.isEmpty()) return ""

        val lower = s.lowercase()
        if (EMPTY_PLACEHOLDERS.any { lower == it || lower.startsWith("$it.") || lower.startsWith("$it,") }) {
            return ""
        }
        val letters = s.count { it.isLetterOrDigit() }
        if (letters < 3) return ""

        // Drop obvious instruction / support dumps that contaminate memory.
        if (lower.contains("here are") || lower.contains("i can help") ||
            lower.contains("the user") || lower.contains("feel free to ask")
        ) {
            return ""
        }

        return collapseRepeatedPunct(s).trimEnd(',', ';', ':', '-')
    }

    private fun collapseWhitespace(text: String): String {
        val sb = StringBuilder(text.length)
        var prevSpace = true
        for (ch in text) {
            val space = ch.isWhitespace()
            if (space) {
                if (!prevSpace) sb.append(' ')
            } else {
                sb.append(ch)
            }
            prevSpace = space
        }
        return sb.toString().trim()
    }

    private fun collapseRepeatedPunct(text: String): String {
        val sb = StringBuilder(text.length)
        var prev: Char? = null
        for (ch in text) {
            if (ch == ',' || ch == ';' || ch == ':') {
                if (prev == ch) continue
            }
            sb.append(ch)
            prev = ch
        }
        return collapseWhitespace(sb.toString())
    }

    private fun clampParagraph(text: String, maxWords: Int, maxChars: Int): String {
        val cleaned = sanitizeParagraph(text)
        if (cleaned.isEmpty()) return ""
        val words = cleaned.split(' ').filter { it.isNotEmpty() }
        val byWords = if (words.size <= maxWords) {
            cleaned
        } else {
            words.take(maxWords).joinToString(" ")
        }
        return byWords.take(maxChars).trimEnd(',', ';', ':', '-', '.', ' ')
            .let { sanitizeParagraph(it) }
    }

    fun isCannedFallback(text: String): Boolean {
        val t = text.trim()
        return t == DIDNT_CATCH_REPLY ||
            t == GREETING_FALLBACK ||
            t == CAPABILITY_FALLBACK ||
            t == CURIOUS_FALLBACK
    }

    private val EMPTY_PLACEHOLDERS = setOf(
        "none", "null", "nil", "n/a", "na", "empty", "(empty)", "[empty]",
        "nothing", "no memory", "no data", "unknown", "...", "…", "-", "--",
    )

    private val EMOTION_TAGS = listOf(
        "[happy]", "[sad]", "[angry]", "[thinking]", "[idle]",
        "[listening]", "[talking]", "[sleeping]",
    )

    private val LABEL_PREFIXES = listOf(
        "updated memory:", "recent conversation:", "short-term:", "short term:",
        "memory:", "output:", "note:", "summary:",
    )

    private val ROLE_LABELS = listOf(
        "user", "assistant", "friend", "pet", "human", "mochi",
    )
}
