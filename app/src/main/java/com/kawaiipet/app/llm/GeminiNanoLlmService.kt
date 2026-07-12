package com.kawaiipet.app.llm

import android.util.Log
import com.google.mlkit.genai.prompt.PromptPrefix
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.kawaiipet.app.util.PreferenceManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeminiNanoLlmService @Inject constructor(
    private val nano: GeminiNanoAvailability,
    private val prefs: PreferenceManager,
    private val foregroundGate: AiForegroundGate,
) : LlmService {

    /**
     * Warms Nano while the user is still talking. Requires a resumed foreground
     * host (AICore blocks background work); at tap time [AiForegroundGate] reuses
     * the already-resumed AiTriggerActivity, so this adds no extra UI.
     */
    override suspend fun warmUp() {
        foregroundGate.withForeground {
            nano.prewarm()
        }
    }

    override suspend fun chat(
        messages: List<ChatMessage>,
        factTexts: List<String>,
        onPartial: (String) -> Unit,
    ): String =
        foregroundGate.withForeground {
            nano.ensureReady()
            val petName = prefs.getPetName()
            val personality = prefs.getPersonalityPrompt()

            val systemPrompt = buildSystemPrompt(petName, personality, factTexts)
            val conversation = formatConversation(messages)
            val userTurn = buildString {
                appendLine("Conversation:")
                appendLine(conversation)
                append("Reply as the pet now. End with exactly one emotion tag.")
            }

            Log.d(TAG, "chat promptLen=${userTurn.length} prefixLen=${systemPrompt.length}")

            // Streaming inference: tokens reach the UI as they generate instead of
            // after the full response (each emitted chunk carries new delta text).
            val accumulated = StringBuilder()
            nano.model.generateContentStream(
                generateContentRequest(TextPart(userTurn)) {
                    promptPrefix = PromptPrefix(systemPrompt)
                    temperature = 0.85f
                    topK = 40
                    candidateCount = 1
                    maxOutputTokens = 120
                },
            ).collect { chunk ->
                val delta = chunk.candidates.firstOrNull()?.text.orEmpty()
                if (delta.isNotEmpty()) {
                    accumulated.append(delta)
                    onPartial(accumulated.toString())
                }
            }
            val text = accumulated.toString().trim()
            if (text.isBlank()) {
                error("Empty response from on-device AI")
            }
            text
        }

    override suspend fun extractFacts(conversationSnippet: String): List<String> {
        if (conversationSnippet.isBlank()) return emptyList()
        return foregroundGate.withForeground {
            nano.ensureReady()

            val prompt = EXTRACT_PROMPT + conversationSnippet
            val response = nano.model.generateContent(
                generateContentRequest(TextPart(prompt)) {
                    temperature = 0.2f
                    topK = 20
                    candidateCount = 1
                    maxOutputTokens = 120
                },
            )
            val raw = response.candidates.firstOrNull()?.text.orEmpty()
            raw
                .lineSequence()
                .map { it.trim() }
                .filter { line ->
                    line.isNotEmpty() &&
                        !line.startsWith("#") &&
                        !line.startsWith("```") &&
                        line.equals("NONE", ignoreCase = true).not()
                }
                .toList()
        }
    }

    companion object {
        private const val TAG = "GeminiNanoLlmService"

        private const val EXTRACT_PROMPT =
            "You are a fact extractor. Read the conversation below and list only concrete personal facts about the user (name, preferences, hobbies, relationships, etc.).\n" +
                "Rules:\n" +
                "- One fact per line, written as a short plain-English sentence.\n" +
                "- Do NOT output any labels, tags, headers, or meta-text (no \"output:\", \"instruction:\", \"thought\", etc.).\n" +
                "- If there are no personal facts, respond with exactly: NONE\n" +
                "\n" +
                "Conversation:\n"

        fun buildSystemPrompt(petName: String, personality: String, factTexts: List<String>): String {
            val name = petName.trim().ifEmpty { "Mochi" }
            val p = personality.trim().ifEmpty { LlmPromptDefaults.DEFAULT_PERSONALITY }
            val sb = StringBuilder()
            sb.append("Your name is ").append(name).append(".\n\n").append(p)
            if (factTexts.isNotEmpty()) {
                sb.append("\n\nThings you remember about the user:\n")
                for (f in factTexts) {
                    sb.append("- ").append(f).append('\n')
                }
            }
            sb.append(LlmPromptDefaults.stayInCharacterBlock(name))
            return sb.toString()
        }

        private fun formatConversation(messages: List<ChatMessage>): String {
            // Keep recent turns under the ~4k token budget.
            val recent = if (messages.size > 12) messages.takeLast(12) else messages
            return recent.joinToString("\n") { m ->
                val role = if (m.role == Role.USER) "User" else "Pet"
                "$role: ${m.text}"
            }
        }
    }
}
