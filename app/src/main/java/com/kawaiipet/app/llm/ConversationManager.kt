package com.kawaiipet.app.llm

import android.util.Log
import com.kawaiipet.app.memory.MemoryPipeline
import com.kawaiipet.app.memory.ShortTermMemory
import com.kawaiipet.app.pet.PetExpression
import com.kawaiipet.app.util.DebugSessionLog
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationManager @Inject constructor(
    private val llmService: LlmService,
    private val shortTermMemory: ShortTermMemory,
    private val memoryPipeline: MemoryPipeline,
) {
    data class LlmResponse(val text: String, val expression: PetExpression)

    suspend fun warmUpLlm() {
        llmService.warmUp()
    }

    suspend fun resetLlmSession() {
        llmService.resetSession()
    }

    /**
     * @param onPartial cumulative display text (emotion tags stripped)
     * @param onSpeakableSentence complete sentence ready for TTS — fires mid-generation
     */
    suspend fun processUserInput(
        text: String,
        onPartial: suspend (String) -> Unit = {},
        onSpeakableSentence: suspend (String) -> Unit = {},
    ): LlmResponse {
        if (LlmPromptDefaults.PURE_SMOLLM_DEBUG) {
            shortTermMemory.clear()
            val messages = listOf(ChatMessage(Role.USER, text))
            val streamer = SpokenSentenceStreamer()
            val rawResponse = llmService.chat(
                messages = messages,
                memoryParagraph = "",
                onPartial = { partial ->
                    val display = sanitizeForDisplay(partial)
                    onPartial(display)
                    for (s in streamer.consume(display)) {
                        onSpeakableSentence(s)
                    }
                },
            )
            val (cleanText, expression) = parseEmotionTag(rawResponse)
            val spoken = cleanText.trim().ifBlank { rawResponse.trim() }
            for (s in streamer.flush(spoken)) {
                onSpeakableSentence(s)
            }
            if (!streamer.hasEmitted && spoken.isNotBlank()) {
                onSpeakableSentence(spoken)
            }
            return LlmResponse(spoken, expression)
        }

        shortTermMemory.addMessage(ChatMessage(Role.USER, text))
        val messages = shortTermMemory.getMessages()
        // Never block TTFT on LTM retrieval — index still happens after the turn.
        val memoryContext = ""

        val streamer = SpokenSentenceStreamer()
        var lastDisplay = ""

        var rawResponse = llmService.chat(
            messages = messages,
            memoryParagraph = memoryContext,
            onPartial = { partial ->
                val display = sanitizeForDisplay(partial)
                lastDisplay = display
                // Bubble updates on every token growth.
                onPartial(display)
                for (s in streamer.consume(display, text)) {
                    Log.d(TAG, "stream sentence: ${s.toOneLineLog()}")
                    onSpeakableSentence(s)
                }
            },
        )
        // #region agent log
        DebugSessionLog.log(
            hypothesisId = "B",
            location = "ConversationManager.afterChat",
            message = "llm raw complete",
            data = mapOf(
                "rawLen" to rawResponse.length,
                "raw" to rawResponse.take(280).replace('\n', ' '),
                "streamed" to streamer.hasEmitted,
                "lastDisplay" to lastDisplay.take(200),
            ),
            runId = "chat-fix",
        )
        // #endregion
        Log.d(TAG, "llm raw (${rawResponse.length} chars): ${rawResponse.toOneLineLog()}")

        // Retry only if nothing has been spoken yet (streaming already committed audio).
        val truncatedBeforeRetry = LlmPromptDefaults.isTruncatedMidPhrase(rawResponse)
        if (!streamer.hasEmitted &&
            (rawResponse.isBlank() || truncatedBeforeRetry)
        ) {
            Log.w(
                TAG,
                "Model returned blank/truncated with no streamed speech — retrying once",
            )
            DebugSessionLog.log(
                hypothesisId = "B",
                location = "ConversationManager.retry",
                message = "retry after blank/truncated",
                data = mapOf(
                    "raw" to rawResponse.take(120),
                    "truncated" to truncatedBeforeRetry,
                ),
                runId = "chat-fix",
            )
            // Rebuild sticky from ShortTermMemory, then retry the original turn (no nudge).
            llmService.resetSession()
            rawResponse = llmService.chat(
                messages = messages,
                memoryParagraph = memoryContext,
                onPartial = { partial ->
                    val display = sanitizeForDisplay(partial)
                    lastDisplay = display
                    onPartial(display)
                    for (s in streamer.consume(display, text)) {
                        onSpeakableSentence(s)
                    }
                },
            )
            Log.d(TAG, "llm retry (${rawResponse.length} chars): ${rawResponse.toOneLineLog()}")
        }

        val spokenRaw = rawResponse
        val (cleanText, parsedExpression) = parseEmotionTag(spokenRaw)
        val qualityChecked = sanitizeModelReply(
            LlmPromptDefaults.collapseRepeatedPhrases(cleanText),
            text,
        )
        // Prefer the model reply; only fall back when there's nothing usable.
        var spokenText = when {
            qualityChecked.isNotBlank() -> qualityChecked
            streamer.hasEmitted -> lastDisplay.trim().ifBlank { cleanText.trim() }
            else -> ensureSpeakable(cleanText, rawResponse, text)
        }.ifBlank { ensureSpeakable("", rawResponse, text) }

        // History poison from loops (seen in device logs) — swap to fallback and reset KV.
        if (LlmPromptDefaults.isDegenerateReply(spokenText) ||
            LlmPromptDefaults.isDegenerateReply(cleanText)
        ) {
            Log.w(TAG, "Degenerate reply — fallback + reset sticky: ${spokenText.toOneLineLog()}")
            llmService.resetSession()
            spokenText = fallbackForUser(text, rawResponse)
        }

        val expression = when {
            spokenText == LlmPromptDefaults.DIDNT_CATCH_REPLY -> PetExpression.THINKING
            LlmPromptDefaults.isCannedFallback(spokenText) -> PetExpression.HAPPY
            else -> parsedExpression
        }

        if (streamer.hasEmitted && !LlmPromptDefaults.isCannedFallback(spokenText)) {
            val flushSource = when {
                spokenText.isNotBlank() -> spokenText
                else -> lastDisplay.ifBlank { cleanText }
            }
            for (s in streamer.flush(flushSource, text)) {
                onSpeakableSentence(s)
            }
        } else if (!streamer.hasEmitted && spokenText.isNotBlank()) {
            onSpeakableSentence(spokenText)
        }

        Log.i(
            TAG,
            "llm parsed: expression=$expression cleanLen=${cleanText.length} " +
                "clean=${cleanText.toOneLineLog()} → spokenLen=${spokenText.length} " +
                "spoken=${spokenText.toOneLineLog()} streamed=${streamer.hasEmitted}",
        )

        if (spokenText.isNotBlank() && !LlmPromptDefaults.isDegenerateReply(spokenText)) {
            shortTermMemory.addMessage(ChatMessage(Role.ASSISTANT, spokenText))
            if (!LlmPromptDefaults.isCannedFallback(spokenText)) {
                memoryPipeline.scheduleIndexTurn(text, spokenText)
            }
        } else if (LlmPromptDefaults.isCannedFallback(spokenText)) {
            // Keep a short canned line so the next turn has something coherent.
            shortTermMemory.addMessage(ChatMessage(Role.ASSISTANT, spokenText))
        }

        return LlmResponse(spokenText, expression)
    }

    fun clearConversation() {
        shortTermMemory.clear()
    }

    /** Clears short-term chat and drops the sticky LiteRT conversation / KV cache. */
    suspend fun clearConversationFully() {
        shortTermMemory.clear()
        llmService.resetSession()
    }

    private fun ensureSpeakable(
        cleanText: String,
        rawResponse: String,
        userText: String,
    ): String {
        val t = clampSpokenLength(cleanText.trim())
        if (t.any { it.isLetterOrDigit() } &&
            !LlmPromptDefaults.isCannedFallback(t) &&
            !isAssistantDump(t) &&
            !isAssistantDump(rawResponse) &&
            !isMetaInstruction(t)
        ) {
            return t
        }
        return fallbackForUser(userText, rawResponse)
    }

    private fun fallbackForUser(userText: String, rawResponse: String): String {
        Log.w(TAG, "Fallback (raw=${rawResponse.take(120).replace('\n', ' ')})")
        return when {
            isCapabilityQuestion(userText) -> LlmPromptDefaults.CAPABILITY_FALLBACK
            isHowAreYou(userText) -> LlmPromptDefaults.GREETING_FALLBACK
            isStatusUpdate(userText) -> LlmPromptDefaults.STATUS_FALLBACK
            isTrivialTurn(userText) -> LlmPromptDefaults.GREETING_FALLBACK
            isClarifyRequest(userText) -> LlmPromptDefaults.CLARIFY_FALLBACK
            isWhatIsQuestion(userText) -> LlmPromptDefaults.CURIOUS_FALLBACK
            else -> LlmPromptDefaults.CURIOUS_FALLBACK
        }
    }

    /** Vague asks like "remind me" / "help me" with no object — pet should ask what. */
    private fun isClarifyRequest(text: String): Boolean {
        val lower = text.lowercase().trim().trimEnd('.', '!', '?')
        if (lower in setOf(
                "remind me", "remember that", "help me", "help", "do it",
                "okay then remind me", "ok then remind me", "okay remind me",
                "okay then, remind me", "ok then, remind me",
            )
        ) return true
        if (lower.startsWith("remind me") && lower.length < 28) return true
        if (lower.startsWith("help me") && lower.length < 18) return true
        return false
    }

    private fun sanitizeModelReply(text: String, userText: String): String {
        val cleaned = extractSpokenCore(text, userText)
        if (cleaned.isEmpty()) {
            Log.w(TAG, "No salvageable sentence from: ${text.take(80)}")
            return ""
        }
        return cleaned
    }

    /**
     * Light cleanup only — keep the model reply, strip role labels / emotion scaffolding.
     */
    private fun extractSpokenCore(raw: String, lastUser: String): String {
        var t = raw.trim()
        if (t.isEmpty()) return ""

        t = stripRolePrefixes(t)
        if (lastUser.isNotBlank()) t = stripLeadingEcho(t, lastUser)
        t = t.trim().trim('"').trim()
        if (t.isEmpty()) return ""

        val parts = t.split(Regex("(?<=[.!?])[\"']?\\s+"))
            .map { stripRolePrefixes(it).trim().trim('"').trim() }
            .filter { it.isNotEmpty() && it.any { ch -> ch.isLetterOrDigit() } }

        if (parts.isEmpty()) {
            return if (t.length in 3..LlmPromptDefaults.MAX_SPOKEN_CHARS) {
                t
            } else {
                t.take(LlmPromptDefaults.MAX_SPOKEN_CHARS)
            }
        }

        return parts.take(LlmPromptDefaults.MAX_REPLY_SENTENCES).joinToString(" ").trim()
    }

    private fun stripRolePrefixes(text: String): String {
        var t = text.trim()
        t = Regex("(?i)^(you|assistant|pet|mochi|human|friend)\\s*(says)?\\s*:\\s*").replace(t, "")
        return t.trim()
    }

    private fun isTrivialTurn(text: String): Boolean {
        val norm = normalizeForCompare(text)
        if (norm.isEmpty()) return true
        val tokens = norm.split(' ').filter { it.isNotEmpty() }
        if (tokens.size > 6) return false
        val content = tokens.filterNot { it in TRIVIAL_TOKENS }
        return content.isEmpty() || (content.size == 1 && content[0] in setOf("hello", "hi", "hey", "yo"))
    }

    private fun stripLeadingEcho(reply: String, userText: String): String {
        val r = reply.trim()
        if (r.length >= userText.length &&
            r.regionMatches(0, userText, 0, userText.length, ignoreCase = true)
        ) {
            return r.drop(userText.length)
                .trimStart(' ', ',', '.', '!', '?', ':', '\n', '"', '\'')
                .trim()
        }
        return r
    }

    private fun isMetaInstruction(text: String): Boolean {
        val lower = text.lowercase()
        return listOf(
            "remember to", "emotion tag", "respond in first",
            "some tags", "finish with", "reply rules",
        ).any { lower.contains(it) }
    }

    private fun isAssistantDump(text: String): Boolean {
        val lower = text.lowercase()
        // Lists / markdown dumps — not short companion lines like "I'm here to help!".
        if (Regex("\\n\\s*[-•]").containsMatchIn(text)) return true
        if (lower.count { it == '*' } >= 2) return true
        return listOf(
            "travel planning",
            "travel tips",
            "itinerar",
            "customer support",
            "how can i assist",
            "how may i assist",
            "feel free to ask me anything",
            "feel free to ask",
            "here to help you with any",
            "here to help with any",
            "any questions or problems",
            "questions or concerns",
            "i'm happy to help you with",
            "i am happy to help you with",
        ).any { lower.contains(it) }
    }

    private fun isCapabilityQuestion(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("what can you") ||
            lower.contains("what do you do") ||
            lower.contains("how can you help") ||
            lower.contains("how do you help") ||
            lower.contains("how can you assist") ||
            lower.contains("what are you for") ||
            (lower.contains("can you do") && lower.length < 48) ||
            (lower.contains("help me") && lower.length < 28 && !lower.contains("remind"))
    }

    private fun isHowAreYou(text: String): Boolean =
        LlmPromptDefaults.userInvitesGreeting(text) &&
            normalizeForCompare(text).let { n ->
                n.contains("how are you") ||
                    n.contains("how you doing") ||
                    n.contains("hows it going") ||
                    n.contains("whats up")
            }

    /** User shared how they feel ("I'm good", "doing fine") — don't ask how are you again. */
    private fun isStatusUpdate(text: String): Boolean {
        val n = normalizeForCompare(text)
        if (n.isEmpty() || n.length > 40) return false
        return n.contains("i am good") || n.contains("i m good") || n.contains("im good") ||
            n.contains("i am fine") || n.contains("i m fine") || n.contains("im fine") ||
            n.contains("doing good") || n.contains("doing fine") || n.contains("doing okay") ||
            n.contains("doing ok") || n.contains("i am okay") || n.contains("i m okay") ||
            n.contains("i am great") || n.contains("i m great") ||
            n == "good" || n == "fine" || n == "okay" || n == "ok" || n == "great"
    }

    private fun isWhatIsQuestion(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("what is") ||
            lower.contains("what's a") ||
            lower.contains("whats a") ||
            lower.contains("what are")
    }

    private fun normalizeForCompare(text: String): String =
        text.lowercase()
            .replace(Regex("^(user|assistant|pet)\\s*:\\s*"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun clampSpokenLength(text: String): String {
        // Keep all lines; only normalize whitespace. Hard word cut is a last-resort safety net.
        val collapsed = text
            .replace(Regex("[\\t\\r\\n]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('"')
        if (collapsed.length > LlmPromptDefaults.MAX_SPOKEN_CHARS) {
            return softCutAtSentence(collapsed, LlmPromptDefaults.MAX_SPOKEN_CHARS)
        }
        val words = collapsed.split(' ').filter { it.isNotEmpty() }
        if (words.size <= LlmPromptDefaults.MAX_REPLY_WORDS) return collapsed
        return softCutAtSentence(
            words.take(LlmPromptDefaults.MAX_REPLY_WORDS).joinToString(" "),
            LlmPromptDefaults.MAX_SPOKEN_CHARS,
        )
    }

    /** Prefer ending on .!? near [maxChars] instead of mid-word. */
    private fun softCutAtSentence(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text.trimEnd(',', ';', ':')
        val window = text.take(maxChars)
        val lastStop = window.indexOfLast { it == '.' || it == '!' || it == '?' }
        if (lastStop >= maxChars / 3) {
            return window.take(lastStop + 1).trim()
        }
        return window.trimEnd(',', ';', ':', ' ').trim()
    }

    companion object {
        private const val TAG = "ConversationManager"
        private const val LOG_SNIP_LEN = 200

        private val TRIVIAL_TOKENS = setOf(
            "hello", "hi", "hey", "yo", "how", "are", "you", "doing", "today",
            "there", "good", "morning", "afternoon", "evening", "night", "whats",
            "up", "sup",
        )

        private fun String.toOneLineLog(): String =
            replace('\n', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(LOG_SNIP_LEN)

        private val EMOTION_TAG_REGEX =
            "\\[(happy|sad|angry|thinking|idle|listening|talking|sleeping)\\]"
                .toRegex(RegexOption.IGNORE_CASE)

        private val TRAILING_PARTIAL_TAG_REGEX = "\\[[^\\]]*$".toRegex()

        fun sanitizeForDisplay(partialRaw: String): String =
            LlmPromptDefaults.sanitizeModelSpeech(partialRaw)
                .replace(EMOTION_TAG_REGEX, "")
                .replace(TRAILING_PARTIAL_TAG_REGEX, "")
                .trim()

        fun parseEmotionTag(response: String): Pair<String, PetExpression> {
            // Keep emotion tags until after match; strip markdown/emoji around them.
            val forTags = response
            val match = EMOTION_TAG_REGEX.findAll(forTags).lastOrNull()
            val expression = match?.groupValues?.get(1)?.let { PetExpression.fromTag(it) }
                ?: PetExpression.HAPPY
            val cleanText = LlmPromptDefaults.stripSpeechFormatting(
                forTags.replace(EMOTION_TAG_REGEX, ""),
            )
            return cleanText to expression
        }
    }
}
