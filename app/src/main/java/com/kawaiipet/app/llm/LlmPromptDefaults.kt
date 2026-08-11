package com.kawaiipet.app.llm

/**
 * Prompt + sampler defaults for on-device SmolLM2-360M via LiteRT-LM.
 *
 * Long-term memory is RAG-retrieved chunks (prompt-sized); short-term is recent messages.
 */
object LlmPromptDefaults {

    /**
     * Debug: pure model — no pet personality, no chat history, no reply filters.
     * Flip to false to reconnect the normal pet pipeline (code stays in place).
     */
    const val PURE_SMOLLM_DEBUG = false

    /**
     * Shipped Mochi personality — a pet friend you hang out with, not a helper bot.
     * Keep short: SmolLM2-360M follows brief role cues best.
     */
    const val DEFAULT_PERSONALITY =
        "Cozy playful virtual pet friend. Chat about daily life, feelings, and little moments. "

    /** Bump when [DEFAULT_PERSONALITY] changes so stored prefs refresh. */
    const val PERSONALITY_DEFAULT_VERSION = 12

    /**
     * Hard caps for spoken pet replies.
     */
    const val MAX_REPLY_WORDS = 48
    const val MAX_REPLY_SENTENCES = 3
    const val MAX_SPOKEN_CHARS = 280
    const val MAX_CHARS_PER_TURN = 320

    /** Legacy paragraph prefs / migration clamp only — storage itself is uncapped in RAG. */
    const val MAX_MEMORY_WORDS = 250
    const val MAX_MEMORY_CHARS = 1500

    /** Cap only what is injected into the system prompt from retrieved chunks. */
    const val MAX_RETRIEVED_MEMORY_WORDS = 80
    const val MAX_RETRIEVED_MEMORY_CHARS = 400

    /** Keep the last N chat messages as short-term history (full text, not summarized). */
    const val MAX_SHORT_TERM_MESSAGES = 6

    const val DIDNT_CATCH_REPLY = "Huh? Say that again for me?"
    const val GREETING_FALLBACK = "Hey! I'm good — how are you doing?"
    const val STATUS_FALLBACK = "Aww, glad you're good! What's been going on?"
    const val CAPABILITY_FALLBACK =
        "I'm your pet friend — talk to me about anything in your life."
    const val CURIOUS_FALLBACK = "Ooh, I'm listening — tell me more?"
    const val CLARIFY_FALLBACK = "Remind you of what? I'm right here."

    /**
     * Sampler defaults for SmolLM2-360M-Instruct — balanced friend chat
     * (not as tight as 0.5/48, not as loose as 0.75/96).
     */
    const val SAMPLER_TOP_K = 30
    const val SAMPLER_TOP_P = 0.9
    const val SAMPLER_TEMPERATURE = 0.65
    /** ~2 short spoken sentences + emotion tag. */
    const val MAX_OUTPUT_TOKENS = 72

    /**
     * Decode penalties for short pet replies (LiteRT-LM [RepetitionPenaltyConfig]).
     * Applied only to the current generation window — keeps "I'm good…" / phrase loops down
     * without crushing natural buddy chat.
     *
     * - repetitionPenalty (>= 1): HuggingFace-style multiplicative
     * - presencePenalty: OpenAI-style once-seen token nudge
     * - frequencyPenalty: OpenAI-style scales with how often a token reappears
     * - windowSize 0 = whole current reply (fits [MAX_OUTPUT_TOKENS])
     */
    const val REPETITION_PENALTY = 1.25f
    const val PRESENCE_PENALTY = 0.5f
    const val FREQUENCY_PENALTY = 0.4f
    const val PENALTY_WINDOW_SIZE = 0

    /**
     * Ban exact n-gram repeats in the current reply ([NoRepeatNgramConfig]).
     * Size 4 stops "Please don't be so sorry" loops without blocking normal words.
     */
    const val NO_REPEAT_NGRAM_SIZE = 4
    const val NO_REPEAT_NGRAM_WINDOW = 0

    /**
     * Stable system prompt for SmolLM2-360M — short role + friend chat style.
     */
    fun buildSystemPrompt(
        petName: String,
        personality: String,
        @Suppress("UNUSED_PARAMETER") memoryParagraph: String = "",
    ): String {
        val name = petName.trim().ifEmpty { "Mochi" }
        // Keep the system preface short — prefill cost scales with this.
        val vibe = personality.trim().ifEmpty { DEFAULT_PERSONALITY }.take(120)
        return buildString {
            append("You are $name, their tiny pet friend. $vibe\n")
            append("Answer what they just said as a close buddy. And sometimes ask questions about their life.")
            append("1–2 short spoken sentences. No apologies about the chat. Don't echo them. ")
            append("Plain speech only. End with [happy], [sad], [angry], or [thinking].")
        }
    }

