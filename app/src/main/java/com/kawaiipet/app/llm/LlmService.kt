package com.kawaiipet.app.llm

interface LlmService {
    /**
     * Chat as the pet. [memoryParagraph] / [shortTermParagraph] are capped notes in the system
     * prompt (long-term facts vs recent chat). [onPartial] gets streamed raw tokens.
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        memoryParagraph: String = "",
        shortTermParagraph: String = "",
        onPartial: (String) -> Unit = {},
    ): String

    /**
     * Separate utility call (no pet persona / no chat history): rewrite memory as one short
     * sanitized paragraph. Returns empty string if nothing useful should be kept (`NONE`).
     */
    suspend fun consolidateMemory(
        currentMemory: String,
        userText: String,
        assistantText: String,
    ): String

    /**
     * Same utility style as [consolidateMemory], but for recent conversation continuity
     * (topics just discussed), not stable biography.
     */
    suspend fun consolidateShortTerm(
        currentShortTerm: String,
        userText: String,
        assistantText: String,
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
