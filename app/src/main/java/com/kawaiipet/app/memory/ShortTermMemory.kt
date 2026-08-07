package com.kawaiipet.app.memory

import android.util.Log
import com.kawaiipet.app.llm.LlmPromptDefaults
import com.kawaiipet.app.llm.LlmService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Recent chat context as one capped, sanitized paragraph (not a raw turn list).
 * Consolidated after each successful reply so SmolLM does not copy prior junk.
 */
@Singleton
class ShortTermMemory @Inject constructor(
    private val llmService: LlmService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    private var paragraph: String = ""

    fun getParagraph(): String =
        LlmPromptDefaults.clampShortTermParagraph(paragraph)

    @Synchronized
    fun clear() {
        paragraph = ""
    }

    /** Fire-and-forget: rewrite recent-chat paragraph after a good turn. */
    fun scheduleConsolidate(userText: String, assistantText: String) {
        val user = LlmPromptDefaults.sanitizeParagraph(userText)
        val assistant = LlmPromptDefaults.sanitizeParagraph(assistantText)
        if (user.isEmpty() || assistant.isEmpty()) return
        if (LlmPromptDefaults.isCannedFallback(assistantText)) return
        scope.launch {
            runCatching { consolidateNow(user, assistant) }
                .onFailure { Log.w(TAG, "Short-term consolidate failed", it) }
        }
    }

    suspend fun consolidateNow(userText: String, assistantText: String) {
        mutex.withLock {
            val current = getParagraph()
            val updated = runCatching {
                llmService.consolidateShortTerm(current, userText, assistantText)
            }.getOrDefault("")
            val next = when {
                updated.isNotBlank() -> updated
                else -> fallbackMerge(current, userText, assistantText)
            }
            val clamped = LlmPromptDefaults.clampShortTermParagraph(next)
            if (clamped == current) return
            // Empty after sanitize → clear stale short-term junk.
            paragraph = clamped
            Log.d(
                TAG,
                if (clamped.isEmpty()) "Short-term cleared (empty after sanitize)"
                else "Short-term updated (${clamped.length} chars): ${clamped.take(120)}",
            )
        }
    }

    companion object {
        private const val TAG = "ShortTermMemory"

        /** Deterministic merge when the utility LLM returns nothing useful. */
        internal fun fallbackMerge(
            current: String,
            userText: String,
            assistantText: String,
        ): String {
            val user = LlmPromptDefaults.sanitizeParagraph(userText).take(120)
            val assistant = LlmPromptDefaults.sanitizeParagraph(assistantText).take(120)
            if (user.isEmpty() && assistant.isEmpty()) {
                return LlmPromptDefaults.clampShortTermParagraph(current)
            }
            val bit = buildString {
                if (user.isNotEmpty()) append("They said: ").append(user).append(". ")
                if (assistant.isNotEmpty()) append("You replied: ").append(assistant).append('.')
            }.trim()
            return listOf(
                LlmPromptDefaults.sanitizeParagraph(current),
                bit,
            ).filter { it.isNotBlank() }.joinToString(" ")
        }
    }
}
