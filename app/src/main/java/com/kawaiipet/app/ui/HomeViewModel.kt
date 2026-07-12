package com.kawaiipet.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kawaiipet.app.llm.GeminiNanoAvailability
import com.kawaiipet.app.llm.NanoAiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val nanoAvailability: GeminiNanoAvailability,
) : ViewModel() {

    val nanoState: StateFlow<NanoAiState> = nanoAvailability.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NanoAiState.Checking)

    init {
        refreshNanoStatus()
    }

    fun refreshNanoStatus() {
        viewModelScope.launch {
            nanoAvailability.refreshStatus()
        }
    }

    fun downloadNanoModel() {
        viewModelScope.launch {
            runCatching { nanoAvailability.download() }
                .onFailure { nanoAvailability.refreshStatus() }
        }
    }

    fun canStartPetWithNano(): Boolean = nanoAvailability.isReady()
}
