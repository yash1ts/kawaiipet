package com.kawaiipet.app.llm

import android.util.Log
import com.kawaiipet.app.memory.MemoryPipeline
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Legacy entry point — memorable turns are indexed into RAG on a background IO thread.
 */
@Singleton
class FactExtractor @Inject constructor(
    private val memoryPipeline: MemoryPipeline,
) {
    fun extractAndStoreAsync(userText: String, assistantText: String) {
        Log.d(TAG, "Scheduling background RAG index")
        memoryPipeline.scheduleIndexTurn(userText, assistantText)
    }

    companion object {
        private const val TAG = "FactExtractor"
    }
}
