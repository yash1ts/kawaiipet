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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Long-term pet memory. Chat turns are recorded into [SessionTranscript] during a session;
 * they are summarized into prefs once (e.g. when Home opens) — not after every reply.
 */
@Singleton
class MemoryPipeline @Inject constructor(
    private val llmService: LlmService,
    private val prefs: PreferenceManager,
    private val sessionTranscript: SessionTranscript,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    val memoryParagraph: Flow<String> = prefs.memoryParagraph

    suspend fun getMemoryParagraph(): String =
        LlmPromptDefaults.clampMemoryParagraph(prefs.getMemoryParagraph())

    suspend fun clearMemory() {
        prefs.setMemoryParagraph("")
    }

    /** Record a successful turn for later session flush (no LLM call). */
    fun recordTurn(userText: String, assistantText: String) {
        sessionTranscript.record(userText, assistantText)
    }

    /**
     * Fire-and-forget: summarize the pending session into long-term memory.
     * Intended for Home screen (and similar app foreground entry).
     */
    fun scheduleFlushSession() {
        if (sessionTranscript.isEmpty()) return
        scope.launch {
            runCatching { flushSessionNow() }
                .onFailure { Log.w(TAG, "Session memory flush failed", it) }
        }
    }

    suspend fun flushSessionNow() {
        mutex.withLock {
            val turns = sessionTranscript.snapshotAndClear()
            if (turns.isEmpty()) return

            val friendLines = turns.map { it.userText }
                .map { LlmPromptDefaults.sanitizeParagraph(it) }
                .filter { it.isNotEmpty() && LlmPromptDefaults.looksLikeMemorableUserTurn(it) }
                .distinct()

            if (friendLines.isEmpty()) {
                Log.d(TAG, "Session flush skipped (${turns.size} turns, none memorable)")
                return
            }

            val current = getMemoryParagraph()
            Log.d(
                TAG,
                "Flushing session → memory (${friendLines.size} fact lines from ${turns.size} turns)",
            )
            val updated = llmService.consolidateSession(current, friendLines)
            if (updated.isBlank()) {
                Log.d(TAG, "Memory unchanged after session flush (NONE / rejected)")
                return
            }
            val clamped = LlmPromptDefaults.clampMemoryParagraph(updated)
            if (clamped.isEmpty()) {
                prefs.setMemoryParagraph("")
                return
            }
            if (LlmPromptDefaults.isSameMemory(clamped, current)) {
                Log.d(TAG, "Memory unchanged (duplicate of current)")
                return
            }
            prefs.setMemoryParagraph(clamped)
            Log.d(TAG, "Memory updated from session (${clamped.length} chars): ${clamped.take(160)}")
        }
    }

    companion object {
        private const val TAG = "MemoryPipeline"
    }
}
