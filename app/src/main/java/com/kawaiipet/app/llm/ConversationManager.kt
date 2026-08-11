package com.kawaiipet.app.llm

import android.util.Log
import com.kawaiipet.app.memory.MemoryPipeline
import com.kawaiipet.app.memory.ShortTermMemory
import com.kawaiipet.app.pet.PetExpression
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

    suspend fun processUserInput(
        text: String,
        onPartial: (String) -> Unit = {},
    ): LlmResponse {
        if (LlmPromptDefaults.PURE_SMOLLM_DEBUG) {
            // Pure SmolLM: no short-term / memory, no personality filters — raw model reply.
            shortTermMemory.clear()
            val messages = listOf(ChatMessage(Role.USER, text))
            val rawResponse = llmService.chat(
                messages = messages,
                memoryParagraph = "",
                onPartial = onPartial,
            )
            val (cleanText, expression) = parseEmotionTag(rawResponse)
            val spoken = cleanText.trim().ifBlank { rawResponse.trim() }
            Log.d(
                TAG,
                "PURE_SMOLLM_DEBUG raw (${rawResponse.length}): ${rawResponse.toOneLineLog()} " +
                    "→ spoken=${spoken.toOneLineLog()}",
            )
            return LlmResponse(spoken, expression)
        }

        shortTermMemory.addMessage(ChatMessage(Role.USER, text))
        val messages = shortTermMemory.getMessages()
        val memoryParagraph = memoryPipeline.getMemoryParagraph()

        var rawResponse = llmService.chat(
            messages = messages,
            memoryParagraph = memoryParagraph,
            onPartial = onPartial,
        )
        Log.d(TAG, "llm raw (${rawResponse.length} chars): ${rawResponse.toOneLineLog()}")

        // If it regurgitates a canned line, retry once without history.
        if (isStuckOnCanned(rawResponse)) {
            Log.w(TAG, "Model stuck on canned line — retrying once without history")
            val nudged = listOf(
                ChatMessage(
                    Role.USER,
                    "As a clever companion, answer briefly: ${text.trim().take(LlmPromptDefaults.MAX_CHARS_PER_TURN)}",
                ),
            )
            rawResponse = llmService.chat(
                messages = nudged,
                memoryParagraph = memoryParagraph,
            )
            Log.d(TAG, "llm retry (${rawResponse.length} chars): ${rawResponse.toOneLineLog()}")
        }

        val (cleanText, parsedExpression) = parseEmotionTag(rawResponse)
        val qualityChecked = sanitizeModelReply(cleanText, text)
        val spokenText = ensureSpeakable(qualityChecked, rawResponse, text)
        val expression = when {
            spokenText == LlmPromptDefaults.DIDNT_CATCH_REPLY -> PetExpression.THINKING
            LlmPromptDefaults.isCannedFallback(spokenText) -> PetExpression.HAPPY
            else -> parsedExpression
        }
        Log.d(
            TAG,
            "llm parsed: expression=$expression cleanLen=${cleanText.length} " +
                "clean=${cleanText.toOneLineLog()} → spokenLen=${spokenText.length} " +
                "spoken=${spokenText.toOneLineLog()}",
        )

        // Never store canned/failed lines — they train the next copy loop.
        if (LlmPromptDefaults.isCannedFallback(spokenText)) {
            shortTermMemory.removeLastUserMessage()
        } else {
            shortTermMemory.addMessage(ChatMessage(Role.ASSISTANT, spokenText))
            // Defer long-term summarize until Home (session flush) — keep chat snappy.
            memoryPipeline.recordTurn(text, spokenText)
        }

        return LlmResponse(spokenText, expression)
    }

    fun clearConversation() {
        shortTermMemory.clear()
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
            isTrivialTurn(userText) -> LlmPromptDefaults.GREETING_FALLBACK
            isWhatIsQuestion(userText) -> LlmPromptDefaults.CURIOUS_FALLBACK
            else -> LlmPromptDefaults.CURIOUS_FALLBACK
        }
    }

    private fun sanitizeModelReply(text: String, userText: String): String {
        var cleaned = extractSpokenCore(text, userText)
        if (cleaned.isEmpty()) {
            Log.w(TAG, "No salvageable sentence from: ${text.take(80)}")
            return ""
        }
        if (isNarration(cleaned) || isMetaInstruction(cleaned) || isAssistantDump(cleaned) ||
            isAssistantDump(text) || isStuckOnCanned(cleaned)
        ) {
            Log.w(TAG, "Rejected bad reply: ${cleaned.take(80)}")
            return ""
        }
        if (normalizeForCompare(cleaned) == normalizeForCompare(userText)) {
            Log.w(TAG, "Rejected pure user echo")
            return ""
        }
        return cleaned
    }

    private fun extractSpokenCore(raw: String, lastUser: String): String {
        var t = raw.trim()
        if (t.isEmpty()) return ""

        t = stripRolePrefixes(t)
        if (lastUser.isNotBlank()) t = stripLeadingEcho(t, lastUser)
        t = takeBeforeMeta(t)

        val quoted = Regex("\"([^\"]{3,120})\"").find(t)
        if (quoted != null) {
            val inner = quoted.groupValues[1].trim()
            if (!isMetaInstruction(inner) && !isNarration(inner) && !isAssistantDump(inner)) {
                return inner
            }
        }

        t = t.trim().trim('"').trim()
        val parts = t.split(Regex("(?<=[.!?])[\"']?\\s+"))
            .map { it.trim().trim('"').trim() }
            .filter { it.isNotEmpty() }

        for (part in parts) {
            val sentence = stripRolePrefixes(part).trim().trim('"')
            if (sentence.any { it.isLetterOrDigit() } &&
                !isNarration(sentence) &&
                !isMetaInstruction(sentence) &&
                !isAssistantDump(sentence)
            ) {
                return sentence
            }
        }
        // Allow a short single-clause reply without trailing punctuation.
        if (t.length in 3..120 && !isAssistantDump(t) && !isMetaInstruction(t) && !isNarration(t)) {
            return t
        }
        return ""
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

    private fun takeBeforeMeta(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val lower = trimmed.lowercase()
        if (lower.startsWith("the user") || lower.startsWith("here are") ||
            lower.startsWith("remember")
        ) {
            return ""
        }
        val cutMarkers = listOf(
            " here are", "\nhere are", " remember to", "\nremember",
            " emotion tag", "\nemotion", " the user", "\nthe user",
            " i can help with", "\ni can help", "\n*", " * ", "\n-",
            " - happy", " - sad", " - thinking",
        )
        var cutAt = trimmed.length
        for (marker in cutMarkers) {
            val idx = lower.indexOf(marker)
            if (idx >= 0) cutAt = minOf(cutAt, idx)
        }
        return trimmed.take(cutAt).trim().trimEnd('.', '!', '?', '"', '\'').trim()
    }

    private fun isStuckOnCanned(text: String): Boolean {
        val n = normalizeForCompare(text)
        if (n.isEmpty()) return false
        return listOf(
            LlmPromptDefaults.CAPABILITY_FALLBACK,
            LlmPromptDefaults.GREETING_FALLBACK,
            LlmPromptDefaults.DIDNT_CATCH_REPLY,
            LlmPromptDefaults.CURIOUS_FALLBACK,
        ).any { normalizeForCompare(it) == n || n.startsWith(normalizeForCompare(it).take(24)) }
    }

    private fun isMetaInstruction(text: String): Boolean {
        val lower = text.lowercase()
        return listOf(
            "here are", "remember to", "emotion tag", "respond in first",
            "some tags", "finish with", "reply rules",
        ).any { lower.contains(it) }
    }

    private fun isAssistantDump(text: String): Boolean {
        val lower = text.lowercase()
        if (lower.contains('*') || Regex("\\n\\s*[-•]").containsMatchIn(text)) return true
        return listOf(
            "i can help with",
            "i'm here to help",
            "i am here to help",
            "feel free to ask",
            "travel planning",
            "travel tips",
            "booking",
            "itinerar",
            "customer support",
            "how can i assist",
        ).any { lower.contains(it) }
    }

    private fun isCapabilityQuestion(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("what can you") ||
            lower.contains("what do you do") ||
            (lower.contains("can you do") && lower.length < 48)
    }

    private fun isWhatIsQuestion(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("what is") ||
            lower.contains("what's a") ||
            lower.contains("whats a") ||
            lower.contains("what are")
    }

    private fun isNarration(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower.startsWith("the user") ||
            lower.contains("the user is ") ||
            lower.contains("the user was ")
    }

    private fun normalizeForCompare(text: String): String =
        text.lowercase()
            .replace(Regex("^(user|assistant|pet)\\s*:\\s*"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun clampSpokenLength(text: String): String {
        val collapsed = text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .firstOrNull()
            .orEmpty()
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('"')
        val words = collapsed.split(' ').filter { it.isNotEmpty() }
        if (words.size <= LlmPromptDefaults.MAX_REPLY_WORDS) return collapsed
        return words.take(LlmPromptDefaults.MAX_REPLY_WORDS).joinToString(" ").trimEnd(',', ';', ':')
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
            partialRaw
                .replace(EMOTION_TAG_REGEX, "")
                .replace(TRAILING_PARTIAL_TAG_REGEX, "")
                .trim()

        fun parseEmotionTag(response: String): Pair<String, PetExpression> {
            val match = EMOTION_TAG_REGEX.findAll(response).lastOrNull()
            val expression = match?.groupValues?.get(1)?.let { PetExpression.fromTag(it) }
                ?: PetExpression.HAPPY
            val cleanText = response.replace(EMOTION_TAG_REGEX, "").trim()
            return cleanText to expression
        }
    }
}
