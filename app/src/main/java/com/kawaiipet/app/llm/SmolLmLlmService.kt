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

    companion object {
        private const val TAG = "SmolLmLlmService"

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
