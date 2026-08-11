package com.kawaiipet.app.llm

import android.util.Log
import com.kawaiipet.app.memory.MemoryPipeline
import com.kawaiipet.app.memory.ShortTermMemory
import com.kawaiipet.app.pet.PetExpression
import com.kawaiipet.app.tools.PetToolCall
import com.kawaiipet.app.tools.PetToolIntent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationManager @Inject constructor(
    private val llmService: LlmService,
    private val shortTermMemory: ShortTermMemory,
    private val memoryPipeline: MemoryPipeline,
) {
    data class LlmResponse(
        val text: String,
        val expression: PetExpression,
        /** Model tool call: open/play app, optional search query. */
        val toolCall: PetToolCall? = null,
    ) {
        @Deprecated("Use toolCall", ReplaceWith("toolCall?.intent"))
        val openApp: PetToolIntent? get() = toolCall?.intent
    }

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
            val toolCall = parseToolCall(rawResponse)
            val spoken = cleanText.trim().ifBlank { rawResponse.trim() }
            for (s in streamer.flush(spoken)) {
                onSpeakableSentence(s)
            }
            if (!streamer.hasEmitted && spoken.isNotBlank()) {
                onSpeakableSentence(spoken)
            }
            return LlmResponse(spoken, expression, toolCall)
        }

        // Keyword intent path: confirm + launch without involving the chat LLM.
        if (LlmPromptDefaults.looksLikeActionIntent(text)) {
            return processActionIntent(text, onPartial, onSpeakableSentence)
        }

        shortTermMemory.addMessage(ChatMessage(Role.USER, text))
        val messages = shortTermMemory.getMessages()
        // Never block TTFT on LTM retrieval — index still happens after the turn.
        val memoryContext = ""

        val streamer = SpokenSentenceStreamer()
        var lastDisplay = ""

        val rawResponse = llmService.chat(
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
        Log.d(TAG, "llm raw (${rawResponse.length} chars): ${rawResponse.toOneLineLog()}")

        val toolCall = parseToolCall(rawResponse)
        if (toolCall != null) {
            Log.i(
                TAG,
                "toolCall from chat: ${toolCall.intent.id} query=${toolCall.query?.take(60)}",
            )
        }
        val (cleanText, expression) = parseEmotionTag(rawResponse)
        // Light cleanup only — Qwen is coherent enough without SmolLM-era quality gates.
        val spokenText = lightCleanReply(cleanText)
            .ifBlank { lastDisplay.trim() }
            .ifBlank { LlmPromptDefaults.DIDNT_CATCH_REPLY }

        if (streamer.hasEmitted) {
            val flushSource = when {
                spokenText != LlmPromptDefaults.DIDNT_CATCH_REPLY -> spokenText
                else -> lastDisplay.ifBlank { cleanText }
            }
            for (s in streamer.flush(flushSource, text)) {
                onSpeakableSentence(s)
            }
        } else if (spokenText.isNotBlank() &&
            spokenText != LlmPromptDefaults.DIDNT_CATCH_REPLY
        ) {
            onSpeakableSentence(spokenText)
        }

        Log.i(
            TAG,
            "llm parsed: expression=$expression tool=${toolCall?.intent?.id} " +
                "query=${toolCall?.query?.take(40)} cleanLen=${cleanText.length} " +
                "spoken=${spokenText.toOneLineLog()} streamed=${streamer.hasEmitted}",
        )

        if (spokenText.isNotBlank()) {
            shortTermMemory.addMessage(ChatMessage(Role.ASSISTANT, spokenText))
            if (spokenText != LlmPromptDefaults.DIDNT_CATCH_REPLY &&
                !LlmPromptDefaults.isCannedFallback(spokenText)
            ) {
                memoryPipeline.scheduleIndexTurn(text, spokenText)
            }
        }

        return LlmResponse(spokenText, expression, toolCall)
    }

    fun clearConversation() {
        shortTermMemory.clear()
    }

    /** Clears short-term chat and drops the sticky LiteRT conversation / KV cache. */
    suspend fun clearConversationFully() {
        shortTermMemory.clear()
        llmService.resetSession()
    }

    /**
     * Keyword/NLU action path: immediate spoken confirm + launch from utterance parse.
     * No LLM involvement (chat model is buddy-chat only).
     */
    private suspend fun processActionIntent(
        text: String,
        onPartial: suspend (String) -> Unit,
        onSpeakableSentence: suspend (String) -> Unit,
    ): LlmResponse {
        shortTermMemory.addMessage(ChatMessage(Role.USER, text))
        val toolCall = PetToolIntent.detectToolCallFromUserUtterance(text)
        val immediate = if (toolCall != null) {
            confirmationForTool(toolCall)
        } else {
            "On it!"
        }
        Log.i(
            TAG,
            "action intent immediate=${immediate.toOneLineLog()} " +
                "tool=${toolCall?.intent?.id} query=${toolCall?.query?.take(60)}",
        )
        onPartial(immediate)
        onSpeakableSentence(immediate)

        shortTermMemory.addMessage(ChatMessage(Role.ASSISTANT, immediate))
        return LlmResponse(immediate, PetExpression.HAPPY, toolCall)
    }

    private fun confirmationForTool(call: PetToolCall): String {
        val label = call.intent.label
        val q = call.query?.trim().orEmpty()
        return if (q.isNotEmpty()) {
            "On it — searching $label for $q!"
        } else {
            "Opening $label!"
        }
    }

    /** Strip role labels / extra whitespace; soft-cap length for TTS. */
    private fun lightCleanReply(raw: String): String {
        var t = raw.trim().trim('"')
        if (t.isEmpty()) return ""
        t = Regex("(?i)^(you|assistant|pet|mochi|human|friend)\\s*(says)?\\s*:\\s*")
            .replace(t, "")
            .trim()
        t = t
            .replace(Regex("[\\t\\r\\n]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (t.length <= LlmPromptDefaults.MAX_SPOKEN_CHARS) return t
        val window = t.take(LlmPromptDefaults.MAX_SPOKEN_CHARS)
        val lastStop = window.indexOfLast { it == '.' || it == '!' || it == '?' }
        return if (lastStop >= window.length / 3) {
            window.take(lastStop + 1).trim()
        } else {
            window.trimEnd(',', ';', ':', ' ').trim()
        }
    }

    companion object {
        private const val TAG = "ConversationManager"
        private const val LOG_SNIP_LEN = 200

        private fun String.toOneLineLog(): String =
            replace('\n', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(LOG_SNIP_LEN)

        private val EMOTION_TAG_REGEX =
            "\\[(happy|sad|angry|thinking|idle|listening|talking|sleeping)\\]"
                .toRegex(RegexOption.IGNORE_CASE)

        /**
         * Model tool tags:
         * - `[play:youtubemusic:The Mandalorian]`
         * - `[open:spotify:Bohemian Rhapsody]`
         * - `[open:chrome]`
         */
        private val TOOL_CALL_TAG_REGEX =
            "\\[(play|open):([a-z0-9]+)(?::([^\\]]+))?\\]".toRegex(RegexOption.IGNORE_CASE)

        private val TRAILING_PARTIAL_TAG_REGEX = "\\[[^\\]]*$".toRegex()

        fun sanitizeForDisplay(partialRaw: String): String =
            LlmPromptDefaults.sanitizeModelSpeech(
                partialRaw
                    .replace(TOOL_CALL_TAG_REGEX, "")
                    .replace(EMOTION_TAG_REGEX, ""),
            )
                .replace(TRAILING_PARTIAL_TAG_REGEX, "")
                .trim()

        fun parseToolCall(response: String): PetToolCall? {
            val match = TOOL_CALL_TAG_REGEX.findAll(response).lastOrNull() ?: return null
            val intent = PetToolIntent.fromId(match.groupValues[2]) ?: return null
            val query = match.groupValues.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }
            return PetToolCall(intent = intent, query = query)
        }

        fun parseEmotionTag(response: String): Pair<String, PetExpression> {
            // Strip tool tags first so they never reach TTS / history.
            val withoutTools = response.replace(TOOL_CALL_TAG_REGEX, " ")
            val match = EMOTION_TAG_REGEX.findAll(withoutTools).lastOrNull()
            val expression = match?.groupValues?.get(1)?.let { PetExpression.fromTag(it) }
                ?: PetExpression.HAPPY
            val cleanText = LlmPromptDefaults.stripSpeechFormatting(
                withoutTools.replace(EMOTION_TAG_REGEX, ""),
            )
            return cleanText to expression
        }
    }
}
