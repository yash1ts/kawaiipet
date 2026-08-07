package com.kawaiipet.app.llm

import android.util.Log
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
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
        shortTermParagraph: String,
        onPartial: (String) -> Unit,
    ): String = withContext(Dispatchers.Default) {
        val engine = smolLm.ensureReady()

        val petName = prefs.getPetName()
        val personality = prefs.getPersonalityPrompt()
        val personalitySystemPrompt = LlmPromptDefaults.buildSystemPrompt(
            petName = petName,
            personality = personality,
            memoryParagraph = memoryParagraph,
            shortTermParagraph = shortTermParagraph,
        )

        val systemPrompt = if (LlmPromptDefaults.PURE_SMOLLM_DEBUG) {
            "You are a helpful assistant."
        } else {
            personalitySystemPrompt
        }

        val latestUser = messages.lastOrNull { it.role == Role.USER }?.text?.trim().orEmpty()
            .take(LlmPromptDefaults.MAX_CHARS_PER_TURN)

        Log.d(
            TAG,
            "chat pureDebug=${LlmPromptDefaults.PURE_SMOLLM_DEBUG} " +
                "systemLen=${systemPrompt.length} " +
                "memoryLen=${memoryParagraph.length} shortTermLen=${shortTermParagraph.length} " +
                "latestLen=${latestUser.length}",
        )

        val config = ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            // Continuity comes from the short-term paragraph in the system prompt — not raw turns.
            initialMessages = emptyList(),
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

    override suspend fun consolidateMemory(
        currentMemory: String,
        userText: String,
        assistantText: String,
    ): String = runUtilityConsolidate(
        system = MEMORY_UTILITY_SYSTEM,
        currentLabel = "Current memory",
        currentText = LlmPromptDefaults.clampMemoryParagraph(currentMemory),
        userText = userText,
        assistantText = assistantText,
        clamp = LlmPromptDefaults::clampMemoryParagraph,
        logTag = "consolidateMemory",
    )

    override suspend fun consolidateShortTerm(
        currentShortTerm: String,
        userText: String,
        assistantText: String,
    ): String = runUtilityConsolidate(
        system = SHORT_TERM_UTILITY_SYSTEM,
        currentLabel = "Current recent conversation",
        currentText = LlmPromptDefaults.clampShortTermParagraph(currentShortTerm),
        userText = userText,
        assistantText = assistantText,
        clamp = LlmPromptDefaults::clampShortTermParagraph,
        logTag = "consolidateShortTerm",
    )

    private suspend fun runUtilityConsolidate(
        system: String,
        currentLabel: String,
        currentText: String,
        userText: String,
        assistantText: String,
        clamp: (String) -> String,
        logTag: String,
    ): String = withContext(Dispatchers.Default) {
        val engine = smolLm.ensureReady()
        val user = LlmPromptDefaults.sanitizeParagraph(userText).take(240)
        val assistant = LlmPromptDefaults.sanitizeParagraph(assistantText).take(240)
        if (user.isEmpty() && assistant.isEmpty()) return@withContext ""

        val currentClean = LlmPromptDefaults.sanitizeParagraph(currentText)
        val userTurn = buildString {
            append(currentLabel).append(":\n")
            append(if (currentClean.isBlank()) "(empty)" else currentClean)
            append("\n\nLatest exchange:\n")
            if (user.isNotEmpty()) append("Friend: ").append(user).append('\n')
            if (assistant.isNotEmpty()) append("Pet: ").append(assistant).append('\n')
            append("\nWrite the updated paragraph now (or NONE). Drop empty/placeholder text.")
        }

        Log.d(
            TAG,
            "$logTag currentLen=${currentText.length} userLen=${user.length} " +
                "assistantLen=${assistant.length}",
        )

        val config = ConversationConfig(
            systemInstruction = Contents.of(system),
            initialMessages = emptyList(),
            samplerConfig = SamplerConfig(
                topK = LlmPromptDefaults.UTILITY_TOP_K,
                topP = LlmPromptDefaults.UTILITY_TOP_P,
                temperature = LlmPromptDefaults.UTILITY_TEMPERATURE,
            ),
            maxOutputToken = LlmPromptDefaults.UTILITY_MAX_OUTPUT_TOKENS,
        )

        engine.createConversation(config).use { conversation ->
            val accumulated = StringBuilder()
            conversation.sendMessageAsync(userTurn).collect { message ->
                val delta = message.toString()
                if (delta.isNotEmpty()) accumulated.append(delta)
            }
            sanitizeUtilityParagraphOutput(accumulated.toString(), clamp)
        }
    }

    companion object {
        private const val TAG = "SmolLmLlmService"

        private val MEMORY_UTILITY_SYSTEM =
            "You maintain a memory note about a human for an intelligent companion creature. " +
                "Output ONE plain paragraph, max ${LlmPromptDefaults.MAX_MEMORY_WORDS} words. " +
                "Keep stable personal facts (name, likes, people, places, ongoing topics). " +
                "Merge with current memory; drop empty/placeholder text, noise, lists, role labels, " +
                "and phrases like \"the user\". Do not invent facts. " +
                "If nothing worth remembering, reply exactly: NONE"

        private val SHORT_TERM_UTILITY_SYSTEM =
            "You maintain a recent-conversation note for an intelligent companion creature. " +
                "Output ONE plain paragraph, max ${LlmPromptDefaults.MAX_SHORT_TERM_WORDS} words. " +
                "Summarize what was just discussed for reply continuity (topics, questions, tone). " +
                "Prefer the newest exchange; drop empty/placeholder text, role labels, lists, " +
                "canned lines, and long-term biography. Do not invent content. " +
                "If nothing useful for continuity, reply exactly: NONE"

        private fun sanitizeUtilityParagraphOutput(
            raw: String,
            clamp: (String) -> String,
        ): String {
            val cleaned = LlmPromptDefaults.sanitizeParagraph(raw)
            if (cleaned.isEmpty()) return ""
            return clamp(cleaned)
        }
    }
}
