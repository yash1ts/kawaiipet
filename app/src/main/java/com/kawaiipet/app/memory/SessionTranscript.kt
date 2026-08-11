package com.kawaiipet.app.memory

import com.kawaiipet.app.llm.LlmPromptDefaults
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records successful chat turns during a pet session for later long-term memory flush.
 * Independent of [ShortTermMemory] (which is cleared on Start Pet for a fresh context).
 */
@Singleton
class SessionTranscript @Inject constructor() {

    data class Turn(val userText: String, val assistantText: String)

    private val turns = mutableListOf<Turn>()

    @Synchronized
    fun record(userText: String, assistantText: String) {
        val user = userText.trim().take(LlmPromptDefaults.MAX_CHARS_PER_TURN)
        val assistant = assistantText.trim().take(LlmPromptDefaults.MAX_CHARS_PER_TURN)
        if (user.isEmpty() || assistant.isEmpty()) return
        if (LlmPromptDefaults.isCannedFallback(assistant)) return
        turns.add(Turn(user, assistant))
        while (turns.size > MAX_SESSION_TURNS) {
            turns.removeAt(0)
        }
    }

    @Synchronized
    fun isEmpty(): Boolean = turns.isEmpty()

    @Synchronized
    fun size(): Int = turns.size

    /** Snapshot and clear so a flush runs once. */
    @Synchronized
    fun snapshotAndClear(): List<Turn> {
        if (turns.isEmpty()) return emptyList()
        val copy = turns.toList()
        turns.clear()
        return copy
    }

    companion object {
        private const val MAX_SESSION_TURNS = 40
    }
}
