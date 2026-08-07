package com.kawaiipet.app.pet

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kawaiipet.app.audio.AudioPipeline
import com.kawaiipet.app.llm.ConversationManager
import com.kawaiipet.app.llm.LlmPromptDefaults
import com.kawaiipet.app.audio.ModelManager
import com.kawaiipet.app.overlay.OverlayState
import com.kawaiipet.app.util.PermissionHelper
import com.kawaiipet.app.util.PreferenceManager
import com.kawaiipet.app.util.Analytics
import com.kawaiipet.app.util.UiFeedback
import kotlinx.coroutines.CancellationException
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

class PetViewModel(
    private val appContext: Context,
    private val conversationManager: ConversationManager,
    private val audioPipeline: AudioPipeline,
    private val preferenceManager: PreferenceManager,
    private val modelManager: ModelManager,
    private val animationController: PetAnimationController,
    private val uiFeedback: UiFeedback
) : ViewModel() {

    private val _overlayState = MutableStateFlow<OverlayState>(OverlayState.Idle)
    val overlayState: StateFlow<OverlayState> = _overlayState.asStateFlow()

    private val _currentResponse = MutableStateFlow("")
    val currentResponse: StateFlow<String> = _currentResponse.asStateFlow()

    private val _listeningSubtitle = MutableStateFlow("")
    val listeningSubtitle: StateFlow<String> = _listeningSubtitle.asStateFlow()

    private var currentJob: Job? = null
    private var mouthAnimJob: Job? = null
    private var warmUpJob: Job? = null
    private val emotionDurationMs = 2200L

    fun onPetTapped() {
        if (_overlayState.value is OverlayState.Processing) {
            uiFeedback.click()
            currentJob?.cancel()
            returnToIdle()
            return
        }
        if (_overlayState.value !is OverlayState.Idle) return

        uiFeedback.click()
        warmUpLlmInBackground()
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            try {
                if (!PermissionHelper.hasMicrophonePermission(appContext)) {
                    _currentResponse.value =
                        "Microphone is off for this app. Open KawaiiPet, tap Grant Mic on the home screen, or enable Microphone in Android app settings."
                    animationController.setExpression(PetExpression.SAD)
                    uiFeedback.softNegative()
                    _overlayState.value = OverlayState.Speaking(_currentResponse.value)
                    delay(4500L)
                    returnToIdle()
                    return@launch
                }

                val sttModelId = preferenceManager.getSttModelId()
                val useLocalSherpa =
                    sttModelId.isNotBlank() && modelManager.isModelDownloaded(sttModelId)
                if (useLocalSherpa && !audioPipeline.isSttReady) {
                    _overlayState.value = OverlayState.PreparingVoice
                    _listeningSubtitle.value = "Loading voice model…"
                    animationController.setExpression(PetExpression.THINKING)
                    uiFeedback.petPreparing()
                    Log.d(TAG, "State → PREPARING_VOICE (waiting for Sherpa)")
                    audioPipeline.awaitPetVoiceEnginesReady(timeoutMs = 90_000L)
                    val ready = audioPipeline.isSttReady
                    if (!ready) {
                        _listeningSubtitle.value = ""
                        _currentResponse.value =
                            "Voice model is still loading. Wait a few seconds and tap again."
                        animationController.setExpression(PetExpression.SAD)
                        uiFeedback.softNegative()
                        _overlayState.value = OverlayState.Speaking(_currentResponse.value)
                        delay(3500L)
                        returnToIdle()
                        return@launch
                    }
                }

                Analytics.capture(event = "voice conversation initiated")
                _overlayState.value = OverlayState.Listening
                _listeningSubtitle.value = ""
                animationController.setExpression(PetExpression.LISTENING)
                uiFeedback.petListening()
                Log.d(TAG, "State → LISTENING")

                val userText = withContext(Dispatchers.Default) {
                    audioPipeline.listenAndTranscribe()
                }

                _listeningSubtitle.value = ""
                Log.d(TAG, "STT result: '$userText'")

                when {
                    userText.isBlank() -> {
                        animationController.setExpression(PetExpression.SAD)
                        uiFeedback.softNegative()
                        _currentResponse.value = LlmPromptDefaults.DIDNT_CATCH_REPLY
                        _overlayState.value = OverlayState.Speaking(LlmPromptDefaults.DIDNT_CATCH_REPLY)
                        delay(2000L)
                        returnToIdle()
                    }
                    userText == "[voice]" -> {
                        animationController.setExpression(PetExpression.THINKING)
                        uiFeedback.softNegative()
                        _currentResponse.value = LlmPromptDefaults.DIDNT_CATCH_REPLY
                        _overlayState.value = OverlayState.Speaking(LlmPromptDefaults.DIDNT_CATCH_REPLY)
                        delay(emotionDurationMs)
                        returnToIdle()
                    }
                    else -> processText(userText)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "onPetTapped failed", e)
                _currentResponse.value = "Couldn't hear you — try again?"
                animationController.setExpression(PetExpression.SAD)
                uiFeedback.softNegative()
                _overlayState.value = OverlayState.Speaking(_currentResponse.value)
                delay(emotionDurationMs)
                returnToIdle()
            }
        }
    }

    /**
     * Loads SmolLM into memory while the user is still speaking, so inference
     * can start the moment transcription finishes. Best-effort: failures are
     * ignored and chat-time ensureReady() remains the authority.
     */
    private fun warmUpLlmInBackground() {
        if (warmUpJob?.isActive == true) return
        warmUpJob = viewModelScope.launch {
            try {
                conversationManager.warmUpLlm()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "LLM warmup failed (will retry at chat time)", e)
            }
        }
    }

    fun onTextSubmitted(text: String) {
        if (text.isBlank()) {
            _currentResponse.value = ""
            _overlayState.value = OverlayState.Idle
            return
        }
        currentJob?.cancel()
        currentJob = viewModelScope.launch { processText(text) }
    }

    fun dismissTextInput() {
        _currentResponse.value = ""
        _overlayState.value = OverlayState.Idle
    }

    private suspend fun processText(userText: String) {
        _currentResponse.value = ""
        _overlayState.value = OverlayState.Processing(userText)
        animationController.setExpression(PetExpression.THINKING)
        uiFeedback.petThinking()
        Log.d(TAG, "State → PROCESSING/THINKING")

        try {
            // Don't stream raw tokens into the bubble — SmolLM often dumps lists/meta
            // that get filtered before TTS, which made shown text ≠ spoken text.
            val llmResponse = withTimeoutOrNull(LLM_TIMEOUT_MS) {
                conversationManager.processUserInput(userText)
            }

            if (llmResponse == null) {
                Log.w(TAG, "processUserInput timed out after ${LLM_TIMEOUT_MS}ms")
                _currentResponse.value =
                    "That took too long. Check your connection and tap again."
                animationController.setExpression(PetExpression.SAD)
                uiFeedback.softNegative()
                _overlayState.value = OverlayState.Speaking(_currentResponse.value)
                delay(emotionDurationMs)
                returnToIdle()
                return
            }

            val speakText = llmResponse.text.trim()
                .ifBlank { LlmPromptDefaults.DIDNT_CATCH_REPLY }
            _currentResponse.value = speakText
            _overlayState.value = OverlayState.Speaking(speakText)
            animationController.setExpression(PetExpression.TALKING)
            Log.d(TAG, "State → SPEAKING/TALKING")
            uiFeedback.petSpeakingStart()
            startMouthAnimation()
            audioPipeline.speak(speakText)
            stopMouthAnimation()

            Analytics.capture(
                event = "ai response received",
                properties = mapOf(
                    "expression" to llmResponse.expression.name,
                    "response_length" to llmResponse.text.length,
                ),
            )

            animationController.setExpression(llmResponse.expression)
            if (llmResponse.expression == PetExpression.HAPPY) {
                uiFeedback.petEmotionPositive()
            }
            Log.d(TAG, "State → EMOTION(${llmResponse.expression})")
            delay(emotionDurationMs)
        } catch (e: CancellationException) {
            returnToIdle()
            throw e
        } catch (e: FileNotFoundException) {
            Log.e(TAG, "processText failed (missing file)", e)
            stopMouthAnimation()
            _currentResponse.value =
                "A file is missing. Check Logcat tag PetViewModel. [sad]"
            animationController.setExpression(PetExpression.SAD)
            uiFeedback.softNegative()
            _overlayState.value = OverlayState.Speaking(_currentResponse.value)
            delay(emotionDurationMs)
        } catch (e: Exception) {
            Log.e(TAG, "processText failed", e)
            stopMouthAnimation()
            val tail = e.message?.trim()?.replace('\n', ' ')?.take(90)
            _currentResponse.value = if (tail.isNullOrBlank()) {
                "Something went wrong… See Logcat PetViewModel for the error. [sad]"
            } else {
                "Something went wrong: $tail [sad]"
            }
            animationController.setExpression(PetExpression.SAD)
            uiFeedback.softNegative()
            _overlayState.value = OverlayState.Speaking(_currentResponse.value)
            delay(emotionDurationMs)
        }

        returnToIdle()
    }

    private fun startMouthAnimation() {
        mouthAnimJob = viewModelScope.launch {
            while (true) {
                animationController.setMouthOpen(true)
                delay(75)
                animationController.setMouthOpen(false)
                delay(65)
            }
        }
    }

    private fun stopMouthAnimation() {
        mouthAnimJob?.cancel()
        mouthAnimJob = null
        animationController.setMouthOpen(false)
    }

    private fun returnToIdle() {
        Log.d(TAG, "State → IDLE")
        stopMouthAnimation()
        _currentResponse.value = ""
        _listeningSubtitle.value = ""
        _overlayState.value = OverlayState.Idle
        animationController.setExpression(PetExpression.SLEEPING)
    }

    fun cleanup() {
        currentJob?.cancel()
        mouthAnimJob?.cancel()
        warmUpJob?.cancel()
        returnToIdle()
        audioPipeline.release()
    }

    override fun onCleared() {
        super.onCleared()
        cleanup()
    }

    companion object {
        private const val TAG = "PetViewModel"
        /** Cap wait so a stuck network call does not leave the UI in Processing forever. */
        private const val LLM_TIMEOUT_MS = 180_000L
    }
}
