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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume
import com.kawaiipet.app.pet.SessionConfigStore
import com.kawaiipet.app.util.DebugSessionLog
import com.kawaiipet.app.util.PreferenceManager

class AudioPipeline(
    private val appContext: Context,
    private val stt: SherpaSTT,
    private val tts: SherpaTTS,
    private val vad: SherpaVad,
    private val recorder: AudioRecordManager,
    private val player: AudioTrackManager,
    private val preferenceManager: PreferenceManager,
    private val sessionConfigStore: SessionConfigStore,
) {
    private var recordJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sttInputCleaner = SttInputCleaner()
    private val speakMutex = Mutex()
    private val speakGeneration = AtomicInteger(0)

    /** One-shot load of STT+TTS for the current pet session; see [schedulePetVoiceModelPrepare]. */
    private var petVoicePrepareJob: Job? = null
    private val _enginesReady = MutableStateFlow(false)
    val enginesReady: StateFlow<Boolean> = _enginesReady.asStateFlow()

    var onAmplitude: ((Float) -> Unit)?
        get() = player.onAmplitude
        set(value) { player.onAmplitude = value }

    val isSttReady: Boolean get() = stt.isInitialized
    val isTtsReady: Boolean get() = tts.isInitialized
    val isVadReady: Boolean get() = vad.isInitialized

    fun initializeSTT(modelId: String): Boolean = stt.initialize(modelId)
    fun initializeTTS(modelId: String): Boolean = tts.initialize(modelId)
    fun initializeVad(): Boolean {
        val cfg = sessionConfigStore.snapshot()
        return vad.initialize(cfg.vadThreshold, cfg.vadMinSilenceSec)
    }

    /**
     * One discarded synth so Kitten's first real sentence isn't a cold kernel compile.
     * Safe to call after [initializeTTS]; does not play audio.
     */
    suspend fun primeTts() {
        if (!tts.isInitialized) return
        val speakerId = preferenceManager.getTtsSpeakerId()
        val samples = withContext(Dispatchers.Default) {
            tts.generate("Hi.", speakerId = speakerId)
        }
        Log.d(TAG, "TTS primed samples=${samples?.size ?: 0}")
    }

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
            try {
                val vadOk = initializeVad()
                Log.d(TAG, "Pet voice prepare: Silero VAD success=$vadOk")
                if (loadStt && sttId.isNotBlank()) {
                    val ok = initializeSTT(sttId)
                    Log.d(TAG, "Pet voice prepare: STT id=$sttId success=$ok")
                }
                if (loadTts && ttsId.isNotBlank()) {
                    val ok = initializeTTS(ttsId)
                    Log.d(TAG, "Pet voice prepare: TTS id=$ttsId success=$ok")
                    if (ok) {
                        runCatching { primeTts() }
                            .onFailure { Log.d(TAG, "TTS prime during prepare failed", it) }
                    }
                }
            } finally {
                _enginesReady.value = stt.isInitialized || tts.isInitialized
            }
        }
    }

    /**
     * Waits for [schedulePetVoiceModelPrepare] to finish (up to [timeoutMs]), so engines are ready to reuse.
     */
    suspend fun awaitPetVoiceEnginesReady(timeoutMs: Long = 90_000L) {
        // Already ready (e.g. VoiceEngineWarmup finished) — do not spin.
        if (stt.isInitialized && tts.isInitialized) {
            _enginesReady.value = true
            return
        }
        withTimeoutOrNull(timeoutMs) {
            val job = petVoicePrepareJob
            if (job != null) {
                job.join()
            } else {
                // Wait until prepare is scheduled or engines become ready.
                while (petVoicePrepareJob == null && !stt.isInitialized) {
                    delay(50)
                }
                petVoicePrepareJob?.join()
            }
        }
        _enginesReady.value = stt.isInitialized || tts.isInitialized
    }

    /**
     * Listens for speech, transcribes it, and returns the text.
     * Silero VAD detects when the user starts/stops speaking.
     *
     * Priority: Sherpa STT → Platform SpeechRecognizer → Silero VAD only (no transcription).
     */
    suspend fun listenAndTranscribe(
        timeoutMs: Long = DEFAULT_LISTEN_TIMEOUT_MS,
        onPartialText: (String) -> Unit = {}
    ): String {
        Log.d(TAG, "listenAndTranscribe: starting, sttReady=${stt.isInitialized}")

        val awaitTimeoutMs = maxOf(timeoutMs, MAX_RECORDING_DURATION_MS + 3_000L)

        return try {
            when {
                stt.isInitialized -> listenWithSherpa(awaitTimeoutMs)
                isPlatformSttAvailable() -> listenWithPlatformSpeechRecognizer(onPartialText, awaitTimeoutMs)
                else -> {
                    Log.d(TAG, "No STT available, using Silero VAD only")
                    listenWithVadOnly(awaitTimeoutMs, onPartialText)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "listenAndTranscribe failed", e)
            ""
        } finally {
            // listening finished
        }
    }

    private fun isPlatformSttAvailable(): Boolean = try {
        SpeechRecognizer.isRecognitionAvailable(appContext)
    } catch (_: Exception) {
        false
    }

    private suspend fun listenWithSherpa(timeoutMs: Long): String {
        if (!vad.isInitialized && !initializeVad()) {
            Log.e(TAG, "Silero VAD required but not ready")
            return ""
        }
        // Match sherpa-onnx VadAsr: Silero owns utterance bounds; Moonshine decodes
        // completed segment PCM (not a parallel live stream gated on isSpeechDetected).
        sttInputCleaner.reset()
        vad.reset()
        if (!recorder.start()) return ""

        val result = CompletableDeferred<List<com.k2fsa.sherpa.onnx.SpeechSegment>>()
        var hasSpeechHint = false
        var speechHintStartedAt = 0L
        val recordingStartedAt = SystemClock.elapsedRealtime()

        recordJob = scope.launch {
            recorder.readLoop { samples ->
                if (result.isCompleted) return@readLoop
                val now = SystemClock.elapsedRealtime()
                if (now - recordingStartedAt >= MAX_RECORDING_DURATION_MS) {
                    Log.d(TAG, "Silero: max recording duration reached")
                    vad.flush()
                    completeWithVadSegments(result)
                    return@readLoop
                }

                // Official samples: raw float PCM, no AGC (AGC confuses Silero).
                val vadSamples = sttInputCleaner.pcm16ToFloat(samples)
                val isSpeech = vad.acceptAndIsSpeech(vadSamples)
                val segments = vad.drainSegments()
                if (segments.isNotEmpty()) {
                    Log.d(TAG, "Silero: segment ready count=${segments.size}")
                    DebugSessionLog.log(
                        hypothesisId = "VAD",
                        location = "AudioPipeline.vadEndpoint",
                        message = "segment ready",
                        data = mapOf(
                            "count" to segments.size,
                            "elapsedMs" to (now - recordingStartedAt),
                        ),
                    )
                    recorder.stop()
                    result.complete(segments)
                    return@readLoop
                }

                if (isSpeech) {
                    if (!hasSpeechHint) {
                        hasSpeechHint = true
                        speechHintStartedAt = now
                        Log.d(TAG, "Silero: speech detected")
                    }
                } else if (!hasSpeechHint && now - recordingStartedAt >= LEADING_SILENCE_MS) {
                    Log.d(TAG, "Silero: no speech")
                    recorder.stop()
                    result.complete(emptyList())
                    return@readLoop
                }

                if (hasSpeechHint && speechHintStartedAt > 0L &&
                    now - speechHintStartedAt >= MAX_UTTERANCE_AFTER_SPEECH_MS
                ) {
                    Log.d(TAG, "Silero: max utterance length reached")
                    vad.flush()
                    completeWithVadSegments(result)
                    return@readLoop
                }
            }
        }

        val segs = withTimeoutOrNull(timeoutMs) { result.await() } ?: run {
            Log.w(TAG, "Silero: listen timed out")
            vad.flush()
            vad.drainSegments()
        }
        recorder.stop()
        recordJob?.cancelAndJoin()
        return transcribeVadSegments(segs).trim()
    }

    private fun completeWithVadSegments(
        result: CompletableDeferred<List<com.k2fsa.sherpa.onnx.SpeechSegment>>,
    ) {
        if (result.isCompleted) return
        recorder.stop()
        result.complete(vad.drainSegments())
    }

    /**
     * Decode Silero speech segments with Moonshine (sherpa-onnx VadAsr pattern).
     * RMS-levels for ASR only, with a noise-floor guard so we don't boost hush.
     */
    private fun transcribeVadSegments(segments: List<com.k2fsa.sherpa.onnx.SpeechSegment>): String {
        if (segments.isEmpty()) return ""
        val decodeStarted = SystemClock.elapsedRealtime()
        stt.startStream()
        return try {
            var totalSamples = 0
            for (segment in segments) {
                val raw = segment.samples
                if (raw.isEmpty()) continue
                totalSamples += raw.size
                stt.acceptWaveform(sttInputCleaner.levelFloatForAsr(raw))
            }
            val pad = FloatArray(TRAILING_PAD_SAMPLES)
            stt.acceptWaveform(pad)
            val text = stt.getFinalResult().trim()
            val ms = totalSamples * 1000L / SttEngineConfig.SAMPLE_RATE
            val decodeMs = SystemClock.elapsedRealtime() - decodeStarted
            Log.i(TAG, "Moonshine decode samples=$totalSamples (~${ms}ms audio, ${decodeMs}ms) text='${text.take(120)}'")
            DebugSessionLog.log(
                hypothesisId = "ASR",
                location = "AudioPipeline.transcribeVadSegments",
                message = "moonshine decode",
                data = mapOf(
                    "audioMs" to ms,
                    "decodeMs" to decodeMs,
                    "textLen" to text.length,
                ),
            )
            text
        } finally {
            stt.endStream()
        }
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
                        800L
                    )
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        600L
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
     * Fallback when STT isn't loaded: Silero detects speech start/end only.
     * Returns "[voice]" when speech was heard.
     */
    private suspend fun listenWithVadOnly(
        timeoutMs: Long,
        onPartialText: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        if (!vad.isInitialized && !initializeVad()) {
            Log.e(TAG, "Silero VAD not ready")
            return@withContext ""
        }
        if (!recorder.start()) {
            Log.e(TAG, "Silero: failed to start recorder")
            return@withContext ""
        }

        Log.d(TAG, "Silero VAD-only: recording started")
        onPartialText("Listening…")
        vad.reset()
        sttInputCleaner.reset()

        val result = CompletableDeferred<Boolean>()
        var nonSpeechChunks = 0
        var speechConfirmChunks = 0
        var speechChunksTotal = 0
        var hasSpeechStarted = false
        val recordingStartedAt = SystemClock.elapsedRealtime()

        recordJob = scope.launch {
            recorder.readLoop { samples ->
                if (SystemClock.elapsedRealtime() - recordingStartedAt >= MAX_RECORDING_DURATION_MS) {
                    Log.d(TAG, "Silero: max recording duration reached")
                    result.complete(hasSpeechStarted)
                    return@readLoop
                }

                val vadSamples = sttInputCleaner.pcm16ToFloat(samples)
                val isSpeech = vad.acceptAndIsSpeech(vadSamples)
                val segmentEnded = vad.drainSegments().isNotEmpty()

                if (isSpeech) {
                    nonSpeechChunks = 0
                    speechConfirmChunks++
                    if (!hasSpeechStarted &&
                        speechConfirmChunks >= VadEngineConfig.SPEECH_START_CONFIRM_CHUNKS
                    ) {
                        hasSpeechStarted = true
                        Log.d(TAG, "Silero: speech detected")
                        onPartialText("Speaking…")
                    }
                    if (hasSpeechStarted) speechChunksTotal++
                } else {
                    speechConfirmChunks = 0
                    if (hasSpeechStarted) nonSpeechChunks++
                }

                if (hasSpeechStarted && speechChunksTotal >= 5 &&
                    (segmentEnded || nonSpeechChunks > VadEngineConfig.SILENCE_END_CHUNKS)
                ) {
                    Log.d(TAG, "Silero: silence after speech, stopping")
                    result.complete(true)
                    return@readLoop
                }
            }
        }

        val speechDetected = withTimeoutOrNull(timeoutMs) { result.await() } ?: false
        recorder.stop()
        recordJob?.cancel()

        if (speechDetected) "[voice]" else ""
    }

    /**
     * Streams TTS: synthesizes each sentence as it arrives on [sentences] and plays
     * audio as soon as the first chunk is ready (Sherpa KittenTTS / VITS).
     */
    suspend fun speakSentences(
        sentences: ReceiveChannel<String>,
        speakerId: Int? = null,
        volume: Float? = null,
        speed: Float? = null,
    ) {
        speakMutex.withLock {
            val gen = speakGeneration.incrementAndGet()
            if (!tts.isInitialized) {
                awaitPetVoiceEnginesReady(timeoutMs = 90_000L)
            }
            if (!tts.isInitialized) {
                Log.e(TAG, "Sherpa TTS not ready — skipping speak (no system fallback)")
                for (ignored in sentences) { /* drain */ }
                return@withLock
            }

            val resolvedSpeaker = speakerId ?: preferenceManager.getTtsSpeakerId()
            val resolvedVolume = volume ?: preferenceManager.getTtsVolume()
            val resolvedSpeed = speed ?: preferenceManager.getTtsSpeed()
            player.outputVolume = resolvedVolume
            player.playbackSpeed = resolvedSpeed
            try {
                coroutineScope {
                    val pcm = Channel<FloatArray>(capacity = 4)
                    val playbackJob = launch(Dispatchers.IO) {
                        if (speakGeneration.get() != gen) return@launch
                        player.playStreaming(pcm, tts.sampleRate)
                    }
                    val producer = launch(Dispatchers.Default) {
                        try {
                            var index = 0
                            val t0 = SystemClock.elapsedRealtime()
                            for (sentence in sentences) {
                                if (speakGeneration.get() != gen) break
                                val piece = sentence.trim()
                                if (piece.isEmpty()) continue
                                val samples = tts.generate(
                                    piece,
                                    speakerId = resolvedSpeaker,
                                    speed = resolvedSpeed,
                                ) ?: continue
                                if (samples.isEmpty()) continue
                                if (index == 0) {
                                    val firstAudioMs = SystemClock.elapsedRealtime() - t0
                                    Log.i(
                                        TAG,
                                        "Sherpa TTS first audio chunk after " +
                                            "${firstAudioMs}ms (streaming with LLM)",
                                    )
                                    DebugSessionLog.log(
                                        hypothesisId = "TTS",
                                        location = "AudioPipeline.speakSentences",
                                        message = "first audio chunk",
                                        data = mapOf("firstAudioMs" to firstAudioMs),
                                    )
                                }
                                index++
                                pcm.send(samples)
                            }
                            Log.i(TAG, "Sherpa TTS spoke $index sentence(s)")
                        } finally {
                            pcm.close()
                        }
                    }
                    producer.join()
                    playbackJob.join()
                }
            } finally {
                // speak finished
            }
        }
    }

    /**
     * One-shot speak of a full string (splits into sentences internally).
     * Sherpa KittenTTS only — no system TTS fallback.
     */
    suspend fun speak(
        text: String,
        speakerId: Int? = null,
        volume: Float? = null,
        speed: Float? = null,
    ) {
        if (text.isBlank()) {
            Log.w(TAG, "speak skipped: blank text")
            return
        }
        val channel = Channel<String>(capacity = Channel.UNLIMITED)
        coroutineScope {
            launch {
                try {
                    for (s in SherpaTTS.splitIntoSentences(text.trim())) {
                        channel.send(s)
                    }
                } finally {
                    channel.close()
                }
            }
            speakSentences(channel, speakerId = speakerId, volume = volume, speed = speed)
        }
    }

    fun stopListening() {
        recorder.stop()
        recordJob?.cancel()
        stt.endStream()
    }

    fun stopSpeaking() {
        speakGeneration.incrementAndGet()
        player.stop()
    }

    fun release() {
        speakGeneration.incrementAndGet()
        petVoicePrepareJob?.cancel()
        petVoicePrepareJob = null
        recorder.release()
        player.release()
        stt.release()
        tts.release()
        vad.release()
        _enginesReady.value = false
    }

    companion object {
        private const val TAG = "AudioPipeline"

        /** Hard cap so long continuous speech does not hit client timeouts with empty STT. */
        private const val MAX_RECORDING_DURATION_MS = 30_000L

        /** Default wall-clock budget for listen (must allow [MAX_RECORDING_DURATION_MS] to elapse). */
        private const val DEFAULT_LISTEN_TIMEOUT_MS = 35_000L

        /** ~32ms chunks — 30s of leading silence with no voiced audio ends the listen. */
        private const val LEADING_SILENCE_MS = 30_000L

        /** Hard stop after speech was detected. */
        private const val MAX_UTTERANCE_AFTER_SPEECH_MS = 12_000L

        /** ~250ms of zeros appended before offline decode. */
        private const val TRAILING_PAD_SAMPLES = SttEngineConfig.SAMPLE_RATE / 4
    }
}
