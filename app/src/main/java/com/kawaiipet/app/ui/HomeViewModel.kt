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

    /** Summarize the last pet session into long-term memory (once per pending transcript). */
    fun flushSessionMemory() {
        memoryPipeline.scheduleFlushSession()
    }
}
