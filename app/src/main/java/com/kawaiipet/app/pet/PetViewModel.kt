package com.kawaiipet.app.pet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kawaiipet.app.overlay.OverlayState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Thin UI adapter over [PetBrain]. Owns Compose-facing [OverlayState] mapping only.
 */
class PetViewModel(
    private val petBrain: PetBrain,
) : ViewModel() {

    val overlayState: StateFlow<OverlayState> = petBrain.state
        .map { it.toOverlayState() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, OverlayState.Idle)

    val currentResponse: StateFlow<String> = petBrain.currentResponse
    val listeningSubtitle: StateFlow<String> = petBrain.listeningSubtitle

    init {
        // Keep a collector alive so stateIn stays active while the overlay is up.
        viewModelScope.launch {
            petBrain.state.collect { }
        }
    }

    fun onPetTapped() = petBrain.onTrigger()

    fun onTextSubmitted(text: String) = petBrain.onTextSubmitted(text)

    fun speakProactive(message: String) = petBrain.speakProactive(message)

    fun dismissTextInput() = petBrain.dismissTextInput()

    fun cleanup() {
        petBrain.shutdown()
    }

    override fun onCleared() {
        super.onCleared()
        cleanup()
    }

    companion object {
        private fun PetTurnState.toOverlayState(): OverlayState = when (this) {
            PetTurnState.Idle -> OverlayState.Idle
            PetTurnState.Preparing -> OverlayState.PreparingVoice
            PetTurnState.Listening -> OverlayState.Listening
            PetTurnState.Transcribing -> OverlayState.Processing("")
            is PetTurnState.Thinking -> OverlayState.Processing(userText)
            is PetTurnState.Speaking -> OverlayState.Speaking(text)
            is PetTurnState.Settling -> OverlayState.Speaking(text)
        }
    }
}
