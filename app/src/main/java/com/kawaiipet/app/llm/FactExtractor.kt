package com.kawaiipet.app.llm

import android.util.Log
import com.kawaiipet.app.memory.MemoryPipeline
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Legacy entry point — forwards to [MemoryPipeline] (paragraph memory + utility LLM).
 * Prefer injecting [MemoryPipeline] directly in new code.
 */
@Singleton
class FactExtractor @Inject constructor(
    private val memoryPipeline: MemoryPipeline,
) {
    fun extractAndStoreAsync(userText: String, assistantText: String) {
        Log.d(TAG, "Forwarding turn to MemoryPipeline")
        memoryPipeline.scheduleConsolidate(userText, assistantText)
    }

    companion object {
        private const val TAG = "FactExtractor"
    }
}
