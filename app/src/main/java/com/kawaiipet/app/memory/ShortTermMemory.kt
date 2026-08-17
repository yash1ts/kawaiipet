package com.kawaiipet.app.memory

import com.kawaiipet.app.llm.ChatMessage
import com.kawaiipet.app.llm.LlmPromptDefaults
import com.kawaiipet.app.llm.Role
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recent chat as the actual message list (not a summary), capped by message count.
 * Trims by full user/assistant turns so history never starts mid-pair (odd priors
 * confuse a small chat model into echoing the user).
 */
@Singleton
class ShortTermMemory @Inject constructor() {

    private val messages = mutableListOf<ChatMessage>()

    @Synchronized
    fun addMessage(message: ChatMessage) {
        val text = message.text.trim().take(LlmPromptDefaults.MAX_CHARS_PER_TURN)
        if (text.isEmpty()) return
        messages.add(message.copy(text = text))
        trimToCapLocked()
    }

    @Synchronized
    fun getMessages(): List<ChatMessage> = messages.toList()

    /** Drop a failed turn so the model does not learn from it. */
    @Synchronized
    fun removeLastUserMessage() {
        val idx = messages.indexOfLast { it.role == Role.USER }
        if (idx >= 0) messages.removeAt(idx)
        trimToCapLocked()
    }

    @Synchronized
    fun clear() {
        messages.clear()
    }

    @Synchronized
    fun size(): Int = messages.size

    private fun trimToCapLocked() {
        val max = LlmPromptDefaults.MAX_SHORT_TERM_MESSAGES
        // Prefer even length ending on the latest messages; drop oldest turn (2 msgs).
        while (messages.size > max) {
            if (messages.size >= 2 &&
                messages[0].role == Role.USER &&
                messages[1].role == Role.ASSISTANT
            ) {
                messages.removeAt(0)
                messages.removeAt(0)
            } else {
                messages.removeAt(0)
            }
        }
        // If we still start on an assistant leftover, drop it.
        while (messages.isNotEmpty() && messages.first().role == Role.ASSISTANT) {
            messages.removeAt(0)
        }
    }
}
