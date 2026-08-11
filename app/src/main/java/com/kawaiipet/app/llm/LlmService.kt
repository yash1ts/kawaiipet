package com.kawaiipet.app.llm

interface LlmService {
    /**
     * Chat as the pet. [messages] is short-term history ending with the latest user turn.
     * [memoryParagraph] is optional RAG-retrieved context injected into the system prompt.
     * [onPartial] gets streamed raw tokens.
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        memoryParagraph: String = "",
        onPartial: (String) -> Unit = {},
    ): String

    /** Best-effort model warmup so the first [chat] of a session doesn't pay init cost. */
    suspend fun warmUp() {}
}

data class ChatMessage(
    val role: Role,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class Role { USER, ASSISTANT }
