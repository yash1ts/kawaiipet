package com.kawaiipet.app.memory

import android.util.Log
import com.kawaiipet.app.llm.LlmPromptDefaults
import com.kawaiipet.app.llm.LlmService
import com.kawaiipet.app.util.PreferenceManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch

/**
 * Separate on-device LLM pipeline for long-term pet memory.
 *
 * Chat uses a short paragraph from prefs; this pipeline consolidates / sanitizes
 * that paragraph after successful turns without blocking the reply path.
 */
@Singleton
class MemoryPipeline @Inject constructor(
    private val llmService: LlmService,
    private val prefs: PreferenceManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    val memoryParagraph: Flow<String> = prefs.memoryParagraph

    suspend fun getMemoryParagraph(): String =
        LlmPromptDefaults.clampMemoryParagraph(prefs.getMemoryParagraph())

    suspend fun clearMemory() {
        prefs.setMemoryParagraph("")
    }

    /** Fire-and-forget consolidate after a good chat turn. */
    fun scheduleConsolidate(userText: String, assistantText: String) {
        val user = LlmPromptDefaults.sanitizeParagraph(userText)
        val assistant = LlmPromptDefaults.sanitizeParagraph(assistantText)
        if (user.isEmpty() || assistant.isEmpty()) return
        if (LlmPromptDefaults.isCannedFallback(assistantText)) return
        scope.launch {
            runCatching { consolidateNow(user, assistant) }
                .onFailure { Log.w(TAG, "Memory consolidate failed", it) }
        }
    }

    suspend fun consolidateNow(userText: String, assistantText: String) {
        mutex.withLock {
            val current = getMemoryParagraph()
            val updated = llmService.consolidateMemory(current, userText, assistantText)
            if (updated.isBlank()) {
                Log.d(TAG, "Memory unchanged (NONE / rejected)")
                return
            }
            val clamped = LlmPromptDefaults.clampMemoryParagraph(updated)
            if (clamped == current) return
            // Empty after sanitize clears prefs so junk does not linger.
            prefs.setMemoryParagraph(clamped)
            Log.d(
                TAG,
                if (clamped.isEmpty()) "Memory cleared (empty after sanitize)"
                else "Memory updated (${clamped.length} chars): ${clamped.take(120)}",
            )
        }
    }

    companion object {
        private const val TAG = "MemoryPipeline"
    }
}
