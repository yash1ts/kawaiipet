package com.kawaiipet.app.pet

/**
 * Single explicit turn state for the pet brain.
 * [PetViewModel] maps this to [com.kawaiipet.app.overlay.OverlayState] for UI.
 */
sealed class PetTurnState {
    data object Idle : PetTurnState()
    data object Preparing : PetTurnState()
    data object Listening : PetTurnState()
    data object Transcribing : PetTurnState()
    data class Thinking(val userText: String) : PetTurnState()
    data class Speaking(val text: String) : PetTurnState()
    /** Keep [text] so the bubble does not flash empty after TTS. */
    data class Settling(val expression: PetExpression, val text: String) : PetTurnState()
}
