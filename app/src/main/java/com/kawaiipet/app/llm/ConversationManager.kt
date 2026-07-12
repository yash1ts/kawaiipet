package com.kawaiipet.app.llm

import android.util.Log
import com.kawaiipet.app.memory.FactMatcher
import com.kawaiipet.app.memory.MemoryRepository
import com.kawaiipet.app.memory.ShortTermMemory
import com.kawaiipet.app.pet.PetExpression
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationManager @Inject constructor(
    private val llmService: LlmService,
    private val shortTermMemory: ShortTermMemory,
    private val memoryRepository: MemoryRepository,
    private val factExtractor: FactExtractor,
    private val factMatcher: FactMatcher,
) {
    data class LlmResponse(val text: String, val expression: PetExpression)

    /** Fire while the user is still speaking so the model is warm when [processUserInput] runs. */
    suspend fun warmUpLlm() {
        llmService.warmUp()
    }

    /**
     * [onPartial] receives display-ready partial text (emotion tags stripped) as the
     * model streams, so the bubble can fill in before generation completes.
     */
    suspend fun processUserInput(
        text: String,
        onPartial: (String) -> Unit = {},
    ): LlmResponse {
        shortTermMemory.addMessage(ChatMessage(Role.USER, text))

        val keywords = factMatcher.extractKeywords(text)
        val relevantFacts = memoryRepository.findFactsByKeywords(keywords)

        relevantFacts.forEach { fact ->
            memoryRepository.touchFact(fact.id)
        }

        val messages = shortTermMemory.getMessages()
        val factTexts = relevantFacts.map { it.factText }

        val rawResponse = llmService.chat(messages, factTexts) { partialRaw ->
            val display = sanitizeForDisplay(partialRaw)
            if (display.isNotEmpty()) onPartial(display)
        }
        Log.d(
            TAG,
            "llm raw (${rawResponse.length} chars): ${rawResponse.toOneLineLog()}",
        )
        val (cleanText, expression) = parseEmotionTag(rawResponse)
        val spokenText = ensureSpeakable(cleanText, rawResponse)
        Log.d(
            TAG,
            "llm parsed: expression=$expression cleanLen=${cleanText.length} " +
                "clean=${cleanText.toOneLineLog()} → spokenLen=${spokenText.length} spoken=${spokenText.toOneLineLog()}",
        )

        shortTermMemory.addMessage(ChatMessage(Role.ASSISTANT, spokenText))

        factExtractor.extractAndStoreAsync(text, spokenText)

        return LlmResponse(spokenText, expression)
    }

    fun clearConversation() {
        shortTermMemory.clear()
    }

    /**
     * [AudioPipeline.speak] skips blank strings. If the model returns only an emotion tag, we still speak a short line.
     */
    private fun ensureSpeakable(cleanText: String, rawResponse: String): String {
        val t = cleanText.trim()
        if (t.any { it.isLetterOrDigit() }) return t
        Log.w(
            TAG,
            "No speakable text after emotion tags (raw=${rawResponse.take(160).replace('\n', ' ')})",
        )
        return DEFAULT_SPOKEN_REPLY
    }

    companion object {
        private const val TAG = "ConversationManager"
        private const val DEFAULT_SPOKEN_REPLY = "Okay!"
        private const val LOG_SNIP_LEN = 200

        private fun String.toOneLineLog(): String =
            replace('\n', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(LOG_SNIP_LEN)

        private val EMOTION_TAG_REGEX = "\\[(happy|sad|angry|thinking|idle|listening|talking|sleeping)\\]"
            .toRegex(RegexOption.IGNORE_CASE)

        /** A possibly-unfinished emotion tag at the end of streamed text, e.g. "…hi! [hap". */
        private val TRAILING_PARTIAL_TAG_REGEX = "\\[[^\\]]*$".toRegex()

        /** Strips complete emotion tags plus any partially generated trailing tag. */
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