    /** Prefix retrieved facts onto the user turn without changing the system prompt. */
    fun attachMemoryToUserTurn(userText: String, memoryParagraph: String): String {
        val memory = clampRetrievedMemory(memoryParagraph)
        if (memory.isBlank()) return userText
        // Keep the user line first so the tiny model answers it, not the notes.
        return buildString {
            append(userText)
            append("\nNotes: ")
            append(memory)
        }
    }

    /**
     * True when a reply would poison sticky history (phrase loops / meta apology spam).
     * Used for history hygiene only — not for mid-stream TTS abort.
     */
    fun isDegenerateReply(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (isCannedFallback(t)) return false
        val lower = t.lowercase()
        val sorryCount = Regex("\\bsorry\\b").findAll(lower).count()
        if (sorryCount >= 2) return true
        if (lower.contains("last few sentences") ||
            lower.contains("please don't be so sorry") ||
            lower.contains("bit of a companion") ||
            lower.contains("living companion") ||
            lower.contains("my instructions")
        ) {
            return true
        }
        val collapsed = collapseRepeatedPhrases(t)
        if (t.length >= 40 && collapsed.length < t.length * 0.55f) return true
        return false
    }

    /** True when the user is greeting / asking how the pet is (fallback routing). */
    fun userInvitesGreeting(userText: String): Boolean {
        val n = normalizeEchoKey(userText)
        if (n.isEmpty()) return false
        if (n == "hi" || n == "hello" || n == "hey" || n == "yo" || n == "hiya") return true
        if (n.startsWith("hi ") || n.startsWith("hello ") || n.startsWith("hey ")) {
            if (n.split(' ').size <= 4) return true
        }
        return n.contains("how are you") ||
            n.contains("how you doing") ||
            n.contains("hows it going") ||
            n.contains("how is it going") ||
            n.contains("whats up") ||
            n.contains("what s up")
    }

