package com.kawaiipet.app.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume
import kotlin.math.sqrt
import com.kawaiipet.app.util.PreferenceManager

enum class PipelineState { IDLE, LISTENING, PROCESSING, SPEAKING }

class AudioPipeline(
    private val appContext: Context,
    private val stt: SherpaSTT,
    private val tts: SherpaTTS,
    private val recorder: AudioRecordManager,
    private val player: AudioTrackManager,
    private val preferenceManager: PreferenceManager,
) {
    private val _state = MutableStateFlow(PipelineState.IDLE)
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    private var recordJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val sttInputCleaner = SttInputCleaner()
    private val platformTts = PlatformTts(appContext)

    /** One-shot load of STT+TTS for the current pet session; see [schedulePetVoiceModelPrepare]. */
    private var petVoicePrepareJob: Job? = null

    var onAmplitude: ((Float) -> Unit)?
        get() = player.onAmplitude
        set(value) { player.onAmplitude = value }

    val isSttReady: Boolean get() = stt.isInitialized
    val isTtsReady: Boolean get() = tts.isInitialized

    fun initializeSTT(modelId: String): Boolean = stt.initialize(modelId)
    fun initializeTTS(modelId: String): Boolean = tts.initialize(modelId)

    /**
     * Starts loading the selected STT and TTS models once per overlay session.
     * Safe to call multiple times: in-flight work is not duplicated.
     */
    fun schedulePetVoiceModelPrepare(
        scope: CoroutineScope,
        sttId: String,
        ttsId: String,
        loadStt: Boolean,
        loadTts: Boolean
    ) {
        if (petVoicePrepareJob?.isActive == true) return
        petVoicePrepareJob = scope.launch(Dispatchers.Default) {
            if (loadStt && sttId.isNotBlank()) {
                val ok = initializeSTT(sttId)
                Log.d(TAG, "Pet voice prepare: STT id=$sttId success=$ok")
            }
            if (loadTts && ttsId.isNotBlank()) {
                val ok = initializeTTS(ttsId)
                Log.d(TAG, "Pet voice prepare: TTS id=$ttsId success=$ok")
            }
        }
    }

    /**
     * Waits for [schedulePetVoiceModelPrepare] to finish (up to [timeoutMs]), so engines are ready to reuse.
     */
    suspend fun awaitPetVoiceEnginesReady(timeoutMs: Long = 90_000L) {
        withTimeoutOrNull(timeoutMs) {
            while (petVoicePrepareJob == null) {
                yield()
            }
            petVoicePrepareJob!!.join()
        }
    }

    /**
     * Listens for speech, transcribes it, and returns the text.
     * Uses VAD (voice activity detection) to auto-detect when the user stops speaking.
     *
     * Priority: Sherpa STT → Platform SpeechRecognizer → Raw audio VAD (no transcription).
     */
    suspend fun listenAndTranscribe(
        timeoutMs: Long = DEFAULT_LISTEN_TIMEOUT_MS,
        onPartialText: (String) -> Unit = {}
    ): String {
        _state.value = PipelineState.LISTENING
        Log.d(TAG, "listenAndTranscribe: starting, sttReady=${stt.isInitialized}")

        val awaitTimeoutMs = maxOf(timeoutMs, MAX_RECORDING_DURATION_MS + 3_000L)

        return try {
            when {
                stt.isInitialized -> listenWithSherpa(awaitTimeoutMs)
                isPlatformSttAvailable() -> listenWithPlatformSpeechRecognizer(onPartialText, awaitTimeoutMs)
                else -> {
                    Log.d(TAG, "No STT available, using raw VAD")
                    listenWithVadOnly(awaitTimeoutMs, onPartialText)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "listenAndTranscribe failed", e)
            ""
        } finally {
            _state.value = PipelineState.IDLE
        }
    }

    private fun isPlatformSttAvailable(): Boolean = try {
        SpeechRecognizer.isRecognitionAvailable(appContext)
    } catch (_: Exception) {
        false
    }

    private suspend fun listenWithSherpa(timeoutMs: Long): String {
        stt.startStream()
        sttInputCleaner.reset()
        if (!recorder.start()) return ""

        val result = CompletableDeferred<String>()
        var silenceCount = 0
        var leadingSilenceChunks = 0
        var hasSpeechHint = false
        var speechHintStartedAt = 0L
        val recordingStartedAt = SystemClock.elapsedRealtime()

        recordJob = scope.launch {
            recorder.readLoop { samples ->
                val now = SystemClock.elapsedRealtime()
                if (now - recordingStartedAt >= MAX_RECORDING_DURATION_MS) {
                    Log.d(TAG, "Sherpa: max recording duration reached")
                    recorder.stop()
                    result.complete(stt.getFinalResult())
                    return@readLoop
                }

                val floatSamples = sttInputCleaner.cleanPcm16ToFloat(samples)
                stt.acceptWaveform(floatSamples)

                if (hasSpeechHint && stt.isEndpoint()) {
                    // Stop capture before final decode: Moonshine OfflineRecognizer is not
                    // thread-safe if decode runs while acceptWaveform is still feeding audio.
                    recorder.stop()
                    result.complete(stt.getFinalResult())
                    return@readLoop
                }

                val rms = sqrt(rmsOfFloats(floatSamples)).toFloat()
                if (rms > SPEECH_THRESHOLD) {
                    if (!hasSpeechHint) {
                        speechHintStartedAt = now
                    }
                    hasSpeechHint = true
                    silenceCount = 0
                    leadingSilenceChunks = 0
                } else {
                    if (!hasSpeechHint) {
                        leadingSilenceChunks++
                        if (leadingSilenceChunks > LEADING_SILENCE_CHUNKS) {
                            Log.d(TAG, "Sherpa: no speech within leading silence budget")
                            recorder.stop()
                            result.complete("")
                            return@readLoop
                        }
                    } else {
                        silenceCount++
                    }
                }

                // Moonshine never reports isEndpoint(); noisy mics can keep RMS high forever so
                // silenceCount never grows — cap utterance length so we always leave Listening.
                if (hasSpeechHint && speechHintStartedAt > 0L &&
                    now - speechHintStartedAt >= MAX_UTTERANCE_AFTER_SPEECH_MS
                ) {
                    Log.d(TAG, "Sherpa: max utterance length reached")
                    recorder.stop()
                    result.complete(stt.getFinalResult())
                    return@readLoop
                }

                if (hasSpeechHint && silenceCount > SILENCE_CHUNKS_AFTER_SPEECH) {
                    recorder.stop()
                    result.complete(stt.getFinalResult())
                    return@readLoop
                }
            }
        }

        val awaited = withTimeoutOrNull(timeoutMs) { result.await() }
        recorder.stop()
        recordJob?.cancelAndJoin()
        val text = awaited ?: stt.getFinalResult()

        stt.endStream()
        return text
    }

    private suspend fun listenWithPlatformSpeechRecognizer(
        onPartialText: (String) -> Unit,
        timeoutMs: Long
    ): String = withContext(Dispatchers.Main.immediate) {
        Log.d(TAG, "Using platform SpeechRecognizer")

        val text = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val sr = SpeechRecognizer.createSpeechRecognizer(appContext)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    // Tolerate a short mid-sentence pause without dragging out end-of-speech
                    // detection — this silence window delays every response.
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        1_200L
                    )
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        900L
                    )
                }

                val mainHandler = Handler(Looper.getMainLooper())
                val forceStop = Runnable {
                    try {
                        sr.stopListening()
                    } catch (_: Exception) {
                    }
                }

                val listener = object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "SpeechRecognizer: ready")
                    }

                    override fun onBeginningOfSpeech() {
                        Log.d(TAG, "SpeechRecognizer: speech started")
                    }

                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.d(TAG, "SpeechRecognizer: speech ended (VAD)")
                    }

                    override fun onError(error: Int) {
                        Log.e(TAG, "SpeechRecognizer error: $error")
                        mainHandler.removeCallbacks(forceStop)
                        sr.destroy()
                        if (cont.isActive) cont.resume("")
                    }

                    override fun onResults(results: Bundle?) {
                        val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull().orEmpty()
                        Log.d(TAG, "SpeechRecognizer result: $t")
                        mainHandler.removeCallbacks(forceStop)
                        sr.destroy()
                        if (cont.isActive) cont.resume(t)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                }

                sr.setRecognitionListener(listener)
                sr.startListening(intent)
                mainHandler.postDelayed(forceStop, MAX_RECORDING_DURATION_MS)

                cont.invokeOnCancellation {
                    mainHandler.removeCallbacks(forceStop)
                    try { sr.stopListening() } catch (_: Exception) {}
                    sr.destroy()
                }
            }
        } ?: ""

        text
    }

    /**
     * Fallback: record raw audio and use energy-based VAD to detect when the user
     * starts and stops speaking. No transcription — returns "[voice]" when speech
     * was detected so the caller knows it happened.
     */
    private suspend fun listenWithVadOnly(
        timeoutMs: Long,
        onPartialText: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        if (!recorder.start()) {
            Log.e(TAG, "VAD: failed to start recorder")
            return@withContext ""
        }

        Log.d(TAG, "VAD: recording started")
        onPartialText("Listening…")

        val result = CompletableDeferred<Boolean>()
        var silenceFrames = 0
        var speechFrames = 0
        var hasSpeechStarted = false
        val recordingStartedAt = SystemClock.elapsedRealtime()

        recordJob = scope.launch {
            recorder.readLoop { samples ->
                if (SystemClock.elapsedRealtime() - recordingStartedAt >= MAX_RECORDING_DURATION_MS) {
                    Log.d(TAG, "VAD: max recording duration reached")
                    result.complete(hasSpeechStarted)
                    return@readLoop
                }

                val rms = sqrt(rmsOfPcm16(samples)).toFloat()

                if (rms > SPEECH_THRESHOLD) {
                    speechFrames++
                    silenceFrames = 0

                    if (speechFrames >= MIN_SPEECH_FRAMES && !hasSpeechStarted) {
                        hasSpeechStarted = true
                        Log.d(TAG, "VAD: speech detected")
                        onPartialText("Speaking…")
                    }
                } else {
                    if (hasSpeechStarted) {
                        silenceFrames++
                        if (silenceFrames > SILENCE_CHUNKS_AFTER_SPEECH) {
                            Log.d(TAG, "VAD: silence after speech, stopping")
                            result.complete(true)
                            return@readLoop
                        }
                    }
                }
            }
        }

        val speechDetected = withTimeoutOrNull(timeoutMs) { result.await() } ?: false
        recorder.stop()
        recordJob?.cancel()

        if (speechDetected) "[voice]" else ""
    }

    /**
     * Streams TTS audio (low latency: playback starts while later sentences synthesize).
     * Falls back to Android [TextToSpeech] when Sherpa/Kitten models are not installed.
     * [onRevealed] runs in parallel with a bounded-time character ramp so the bubble animates without
     * blocking synthesis or playback.
     */
    suspend fun speak(
        text: String,
        onRevealed: suspend (String) -> Unit = {}
    ) {
        if (text.isBlank()) {
            Log.w(TAG, "speak skipped: blank text")
            return
        }
        if (!tts.isInitialized) {
            awaitPetVoiceEnginesReady(timeoutMs = 90_000L)
        }

        _state.value = PipelineState.SPEAKING
        val trimmed = text.trim()
        try {
            if (tts.isInitialized) {
                speakWithSherpa(trimmed, onRevealed)
            } else {
                Log.w(
                    TAG,
                    "Sherpa TTS not ready (no model in files/models). Falling back to platform TextToSpeech.",
                )
                speakWithPlatform(trimmed, onRevealed)
            }
        } finally {
            _state.value = PipelineState.IDLE
        }
    }

    private suspend fun speakWithSherpa(
        trimmed: String,
        onRevealed: suspend (String) -> Unit,
    ) {
        val speakerId = preferenceManager.getTtsSpeakerId()
        player.outputVolume = preferenceManager.getTtsVolume()
        coroutineScope {
            val channel = Channel<FloatArray>(capacity = 2)
            val producer = launch(Dispatchers.Default) {
                try {
                    for (sentence in SherpaTTS.splitIntoSentences(trimmed)) {
                        val s = sentence.trim()
                        if (s.isEmpty()) continue
                        val samples = tts.generate(s, speakerId = speakerId)
                        if (samples != null && samples.isNotEmpty()) {
                            channel.send(samples)
                        }
                    }
                } finally {
                    channel.close()
                }
            }
            val revealJob = launch {
                animateDialogueWhileSpeaking(trimmed, onRevealed)
            }
            val playbackJob = launch(Dispatchers.IO) {
                player.playStreaming(channel, tts.sampleRate)
            }
            producer.join()
            playbackJob.join()
            revealJob.cancelAndJoin()
            onRevealed(trimmed)
        }
    }

    private suspend fun speakWithPlatform(
        trimmed: String,
        onRevealed: suspend (String) -> Unit,
    ) = coroutineScope {
        val revealJob = launch {
            animateDialogueWhileSpeaking(trimmed, onRevealed)
        }
        val ok = platformTts.speak(trimmed)
        revealJob.cancelAndJoin()
        onRevealed(trimmed)
        if (!ok) {
            Log.e(TAG, "Platform TTS also failed — no voice output available")
        }
    }

    private suspend fun animateDialogueWhileSpeaking(
        fullText: String,
        onRevealed: suspend (String) -> Unit
    ) {
        if (fullText.isEmpty()) return
        val totalMs = (fullText.length * DIALOGUE_MS_PER_CHAR)
            .coerceIn(MIN_DIALOGUE_TOTAL_MS, MAX_DIALOGUE_TOTAL_MS)
        val ticks = (totalMs / REVEAL_TICK_MS).toInt().coerceAtLeast(3)
        for (t in 1..ticks) {
            val n = (t * fullText.length / ticks).coerceIn(0, fullText.length)
            onRevealed(fullText.take(n))
            delay(REVEAL_TICK_MS)
        }
        onRevealed(fullText)
    }

    fun stopListening() {
        recorder.stop()
        recordJob?.cancel()
        stt.endStream()
        _state.value = PipelineState.IDLE
    }

    fun stopSpeaking() {
        player.stop()
        platformTts.stop()
        _state.value = PipelineState.IDLE
    }

    fun release() {
        petVoicePrepareJob?.cancel()
        petVoicePrepareJob = null
        recorder.release()
        player.release()
        platformTts.release()
        stt.release()
        tts.release()
        _state.value = PipelineState.IDLE
    }

    companion object {
        private const val TAG = "AudioPipeline"
        private fun rmsOfFloats(samples: FloatArray): Double {
            if (samples.isEmpty()) return 0.0
            var sum = 0.0
            for (x in samples) {
                val d = x.toDouble()
                sum += d * d
            }
            return sum / samples.size
        }

        private fun rmsOfPcm16(samples: ShortArray): Double {
            if (samples.isEmpty()) return 0.0
            val scale = 1.0 / Short.MAX_VALUE
            var sum = 0.0
            for (s in samples) {
                val x = s * scale
                sum += x * x
            }
            return sum / samples.size
        }

        /** Hard cap so long continuous speech does not hit client timeouts with empty STT. */
        private const val MAX_RECORDING_DURATION_MS = 60_000L

        /** Default wall-clock budget for listen (must allow [MAX_RECORDING_DURATION_MS] to elapse). */
        private const val DEFAULT_LISTEN_TIMEOUT_MS = 65_000L

        private const val SPEECH_THRESHOLD = 0.025f
        private const val MIN_SPEECH_FRAMES = 3

        /** ~0.2s per chunk — 5 ≈ 1.0s of trailing silence before we stop listening. */
        private const val SILENCE_CHUNKS_AFTER_SPEECH = 5

        /** ~0.2s per chunk at 16 kHz / 3200 samples — 50 ≈ 10s with no voiced audio. */
        private const val LEADING_SILENCE_CHUNKS = 50

        /** Hard stop after speech energy was seen (handles constant background noise above threshold). */
        private const val MAX_UTTERANCE_AFTER_SPEECH_MS = 28_000L

        private const val REVEAL_TICK_MS = 36L
        private const val DIALOGUE_MS_PER_CHAR = 16L
        private const val MIN_DIALOGUE_TOTAL_MS = 200L
        private const val MAX_DIALOGUE_TOTAL_MS = 4_800L
    }
}
