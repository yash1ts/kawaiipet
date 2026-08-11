package com.kawaiipet.app.llm

/**
 * Prompt + sampler defaults for on-device SmolLM2-135M-Instruct via LiteRT-LM.
 *
 * Long-term memory is one sanitized paragraph; short-term is the raw recent message list.
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

    /** Long-term memory as one paragraph (sized for ~768-token utility rewrite). */
    const val MAX_MEMORY_WORDS = 250
    const val MAX_MEMORY_CHARS = 1500

    /** Keep the last N chat messages as short-term history (full text, not summarized). */
    const val MAX_SHORT_TERM_MESSAGES = 8

    const val DIDNT_CATCH_REPLY = "Sorry, I didn't catch that."
    const val GREETING_FALLBACK = "Hey — good to see you. What's on your mind?"
    const val CAPABILITY_FALLBACK =
        "I can talk with you, answer questions, and keep you company."
    const val CURIOUS_FALLBACK = "Interesting — tell me more about that."

    const val SAMPLER_TOP_K = 40
    const val SAMPLER_TOP_P = 0.9
    const val SAMPLER_TEMPERATURE = 0.65
    const val MAX_OUTPUT_TOKENS = 72

    /** Utility tasks (long-term memory consolidate) — cooler sampler. */
    const val UTILITY_TOP_K = 20
    const val UTILITY_TOP_P = 0.85
    const val UTILITY_TEMPERATURE = 0.3
    const val UTILITY_MAX_OUTPUT_TOKENS = 768

    fun buildSystemPrompt(
        petName: String,
        personality: String,
        memoryParagraph: String,
    ): String {
        val name = petName.trim().ifEmpty { "Mochi" }
        val vibe = personality.trim().ifEmpty { DEFAULT_PERSONALITY }.take(220)
        val memory = clampMemoryParagraph(memoryParagraph)
        return buildString {
            append("You are $name, an intelligent companion creature — not a dumb pet, not a chatbot. ")
            append("Personality: $vibe ")
            if (memory.isNotBlank()) {
                append("What you remember about your friend: ")
                append(memory)
                append(' ')
            }
            append("Answer thoughtfully in one or two short sentences. ")
            append("Be clear and smart; if they ask what something is, give a real explanation. ")
            append("Stay warm and witty. Never baby-talk. Never act like customer support. ")
            append("End with [happy] or [thinking].")
        }
    }

    fun clampMemoryParagraph(text: String): String =
        clampParagraph(sanitizeMemoryParagraph(text), MAX_MEMORY_WORDS, MAX_MEMORY_CHARS)

    /**
     * True when the friend likely stated something durable about themselves.
     * False for definitions, trivia, greetings — those make the model "explain" into memory.
     */
    fun looksLikeMemorableUserTurn(text: String): Boolean {
        val t = text.trim()
        if (t.length < 4) return false
        val lower = t.lowercase()
        if (isCannedFallback(t)) return false
        if (TRIVIAL_MEMORY_SKIP.any { lower == it || lower.startsWith("$it ") }) return false
        if (lower.startsWith("what is") || lower.startsWith("what's") ||
            lower.startsWith("whats ") || lower.startsWith("who is") ||
            lower.startsWith("how do") || lower.startsWith("how does") ||
            lower.startsWith("why is") || lower.startsWith("why do") ||
            lower.startsWith("explain") || lower.startsWith("define")
        ) {
            return false
        }
        // Pad so "I like…" matches at the start.
        val padded = " $lower "
        return PERSONAL_FACT_HINTS.any { padded.contains(it) }
    }

    /** Memory-specific sanitize: reject explanation-style dumps and repeated lines. */
    fun sanitizeMemoryParagraph(text: String): String {
        val base = sanitizeParagraph(text)
        if (base.isEmpty()) return ""
        val lower = base.lowercase()
        if (EXPLANATION_LEAK_MARKERS.any { lower.contains(it) }) return ""
        return dedupeMemoryParagraph(base)
    }

    /** True when two memory strings are the same after normalizing punctuation/case. */
    fun isSameMemory(a: String, b: String): Boolean {
        val na = normalizeMemoryKey(a)
        val nb = normalizeMemoryKey(b)
        if (na.isEmpty() && nb.isEmpty()) return true
        if (na.isEmpty() || nb.isEmpty()) return false
        return na == nb
    }

    /**
     * Collapse "same line twice" and repeated clauses the small model often echoes
     * when merging old memory + new fact.
     */
    fun dedupeMemoryParagraph(text: String): String {
        var s = collapseWhitespace(text)
        if (s.isEmpty()) return ""
        s = collapseWholeRepeats(s)

        val clauses = splitMemoryClauses(s)
        if (clauses.isEmpty()) return ""

        val kept = mutableListOf<String>()
        val seen = LinkedHashSet<String>()
        for (clause in clauses) {
            val key = normalizeMemoryKey(clause)
            if (key.length < 3) continue
            if (!seen.add(key)) continue
            // Drop a short clause already covered by a longer kept clause.
            if (kept.any { normalizeMemoryKey(it).contains(key) && normalizeMemoryKey(it) != key }) {
                continue
            }
            // Drop earlier shorter clauses that this one fully covers.
            val toRemove = kept.filter {
                val k = normalizeMemoryKey(it)
                k != key && key.contains(k) && k.length >= 3
            }
            kept.removeAll(toRemove.toSet())
            seen.removeAll(toRemove.map { normalizeMemoryKey(it) }.toSet())
            kept.add(clause.trim().trimEnd('.', '!', '?', ',', ';'))
            seen.add(key)
        }
        if (kept.isEmpty()) return ""
        return collapseWhitespace(kept.joinToString(". ") + ".")
            .trimEnd('.')
            .let { if (it.isEmpty()) "" else "$it." }
            .let(::collapseWhitespace)
    }

    fun normalizeMemoryKey(text: String): String {
        val sb = StringBuilder(text.length)
        var prevSpace = true
        for (ch in text.lowercase()) {
            if (ch.isLetterOrDigit()) {
                sb.append(ch)
                prevSpace = false
            } else if (!prevSpace) {
                sb.append(' ')
                prevSpace = true
            }
        }
        return sb.toString().trim()
    }

    private fun splitMemoryClauses(text: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (ch in text) {
            cur.append(ch)
            if (ch == '.' || ch == '!' || ch == '?' || ch == ';' || ch == '\n') {
                val piece = collapseWhitespace(cur.toString().trimEnd('.', '!', '?', ';'))
                if (piece.isNotEmpty()) out.add(piece)
                cur.clear()
            }
        }
        val tail = collapseWhitespace(cur.toString())
        if (tail.isNotEmpty()) out.add(tail)
        return out
    }

    /** If the whole paragraph is the same word-block repeated (A+A, A+A+A), keep one A. */
    private fun collapseWholeRepeats(text: String): String {
        var s = collapseWhitespace(text)
        repeat(6) {
            val words = s.split(' ').filter { it.isNotEmpty() }
            if (words.size < 4) return s
            var collapsed: String? = null
            for (unitWords in (words.size / 2) downTo 2) {
                if (words.size % unitWords != 0) continue
                val times = words.size / unitWords
                if (times < 2) continue
                val unit = words.take(unitWords)
                val ok = (1 until times).all { i ->
                    words.subList(i * unitWords, (i + 1) * unitWords) == unit
                }
                if (ok) {
                    collapsed = unit.joinToString(" ")
                    break
                }
            }
            if (collapsed == null) return s
            s = collapsed
        }
        return s
    }

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

        // Strip placeholder / prompt-leak tokens anywhere (never show "(empty)" etc.).
        for (token in STRIP_TOKENS) {
            s = s.replace(token, " ", ignoreCase = true)
        }

        // Leading labels: "Memory:", "Was:", "Now:", etc.
        for (prefix in LABEL_PREFIXES) {
            if (s.startsWith(prefix, ignoreCase = true)) {
                s = s.substring(prefix.length).trimStart(' ', ':').trim()
            }
        }

        // Inline role / scaffold labels
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

        // Reject prompt echoes / instruction dumps — not real memory.
        if (PROMPT_LEAK_MARKERS.any { lower.contains(it) }) {
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
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return ""
        val words = cleaned.split(' ').filter { it.isNotEmpty() }
        val byWords = if (words.size <= maxWords) {
            cleaned
        } else {
            words.take(maxWords).joinToString(" ")
        }
        return byWords.take(maxChars).trimEnd(',', ';', ':', '-', '.', ' ')
            .let { sanitizeMemoryParagraph(it) }
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
        "was", "said", "now",
    )

    /** Removed wherever they appear so UI never shows scaffold junk. */
    private val STRIP_TOKENS = listOf(
        "(empty)", "[empty]", "{empty}",
        "current memory:", "current recent conversation:",
        "latest exchange:", "updated memory:", "recent conversation:",
        "write the updated paragraph now", "drop empty/placeholder text",
        "reply exactly: none", "or none", "else: none",
        "invent nothing", "no labels or lists",
        "plain paragraph", "one plain paragraph",
    )

    private val PROMPT_LEAK_MARKERS = listOf(
        "here are", "i can help", "the user", "feel free to ask",
        "max ${MAX_MEMORY_WORDS} words",
        "rewrite personal facts", "rewrite what was just",
        "latest exchange", "current memory", "drop empty",
        "write the updated", "intelligent companion",
        "do not invent", "if nothing worth",
        "compress lasting facts", "copy facts briefly",
    )

    /** Reject memory that reads like a definition / lecture, not a fact note. */
    private val EXPLANATION_LEAK_MARKERS = listOf(
        "this means", "that means", "refers to", "is when", "is a type of",
        "is a kind of", "in other words", "essentially", "basically means",
        "can be defined", "is defined as", "for example", "such as a",
        "typically", "in simple terms", "to put it simply", "it is important",
        "you should know", "let me explain", "the concept of",
    )

    private val PERSONAL_FACT_HINTS = listOf(
        " i ", " i'm ", " im ", " i am ", " my ", " mine ", " me ",
        " i like ", " i love ", " i hate ", " i prefer ", " i want ",
        " i live ", " i work ", " i study ", " i have ", " i had ",
        " my name ", " call me ", " i'm called ", " i am called ",
        " my friend ", " my mom ", " my dad ", " my wife ", " my husband ",
        " my dog ", " my cat ", " my job ", " my school ",
    )

    private val TRIVIAL_MEMORY_SKIP = listOf(
        "hi", "hello", "hey", "yo", "thanks", "thank you", "ok", "okay",
        "bye", "good night", "good morning", "good evening",
    )

    private val EMOTION_TAGS = listOf(
        "[happy]", "[sad]", "[angry]", "[thinking]", "[idle]",
        "[listening]", "[talking]", "[sleeping]",
    )

    private val LABEL_PREFIXES = listOf(
        "updated memory:", "recent conversation:", "short-term:", "short term:",
        "current memory:", "memory:", "output:", "note:", "summary:",
        "was:", "said:", "now:", "short:",
    )

    private val ROLE_LABELS = listOf(
        "user", "assistant", "friend", "pet", "human", "mochi",
    )
}
