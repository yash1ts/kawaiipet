package com.kawaiipet.app.llm

import android.util.Log
import com.kawaiipet.app.memory.MemoryPipeline
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Legacy entry point — session turns are recorded on [MemoryPipeline]; flush happens on Home.
 */
@Singleton
class FactExtractor @Inject constructor(
    private val memoryPipeline: MemoryPipeline,
) {
    fun extractAndStoreAsync(userText: String, assistantText: String) {
        Log.d(TAG, "Recording turn for later session flush")
        memoryPipeline.recordTurn(userText, assistantText)
    }

    companion object {
        private const val TAG = "FactExtractor"
    }
}
