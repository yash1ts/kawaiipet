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
    data class Settling(val expression: PetExpression) : PetTurnState()
}
