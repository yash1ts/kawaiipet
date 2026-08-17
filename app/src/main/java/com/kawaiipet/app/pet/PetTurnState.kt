package com.kawaiipet.app.pet

/**
 * Conversation-session FSM owned by [PetBrain].
 *
 * Idle --tap--> Preparing (STT not ready) or Listening.
 * Preparing --ready--> Listening; --tap/fail--> Idle.
 * Listening --speech--> Transcribing; --tap or 30s silence--> Idle.
 * Transcribing --text--> Thinking; --blank--> Idle; --tap--> Listening.
 * Thinking --reply--> Speaking; --tap--> Listening.
 * Speaking --TTS done--> Settling; --tap--> Listening.
 * Settling --brief hold--> Listening; --tap--> Listening.
 *
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
