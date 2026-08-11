package com.kawaiipet.app.ui

import androidx.lifecycle.ViewModel
import com.kawaiipet.app.memory.MemoryPipeline
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val memoryPipeline: MemoryPipeline,
) : ViewModel() {
    fun canStartPet(): Boolean = true

    /** Warm RAG store / migrate legacy paragraph on Home (background IO). */
    fun flushSessionMemory() {
        memoryPipeline.scheduleFlushSession()
    }
}