    private fun normalizeEchoKey(text: String): String =
        text.lowercase()
            .replace(Regex("^(user|assistant|pet)\\s*:\\s*"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * True when the spoken answer looks cut off mid-phrase (token budget exhausted).
     */
    fun isTruncatedMidPhrase(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        val last = t.last()
        if (last == '.' || last == '!' || last == '?' || last == '"' || last == '”') return false
        // Ends with a contracted / dangling token — classic truncate.
        val lower = t.lowercase()
        if (lower.endsWith(" it's") || lower.endsWith(" i'm") || lower.endsWith(" i've") ||
            lower.endsWith(" don't") || lower.endsWith(" can't") || lower.endsWith(" a") ||
            lower.endsWith(" the") || lower.endsWith(" to") || lower.endsWith(" and") ||
            lower.endsWith(" little") || lower.endsWith(" more")
        ) {
            return true
        }
        // Short answer with no terminal punct.
        if (t.length < 28 && !t.any { it == '.' || it == '!' || it == '?' }) return true
        return false
    }

    /**
     * Collapse "phrase phrase phrase" loops into a single phrase for TTS/bubble.
     */
    fun collapseRepeatedPhrases(text: String): String {
        val t = text.trim()
        if (t.length < 12) return t
        val words = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 4) return t
        // Try phrase lengths 2..6 words.
        for (n in 6 downTo 2) {
            if (words.size < n * 2) continue
            val phrase = words.take(n).joinToString(" ")
            val phraseWords = phrase.split(" ")
            var repeats = 1
            var i = n
            while (i + n <= words.size && words.subList(i, i + n) == phraseWords) {
                repeats++
                i += n
            }
            if (repeats >= 2 && i >= words.size - 1) {
                return phrase.trimEnd(',', ';', '-', '—')
            }
        }
        return t
    }

    /**
     * Strips markdown / decorative formatting so TTS and the bubble stay plain speech.
     * Keeps the words inside emphasis markers; drops emojis and leftover `*` `_` `` ` ``.
     */
    fun stripSpeechFormatting(text: String): String {
        if (text.isEmpty()) return text
        var t = text
        // Fenced / inline code → inner text
        t = Regex("```[\\s\\S]*?```").replace(t, " ")
        t = Regex("`([^`]+)`").replace(t, "$1")
        // **bold** / *italic* / __bold__ / _italic_ → inner text
        t = Regex("\\*\\*([^*]+)\\*\\*").replace(t, "$1")
        t = Regex("__([^_]+)__").replace(t, "$1")
        t = Regex("(?<!\\w)\\*([^*]+)\\*(?!\\w)").replace(t, "$1")
        t = Regex("(?<!\\w)_([^_]+)_(?!\\w)").replace(t, "$1")
        // Headings / bullets
        t = Regex("(?m)^\\s{0,3}#{1,6}\\s+").replace(t, "")
        t = Regex("(?m)^\\s*[-*•]+\\s+").replace(t, "")
        // Any leftover decorative markers common in chat models
        t = t.replace("*", "")
            .replace("_", " ")
            .replace("#", " ")
            .replace("`", "")
            .replace("~", " ")
        t = stripEmojis(t)
        return normalizeSpeechWhitespace(t)
    }

    /** Formatting-strip for anything shown or spoken. */
    fun sanitizeModelSpeech(text: String): String =
        collapseRepeatedPhrases(stripSpeechFormatting(text))

    private fun normalizeSpeechWhitespace(text: String): String =
        text
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex(" *\\n+ *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

    private fun stripEmojis(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (!isDecorativeSymbol(cp)) {
                out.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    private fun isDecorativeSymbol(cp: Int): Boolean {
        if (cp in 0x1F300..0x1FAFF) return true // emoji & symbols
        if (cp in 0x2600..0x27BF) return true // misc symbols / dingbats
        if (cp in 0x2300..0x23FF) return true // misc technical (⏰ etc.)
        if (cp in 0x2B00..0x2BFF) return true // arrows / stars
        if (cp == 0xFE0F || cp == 0x200D || cp == 0x20E3) return true // VS16 / ZWJ / keycap
        if (cp in 0x1F1E6..0x1F1FF) return true // flags
        if (cp in 0xFE00..0xFE0F) return true // variation selectors
        return false
    }

    fun clampMemoryParagraph(text: String): String =
        clampParagraph(sanitizeMemoryParagraph(text), MAX_MEMORY_WORDS, MAX_MEMORY_CHARS)

    /** Prompt-injection clamp for RAG-retrieved memory (not a storage limit). */
    fun clampRetrievedMemory(text: String): String =
        clampParagraph(sanitizeMemoryParagraph(text), MAX_RETRIEVED_MEMORY_WORDS, MAX_RETRIEVED_MEMORY_CHARS)

    /**
     * True when the friend likely stated something durable about themselves.
     * False for definitions, trivia, greetings — those make the model "explain" into memory.
     */
    fun looksLikeMemorableUserTurn(text: String): Boolean {
        val t = text.trim()
        if (t.length < 8) return false
        val lower = t.lowercase()
        if (isCannedFallback(t)) return false
        if (TRIVIAL_MEMORY_SKIP.any { lower == it || lower.startsWith("$it ") }) return false
        // Questions / capability asks are not durable facts about the friend.
        if (lower.contains("?") ||
            lower.startsWith("what ") || lower.startsWith("what's") ||
            lower.startsWith("whats ") || lower.startsWith("who ") ||
            lower.startsWith("how ") || lower.startsWith("why ") ||
            lower.startsWith("can you") || lower.startsWith("could you") ||
            lower.startsWith("will you") || lower.startsWith("do you") ||
            lower.startsWith("explain") || lower.startsWith("define") ||
            lower.contains("what can you") || lower.contains("how can you") ||
            lower.contains("help me")
        ) {
            return false
        }
        // Transient mood / status chatter — not worth LTM.
        if (STATUS_CHAT_HINTS.any { lower == it || lower.startsWith("$it ") || lower.contains(" $it") }) {
            return false
        }
        // Pad so "I like…" matches at the start.
        val padded = " $lower "
        // Require a concrete personal-fact cue — not bare "i" / "me" / "my".
        return DURABLE_FACT_HINTS.any { padded.contains(it) }
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
            t == STATUS_FALLBACK ||
            t == CAPABILITY_FALLBACK ||
            t == CURIOUS_FALLBACK ||
            t == CLARIFY_FALLBACK
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

    /** Concrete durable facts only — bare "i"/"me"/"my" indexed too much chatter. */
    private val DURABLE_FACT_HINTS = listOf(
        " i like ", " i love ", " i hate ", " i prefer ", " i want ",
        " i live ", " i work ", " i study ", " i have ", " i had ",
        " i'm a ", " i am a ", " i'm an ", " i am an ",
        " my name ", " call me ", " i'm called ", " i am called ",
        " my friend ", " my mom ", " my dad ", " my wife ", " my husband ",
        " my dog ", " my cat ", " my job ", " my school ", " my birthday ",
        " favorite ", " favourite ", " allerg",
    )

    private val STATUS_CHAT_HINTS = listOf(
        "i am doing", "i'm doing", "im doing", "doing good", "doing fine",
        "doing okay", "doing ok", "i'm fine", "i am fine", "i'm good",
        "i am good", "i'm okay", "i am okay", "i'm ok", "i am ok",
        "how are you", "how're you", "what's up", "whats up",
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
