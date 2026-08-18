package com.kawaiipet.app.pet

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.kawaiipet.app.audio.AudioPipeline
import com.kawaiipet.app.audio.ModelManager
import com.kawaiipet.app.llm.ConversationManager
import com.kawaiipet.app.llm.LlmEngineWarmup
import com.kawaiipet.app.llm.LlmPromptDefaults
import com.kawaiipet.app.tools.AppLauncher
import com.kawaiipet.app.util.DebugSessionLog
import com.kawaiipet.app.util.PermissionHelper
import com.kawaiipet.app.util.UiFeedback
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns one pet conversation session (listen → think → speak loop until idle).
 * Sticky KV stays the hot path; short-term memory is the source of truth for rebuilds.
 */
@Singleton
class PetBrain @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val conversationManager: ConversationManager,
    private val llmEngineWarmup: LlmEngineWarmup,
    private val audioPipeline: AudioPipeline,
    private val sessionConfigStore: SessionConfigStore,
    private val modelManager: ModelManager,
    private val animationController: PetAnimationController,
    private val uiFeedback: UiFeedback,
    private val appLauncher: AppLauncher,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<PetTurnState>(PetTurnState.Idle)
    val state: StateFlow<PetTurnState> = _state.asStateFlow()

    private val _currentResponse = MutableStateFlow("")
    val currentResponse: StateFlow<String> = _currentResponse.asStateFlow()

    private val _listeningSubtitle = MutableStateFlow("")
    val listeningSubtitle: StateFlow<String> = _listeningSubtitle.asStateFlow()

    private var sessionJob: Job? = null
    private var turnJob: Job? = null
    private var mouthAnimJob: Job? = null
    private var warmUpJob: Job? = null

    fun onTrigger() {
        uiFeedback.click()
        when (_state.value) {
            PetTurnState.Idle -> startSession()
            PetTurnState.Listening,
            PetTurnState.Preparing,
            -> endSession()
            is PetTurnState.Transcribing,
            is PetTurnState.Thinking,
            is PetTurnState.Speaking,
            is PetTurnState.Settling,
            -> interruptToListen()
        }
    }

    fun cancelTurn() = endSession()

    private fun startSession() {
        if (sessionJob?.isActive == true) return
        warmUpLlmInBackground()
        sessionJob = scope.launch {
            try {
                while (isActive) {
                    var endSession = false
                    val turn = launch {
                        val trace = TurnTrace()
                        try {
                            when (runOneTurn(trace = trace)) {
                                TurnOutcome.EndSession -> endSession = true
                                TurnOutcome.Continue -> Unit
                            }
                        } catch (e: CancellationException) {
                            audioPipeline.stopListening()
                            audioPipeline.stopSpeaking()
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "turn failed", e)
                            recoverWithFriendlyFallback()
                        } finally {
                            trace.log()
                        }
                    }
                    turnJob = turn
                    turn.join()
                    turnJob = null
                    if (endSession || !isActive) break
                }
            } finally {
                audioPipeline.stopListening()
                audioPipeline.stopSpeaking()
                if (sessionJob === coroutineContext.job) {
                    sessionJob = null
                    returnToIdle()
                }
            }
        }
    }

    private fun interruptToListen() {
        audioPipeline.stopListening()
        audioPipeline.stopSpeaking()
        stopMouthAnimation()
        val sessionRunning = sessionJob?.isActive == true
        turnJob?.cancel()
        turnJob = null
        if (!sessionRunning) {
            startSession()
        }
    }

    private fun endSession() {
        sessionJob?.cancel()
        turnJob?.cancel()
        sessionJob = null
        turnJob = null
        mouthAnimJob?.cancel()
        mouthAnimJob = null
        audioPipeline.stopListening()
        audioPipeline.stopSpeaking()
        returnToIdle()
    }

    fun onTextSubmitted(text: String) {
        if (text.isBlank()) {
            _currentResponse.value = ""
            _state.value = PetTurnState.Idle
            return
        }
        endSession()
        turnJob = scope.launch {
            val trace = TurnTrace()
            try {
                processText(text, trace)
                returnToIdle()
            } catch (e: CancellationException) {
                audioPipeline.stopSpeaking()
                returnToIdle()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "text turn failed", e)
                recoverWithFriendlyFallback()
                returnToIdle()
            } finally {
                trace.log()
            }
        }
    }

    /**
     * Soft proactive line (e.g. usage-time nudge) — no mic, no LLM.
     * Skipped if a turn is already in progress so we don't interrupt a chat.
     */
    fun speakProactive(message: String) {
        val line = message.trim()
        if (line.isEmpty()) return
        if (_state.value !is PetTurnState.Idle) {
            Log.d(TAG, "speakProactive skipped — pet busy (${_state.value})")
            return
        }
        turnJob?.cancel()
        turnJob = scope.launch {
            try {
                _listeningSubtitle.value = ""
                _currentResponse.value = line
                _state.value = PetTurnState.Speaking(line)
                animationController.setExpression(PetExpression.HAPPY)
                uiFeedback.petSpeakingStart()
                startMouthAnimation()
                if (!audioPipeline.isTtsReady) {
                    audioPipeline.awaitPetVoiceEnginesReady(timeoutMs = 20_000L)
                }
                val cfg = sessionConfigStore.snapshot()
                audioPipeline.speak(
                    line,
                    speakerId = cfg.ttsSpeakerId,
                    volume = cfg.ttsVolume,
                    speed = cfg.ttsSpeed,
                )
                stopMouthAnimation()
                _state.value = PetTurnState.Settling(PetExpression.HAPPY, line)
                delay(EMOTION_DURATION_MS)
            } catch (e: CancellationException) {
                audioPipeline.stopSpeaking()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "speakProactive failed", e)
            } finally {
                if (sessionJob?.isActive != true) {
                    returnToIdle()
                }
            }
        }
    }

    fun dismissTextInput() {
        _currentResponse.value = ""
        _state.value = PetTurnState.Idle
    }

    fun shutdown() {
        endSession()
        warmUpJob?.cancel()
        warmUpJob = null
        audioPipeline.release()
    }

    private suspend fun runOneTurn(trace: TurnTrace): TurnOutcome {
        if (!PermissionHelper.hasMicrophonePermission(appContext)) {
            speakError(
                "Microphone is off for this app. Open KawaiiPet, tap Grant Mic on the home screen, or enable Microphone in Android app settings.",
                holdMs = 4500L,
            )
            return TurnOutcome.EndSession
        }

        val cfg = sessionConfigStore.snapshot()
        val useLocalSherpa =
            cfg.sttModelId.isNotBlank() && modelManager.isModelDownloaded(cfg.sttModelId)
        if (useLocalSherpa && !audioPipeline.isSttReady) {
            _state.value = PetTurnState.Preparing
            _listeningSubtitle.value = "Loading voice model…"
            animationController.setExpression(PetExpression.THINKING)
            uiFeedback.petPreparing()
            val ready = withTimeoutOrNull(VOICE_PREPARE_TIMEOUT_MS) {
                audioPipeline.awaitPetVoiceEnginesReady(timeoutMs = VOICE_PREPARE_TIMEOUT_MS)
                audioPipeline.isSttReady
            } ?: false
            if (!ready) {
                speakError(
                    "Voice model is still loading. Wait a few seconds and tap again.",
                    holdMs = 3500L,
                )
                return TurnOutcome.EndSession
            }
        }

        _state.value = PetTurnState.Listening
        _listeningSubtitle.value = ""
        _currentResponse.value = ""
        animationController.setExpression(PetExpression.LISTENING)
        uiFeedback.petListening()
        // Do not warm the LLM during mic/STT — LiteRT engine init races Moonshine
        // on first tap and we saw empty transcripts while GPU load finished mid-listen.

        val listenStarted = SystemClock.elapsedRealtime()
        val userText = withContext(Dispatchers.Default) {
            audioPipeline.listenAndTranscribe(timeoutMs = LISTEN_TIMEOUT_MS)
        }
        trace.listenMs = SystemClock.elapsedRealtime() - listenStarted
        _listeningSubtitle.value = ""
        Log.d(TAG, "STT result: '$userText' (${trace.listenMs}ms)")

        if (userText.isBlank() || userText == "[voice]") {
            return TurnOutcome.EndSession
        }
        _state.value = PetTurnState.Transcribing
        processText(userText, trace)
        return TurnOutcome.Continue
    }

    private suspend fun processText(userText: String, trace: TurnTrace) {
        DebugSessionLog.log(
            hypothesisId = "C",
            location = "PetBrain.processText:entry",
            message = "processText started",
            data = mapOf("userText" to userText.take(120), "userLen" to userText.length),
        )
        _currentResponse.value = ""
        _state.value = PetTurnState.Thinking(userText)
        animationController.setExpression(PetExpression.THINKING)
        uiFeedback.petThinking()
        warmUpLlmInBackground()

        try {
            coroutineScope {
                val sentenceChannel = Channel<String>(capacity = 32)
                val startedSpeaking = AtomicBoolean(false)
                val queuedTts = AtomicBoolean(false)
                val speakJob = async(Dispatchers.IO) {
                    val cfg = sessionConfigStore.snapshot()
                    audioPipeline.speakSentences(
                        sentences = sentenceChannel,
                        speakerId = cfg.ttsSpeakerId,
                        volume = cfg.ttsVolume,
                        speed = cfg.ttsSpeed,
                    )
                }

                /** Publish bubble text on Main before any TTS work. Never show empty Speaking. */
                suspend fun publishReplyUi(raw: String, startTalking: Boolean) {
                    val display = raw.trim()
                    if (display.isEmpty()) return
                    withContext(Dispatchers.Main.immediate) {
                        _currentResponse.value = display
                        if (startTalking) {
                            if (startedSpeaking.compareAndSet(false, true)) {
                                _state.value = PetTurnState.Speaking(display)
                                animationController.setExpression(PetExpression.TALKING)
                                uiFeedback.petSpeakingStart()
                                startMouthAnimation()
                            } else {
                                _state.value = PetTurnState.Speaking(display)
                            }
                        }
                        // Even while Thinking: bubbleVisible uses responseText on Processing.
                    }
                }

                val genStarted = SystemClock.elapsedRealtime()
                var lastTtsPiece = ""
                val llmResponse = withTimeoutOrNull(LLM_TIMEOUT_MS) {
                    conversationManager.processUserInput(
                        text = userText,
                        onPartial = { display ->
                            val trimmed = display.trim()
                            if (trimmed.isNotEmpty()) {
                                // Stream into bubble during Thinking; flip to Talking once TTS starts.
                                publishReplyUi(trimmed, startTalking = false)
                            }
                        },
                        onSpeakableSentence = speak@{ sentence ->
                            val piece = sentence.trim()
                            if (piece.isEmpty()) return@speak
                            if (piece.equals(lastTtsPiece, ignoreCase = true)) {
                                return@speak
                            }
                            lastTtsPiece = piece
                            // Keep cumulative bubble from onPartial; only flip to Talking + queue TTS.
                            withContext(Dispatchers.Main.immediate) {
                                if (startedSpeaking.compareAndSet(false, true)) {
                                    val shown = _currentResponse.value.ifBlank { piece }
                                    _state.value = PetTurnState.Speaking(shown)
                                    animationController.setExpression(PetExpression.TALKING)
                                    uiFeedback.petSpeakingStart()
                                    startMouthAnimation()
                                }
                            }
                            queuedTts.set(true)
                            if (sentenceChannel.trySend(piece).isFailure) {
                                sentenceChannel.send(piece)
                            }
                        },
                    )
                }
                trace.llmMs = SystemClock.elapsedRealtime() - genStarted
                sentenceChannel.close()

                if (llmResponse == null) {
                    Log.w(TAG, "processUserInput timed out after ${LLM_TIMEOUT_MS}ms")
                    speakJob.cancel()
                    audioPipeline.stopSpeaking()
                    stopMouthAnimation()
                    val fallback = LlmPromptDefaults.DIDNT_CATCH_REPLY
                    publishReplyUi(fallback, startTalking = true)
                    val cfg = sessionConfigStore.snapshot()
                    audioPipeline.speak(
                        fallback,
                        speakerId = cfg.ttsSpeakerId,
                        volume = cfg.ttsVolume,
                        speed = cfg.ttsSpeed,
                    )
                    stopMouthAnimation()
                    animationController.setExpression(PetExpression.THINKING)
                    uiFeedback.softNegative()
                    _state.value = PetTurnState.Settling(PetExpression.THINKING, fallback)
                    delay(EMOTION_DURATION_MS)
                    return@coroutineScope
                }

                val speakText = llmResponse.text.trim()
                    .ifBlank { LlmPromptDefaults.DIDNT_CATCH_REPLY }
                // Always paint final text before waiting on / starting audio.
                publishReplyUi(speakText, startTalking = true)

                val ttsStarted = SystemClock.elapsedRealtime()
                withTimeoutOrNull(TTS_DRAIN_TIMEOUT_MS) { speakJob.await() }
                    ?: run {
                        Log.w(TAG, "TTS drain timed out")
                        audioPipeline.stopSpeaking()
                    }
                trace.ttsMs = SystemClock.elapsedRealtime() - ttsStarted
                stopMouthAnimation()

                // No streamed TTS pieces — speak the full reply now (text already on screen).
                if (!queuedTts.get() && speakText.isNotBlank()) {
                    val cfg = sessionConfigStore.snapshot()
                    audioPipeline.speak(
                        speakText,
                        speakerId = cfg.ttsSpeakerId,
                        volume = cfg.ttsVolume,
                        speed = cfg.ttsSpeed,
                    )
                    stopMouthAnimation()
                }

                // Open/play after speech so the confirmation is heard first.
                llmResponse.toolCall?.let { call ->
                    val ok = withContext(Dispatchers.Main) { appLauncher.launch(call) }
                    if (!ok) {
                        Log.w(
                            TAG,
                            "tool launch failed: ${call.intent.id} query=${call.query?.take(40)}",
                        )
                    }
                }

                animationController.setExpression(llmResponse.expression)
                if (llmResponse.expression == PetExpression.HAPPY) {
                    uiFeedback.petEmotionPositive()
                }
                _state.value = PetTurnState.Settling(llmResponse.expression, speakText)
                delay(EMOTION_DURATION_MS)
            }
        } catch (e: CancellationException) {
            audioPipeline.stopSpeaking()
            throw e
        } catch (e: FileNotFoundException) {
            Log.e(TAG, "processText failed (missing file)", e)
            audioPipeline.stopSpeaking()
            stopMouthAnimation()
            recoverWithFriendlyFallback()
        } catch (e: Exception) {
            Log.e(TAG, "processText failed", e)
            audioPipeline.stopSpeaking()
            stopMouthAnimation()
            recoverWithFriendlyFallback()
        }
    }

    private fun warmUpLlmInBackground() {
        // Deduped singleton warmup (app start / overlay / assets may already be hot).
        llmEngineWarmup.startWarmup("pet_tap")
        if (warmUpJob?.isActive == true) return
        warmUpJob = scope.launch(Dispatchers.Default) {
            try {
                llmEngineWarmup.awaitReady(timeoutMs = 8_000L)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "LLM warmup await failed (will retry at chat time)", e)
            }
        }
    }

    private suspend fun speakError(message: String, holdMs: Long) {
        _listeningSubtitle.value = ""
        _currentResponse.value = message
        animationController.setExpression(PetExpression.SAD)
        uiFeedback.softNegative()
        _state.value = PetTurnState.Speaking(message)
        startMouthAnimation()
        runCatching {
            val cfg = sessionConfigStore.snapshot()
            audioPipeline.speak(
                text = message,
                speakerId = cfg.ttsSpeakerId,
                volume = cfg.ttsVolume,
                speed = cfg.ttsSpeed,
            )
        }
        delay(holdMs.coerceAtLeast(400L))
        stopMouthAnimation()
    }

    private suspend fun recoverWithFriendlyFallback() {
        val fallback = LlmPromptDefaults.DIDNT_CATCH_REPLY
        _currentResponse.value = fallback
        _state.value = PetTurnState.Speaking(fallback)
        animationController.setExpression(PetExpression.THINKING)
        uiFeedback.softNegative()
        startMouthAnimation()
        runCatching {
            val cfg = sessionConfigStore.snapshot()
            audioPipeline.speak(
                fallback,
                speakerId = cfg.ttsSpeakerId,
                volume = cfg.ttsVolume,
                speed = cfg.ttsSpeed,
            )
        }
        stopMouthAnimation()
        _state.value = PetTurnState.Settling(PetExpression.THINKING, fallback)
        delay(EMOTION_DURATION_MS)
    }

    private fun startMouthAnimation() {
        mouthAnimJob?.cancel()
        mouthAnimJob = scope.launch {
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
        stopMouthAnimation()
        _currentResponse.value = ""
        _listeningSubtitle.value = ""
        _state.value = PetTurnState.Idle
        animationController.setExpression(PetExpression.SLEEPING)
    }

    private class TurnTrace {
        var listenMs: Long = -1
        var llmMs: Long = -1
        var ttsMs: Long = -1

        fun log() {
            Log.i(
                TAG,
                "turn_trace listen=${listenMs}ms llm=${llmMs}ms tts=${ttsMs}ms",
            )
            DebugSessionLog.flushTurn(
                mapOf(
                    "listenMs" to listenMs,
                    "llmMs" to llmMs,
                    "ttsMs" to ttsMs,
                ),
            )
        }
    }

    companion object {
        private const val TAG = "PetBrain"
        private const val EMOTION_DURATION_MS = 2200L
        private const val VOICE_PREPARE_TIMEOUT_MS = 90_000L
        private const val LISTEN_TIMEOUT_MS = 35_000L
        /** Cap wait so a stuck generation does not leave the UI in Thinking forever. */
        private const val LLM_TIMEOUT_MS = 25_000L
        private const val TTS_DRAIN_TIMEOUT_MS = 20_000L
    }

    private enum class TurnOutcome {
        Continue,
        EndSession,
    }
}
