package com.kawaiipet.app.memory

import com.kawaiipet.app.llm.ChatMessage
import com.kawaiipet.app.llm.LlmPromptDefaults
import com.kawaiipet.app.llm.Role
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recent chat as the actual message list (not a summary), capped by message count.
 */
@Singleton
class ShortTermMemory @Inject constructor() {

    private val messages = mutableListOf<ChatMessage>()

    @Synchronized
    fun addMessage(message: ChatMessage) {
        val text = message.text.trim().take(LlmPromptDefaults.MAX_CHARS_PER_TURN)
        if (text.isEmpty()) return
        messages.add(message.copy(text = text))
        while (messages.size > LlmPromptDefaults.MAX_SHORT_TERM_MESSAGES) {
            messages.removeAt(0)
        }
    }

    @Synchronized
    fun getMessages(): List<ChatMessage> = messages.toList()

    /** Drop a failed turn so the model does not learn from it. */
    @Synchronized
    fun removeLastUserMessage() {
        val idx = messages.indexOfLast { it.role == Role.USER }
        if (idx >= 0) messages.removeAt(idx)
    }

    @Synchronized
    fun clear() {
        messages.clear()
    }

    @Synchronized
    fun size(): Int = messages.size
}
