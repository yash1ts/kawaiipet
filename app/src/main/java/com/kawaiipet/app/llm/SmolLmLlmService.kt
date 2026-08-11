package com.kawaiipet.app.llm

import android.util.Log
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.kawaiipet.app.util.PreferenceManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class SmolLmLlmService @Inject constructor(
    private val smolLm: SmolLmAvailability,
    private val prefs: PreferenceManager,
) : LlmService {

    override suspend fun warmUp() {
        smolLm.warmUp()
    }

    override suspend fun chat(
        messages: List<ChatMessage>,
        memoryParagraph: String,
        onPartial: (String) -> Unit,
    ): String = withContext(Dispatchers.Default) {
        val engine = smolLm.ensureReady()

        val petName = prefs.getPetName()
        val personality = prefs.getPersonalityPrompt()
        val personalitySystemPrompt = LlmPromptDefaults.buildSystemPrompt(
            petName = petName,
            personality = personality,
            memoryParagraph = memoryParagraph,
        )

        val systemPrompt = if (LlmPromptDefaults.PURE_SMOLLM_DEBUG) {
            "You are a helpful assistant."
        } else {
            personalitySystemPrompt
        }

        val latestUser = messages.lastOrNull { it.role == Role.USER }?.text?.trim().orEmpty()
            .take(LlmPromptDefaults.MAX_CHARS_PER_TURN)

        val historyForModel = if (LlmPromptDefaults.PURE_SMOLLM_DEBUG) {
            emptyList()
        } else {
            buildHistoryMessages(messages)
        }

        Log.d(
            TAG,
            "chat pureDebug=${LlmPromptDefaults.PURE_SMOLLM_DEBUG} " +
                "systemLen=${systemPrompt.length} historyTurns=${historyForModel.size} " +
                "memoryLen=${memoryParagraph.length} latestLen=${latestUser.length}",
        )

        val config = ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            initialMessages = historyForModel,
            samplerConfig = SamplerConfig(
                topK = LlmPromptDefaults.SAMPLER_TOP_K,
                topP = LlmPromptDefaults.SAMPLER_TOP_P,
                temperature = LlmPromptDefaults.SAMPLER_TEMPERATURE,
            ),
            maxOutputToken = LlmPromptDefaults.MAX_OUTPUT_TOKENS,
        )

        engine.createConversation(config).use { conversation ->
            val accumulated = StringBuilder()
            conversation.sendMessageAsync(latestUser).collect { message ->
                val delta = message.toString()
                if (delta.isNotEmpty()) {
                    accumulated.append(delta)
                    onPartial(accumulated.toString())
                }
            }
            val text = accumulated.toString().trim()
            if (text.isBlank()) {
                error("Empty response from SmolLM")
            }
            text
        }
    }

    override suspend fun consolidateSession(
        currentMemory: String,
        friendLines: List<String>,
    ): String = withContext(Dispatchers.Default) {
        val lines = friendLines
            .map { LlmPromptDefaults.sanitizeParagraph(it).take(120) }
            .filter { it.isNotEmpty() && LlmPromptDefaults.looksLikeMemorableUserTurn(it) }
            .distinct()
            .take(MAX_SESSION_FACT_LINES)
        if (lines.isEmpty()) return@withContext ""

        val engine = smolLm.ensureReady()
        val currentClean = LlmPromptDefaults.sanitizeMemoryParagraph(currentMemory)
        val userTurn = buildString {
            if (currentClean.isNotEmpty()) {
                append(currentClean).append('\n')
            }
            for (line in lines) {
                append("+ ").append(line).append('\n')
            }
            append("Short:")
        }

        Log.d(
            TAG,
            "consolidateSession currentLen=${currentClean.length} factLines=${lines.size}",
        )

        val config = ConversationConfig(
            systemInstruction = Contents.of(MEMORY_UTILITY_SYSTEM),
            initialMessages = emptyList(),
            samplerConfig = SamplerConfig(
                topK = LlmPromptDefaults.UTILITY_TOP_K,
                topP = LlmPromptDefaults.UTILITY_TOP_P,
                temperature = LlmPromptDefaults.UTILITY_TEMPERATURE,
            ),
            maxOutputToken = LlmPromptDefaults.UTILITY_MAX_OUTPUT_TOKENS,
        )

        engine.createConversation(config).use { conversation ->
            val raw = conversation.sendMessage(userTurn).toString().trim()
            Log.d(TAG, "consolidateSession raw (${raw.length}): ${raw.take(180).replace('\n', ' ')}")
            val cleaned = LlmPromptDefaults.sanitizeMemoryParagraph(raw)
            if (cleaned.isEmpty()) {
                Log.d(TAG, "consolidateSession rejected empty/junk raw")
                return@withContext ""
            }
            val clamped = LlmPromptDefaults.clampMemoryParagraph(cleaned)
            if (clamped.isEmpty()) return@withContext ""
            if (LlmPromptDefaults.isSameMemory(clamped, currentClean)) {
                Log.d(TAG, "consolidateSession unchanged (same as current after dedupe)")
                return@withContext ""
            }
            val clampedKey = LlmPromptDefaults.normalizeMemoryKey(clamped)
            val currentKey = LlmPromptDefaults.normalizeMemoryKey(currentClean)
            val absorbedNew = lines.any { line ->
                val key = LlmPromptDefaults.normalizeMemoryKey(line)
                key.isNotEmpty() && clampedKey.contains(key.take(24))
            }
            val grew = clampedKey.length > currentKey.length + 8
            if (currentKey.isNotEmpty() && !absorbedNew && !grew) {
                Log.d(TAG, "consolidateSession ignored (no new fact absorbed)")
                return@withContext ""
            }
            Log.d(TAG, "consolidateSession ok (${clamped.length}): ${clamped.take(160)}")
            clamped
        }
    }

    companion object {
        private const val TAG = "SmolLmLlmService"
        private const val MAX_SESSION_FACT_LINES = 16

        // Compress durable facts only — never define/explain topics from the chat.
        private val MEMORY_UTILITY_SYSTEM =
            "Write ONE short fact paragraph about the friend " +
                "(max ${LlmPromptDefaults.MAX_MEMORY_WORDS} words). " +
                "Merge old facts with each new \"+\" line from the session. " +
                "Each fact once — never repeat a sentence. " +
                "Keep only: name, likes/dislikes, people, places, job, plans. " +
                "Do not explain or define. If nothing new: NONE"

        /** Prior messages only (excludes the latest user turn already sent as the prompt). */
        private fun buildHistoryMessages(messages: List<ChatMessage>): List<Message> {
            if (messages.isEmpty()) return emptyList()
            val history = messages.dropLastWhile { it.role != Role.USER }.dropLast(1)
            val capped = if (history.size > LlmPromptDefaults.MAX_SHORT_TERM_MESSAGES) {
                history.takeLast(LlmPromptDefaults.MAX_SHORT_TERM_MESSAGES)
            } else {
                history
            }
            return capped.mapNotNull { msg ->
                val text = msg.text.trim().take(LlmPromptDefaults.MAX_CHARS_PER_TURN)
                if (text.isEmpty()) return@mapNotNull null
                when (msg.role) {
                    Role.USER -> Message.user(text)
                    Role.ASSISTANT -> Message.model(text)
                }
            }
        }
    }
}
