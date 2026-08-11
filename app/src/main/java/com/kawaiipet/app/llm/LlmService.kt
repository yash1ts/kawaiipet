package com.kawaiipet.app.llm

interface LlmService {
    /**
     * Chat as the pet. [messages] is short-term history ending with the latest user turn.
     * [memoryParagraph] is optional RAG context attached to the user turn.
     * [onPartial] gets cumulative streamed text (may suspend so TTS can start mid-generation).
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        memoryParagraph: String = "",
        onPartial: suspend (String) -> Unit = {},
    ): String

    /** Best-effort model warmup so the first [chat] of a session doesn't pay init cost. */
    suspend fun warmUp() {}

    /** Drop any sticky on-device conversation / KV cache (e.g. overlay closed). */
    suspend fun resetSession() {}
}

data class ChatMessage(
    val role: Role,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class Role { USER, ASSISTANT }
