package com.kawaiipet.app.llm

interface LlmService {
    /**
     * [factTexts] are local-memory snippets only; the server merges them into the system prompt.
     * [onPartial] receives the accumulated raw response as tokens stream in, so callers can
     * display text before generation finishes.
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        factTexts: List<String>,
        onPartial: (String) -> Unit = {},
    ): String

    suspend fun extractFacts(conversationSnippet: String): List<String>

    /** Best-effort model warmup so the first [chat] of a session doesn't pay init cost. */
    suspend fun warmUp() {}
}

data class ChatMessage(
    val role: Role,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class Role { USER, ASSISTANT }
