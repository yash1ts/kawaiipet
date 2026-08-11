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
import kotlinx.coroutines.channels.ReceiveChannel
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
        var speechFrameCount = 0
        var speechChunksTotal = 0
        var hasSpeechHint = false
        var speechHintStartedAt = 0L
        /** Decaying speech peak — absolute peak made end-of-speech require shouting. */
        var speechPeak = 0f
        var speechLevel = 0f
        var noiseFloor = 0.006f
        var noiseSamples = 0
        val recordingStartedAt = SystemClock.elapsedRealtime()
        // Keep a short pre-roll so ASR still hears word onsets after VAD trips.
        val preRoll = ArrayDeque<FloatArray>(PRE_SPEECH_LOOKBACK_CHUNKS + 1)

        recordJob = scope.launch {
            recorder.readLoop { samples ->
                val now = SystemClock.elapsedRealtime()
                if (now - recordingStartedAt >= MAX_RECORDING_DURATION_MS) {
                    Log.d(TAG, "Sherpa: max recording duration reached")
                    finishListen(result)
                    return@readLoop
                }

                // VAD on speech-band energy (no AGC). Feed leveled audio to the model.
                val energy = sttInputCleaner.speechBandRms(samples)
                val floatSamples = sttInputCleaner.cleanPcm16ToFloat(samples)

                if (hasSpeechHint && stt.isEndpoint()) {
                    finishListen(result)
                    return@readLoop
                }

                // Calibrate + keep adapting ambient noise (slow rise / faster fall).
                if (!hasSpeechHint && noiseSamples < NOISE_CALIBRATION_CHUNKS) {
                    noiseFloor = if (noiseSamples == 0) {
                        energy.coerceAtLeast(0.002f)
                    } else {
                        noiseFloor * 0.80f + energy * 0.20f
                    }
                    noiseSamples++
                } else if (!hasSpeechHint || energy < noiseFloor + END_MARGIN * 3f) {
                    noiseFloor = adaptNoiseFloor(noiseFloor, energy)
                }

                val speechGate = speechGate(noiseFloor)
                val snr = (energy - noiseFloor) / maxOf(noiseFloor, 0.002f)
                val isSpeech = energy >= speechGate || snr >= SPEECH_SNR

                if (isSpeech) {
                    speechFrameCount++
                    speechPeak = maxOf(speechPeak * 0.92f, energy)
                    speechLevel = if (speechLevel <= 0f) energy else speechLevel * 0.65f + energy * 0.35f
                    silenceCount = 0
                    leadingSilenceChunks = 0
                    if (!hasSpeechHint && speechFrameCount >= MIN_SPEECH_FRAMES) {
                        hasSpeechHint = true
                        speechHintStartedAt = now
                        // Flush lookback + current chunk so Moonshine isn't starved of onsets.
                        while (preRoll.isNotEmpty()) {
                            stt.acceptWaveform(preRoll.removeFirst())
                        }
                        stt.acceptWaveform(floatSamples)
                        Log.d(
                            TAG,
                            "Sherpa: speech start e=%.4f gate=%.4f noise=%.4f snr=%.2f".format(
                                energy,
                                speechGate,
                                noiseFloor,
                                snr,
                            ),
                        )
                    } else if (hasSpeechHint) {
                        stt.acceptWaveform(floatSamples)
                    } else {
                        pushPreRoll(preRoll, floatSamples)
                    }
                    if (hasSpeechHint) speechChunksTotal++
                } else {
                    speechFrameCount = 0
                    // Decay peak so a loud first word doesn't freeze a high end threshold.
                    speechPeak *= 0.90f
                    if (!hasSpeechHint) {
                        pushPreRoll(preRoll, floatSamples)
                        leadingSilenceChunks++
                        if (leadingSilenceChunks > LEADING_SILENCE_CHUNKS) {
                            Log.d(
                                TAG,
                                "Sherpa: no speech (noise=%.4f gate=%.4f)".format(
                                    noiseFloor,
                                    speechGate,
                                ),
                            )
                            recorder.stop()
                            result.complete("")
                            return@readLoop
                        }
                    } else {
                        stt.acceptWaveform(floatSamples)
                        val endFloor = endOfSpeechFloor(noiseFloor, speechPeak, speechLevel)
                        if (energy <= endFloor && speechChunksTotal >= MIN_SPEECH_CHUNKS_BEFORE_END) {
                            silenceCount++
                        } else {
                            // Background noise blip — don't accumulate forever, ease off slowly.
                            if (silenceCount > 0) silenceCount--
                        }
                    }
                }

                if (hasSpeechHint && speechHintStartedAt > 0L &&
                    now - speechHintStartedAt >= MAX_UTTERANCE_AFTER_SPEECH_MS
                ) {
                    Log.d(TAG, "Sherpa: max utterance length reached")
                    finishListen(result)
                    return@readLoop
                }

                if (hasSpeechHint && silenceCount > SILENCE_CHUNKS_AFTER_SPEECH) {
                    val endFloor = endOfSpeechFloor(noiseFloor, speechPeak, speechLevel)
                    Log.d(
                        TAG,
                        "Sherpa: end silence e=%.4f floor=%.4f noise=%.4f chunks=%d voiced=%d".format(
                            energy,
                            endFloor,
                            noiseFloor,
                            silenceCount,
                            speechChunksTotal,
                        ),
                    )
                    finishListen(result)
                    return@readLoop
                }
            }
        }

        val awaited = withTimeoutOrNull(timeoutMs) { result.await() }
        recorder.stop()
        recordJob?.cancelAndJoin()
        val text = awaited ?: finalizeSherpaTranscript()

        stt.endStream()
        return text.trim()
    }

    private fun pushPreRoll(preRoll: ArrayDeque<FloatArray>, chunk: FloatArray) {
        preRoll.addLast(chunk)
        while (preRoll.size > PRE_SPEECH_LOOKBACK_CHUNKS) {
            preRoll.removeFirst()
        }
    }

    /** Pad a short silence tail, then decode — offline ASR often drops final phones without it. */
    private fun finalizeSherpaTranscript(): String {
        val pad = FloatArray(TRAILING_PAD_SAMPLES)
        stt.acceptWaveform(pad)
        return stt.getFinalResult()
    }

    private fun finishListen(result: CompletableDeferred<String>) {
        if (result.isCompleted) return
        recorder.stop()
        result.complete(finalizeSherpaTranscript())
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
                        1_400L
                    )
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        1_100L
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
        var speechChunksTotal = 0
        var hasSpeechStarted = false
        var speechPeak = 0f
        var speechLevel = 0f
        var noiseFloor = 0.006f
        var noiseSamples = 0
        val recordingStartedAt = SystemClock.elapsedRealtime()
        val cleaner = SttInputCleaner()

        recordJob = scope.launch {
            recorder.readLoop { samples ->
                if (SystemClock.elapsedRealtime() - recordingStartedAt >= MAX_RECORDING_DURATION_MS) {
                    Log.d(TAG, "VAD: max recording duration reached")
                    result.complete(hasSpeechStarted)
                    return@readLoop
                }

                val energy = cleaner.speechBandRms(samples)
                if (!hasSpeechStarted && noiseSamples < NOISE_CALIBRATION_CHUNKS) {
                    noiseFloor = if (noiseSamples == 0) {
                        energy.coerceAtLeast(0.002f)
                    } else {
                        noiseFloor * 0.80f + energy * 0.20f
                    }
                    noiseSamples++
                } else if (!hasSpeechStarted || energy < noiseFloor + END_MARGIN * 3f) {
                    noiseFloor = adaptNoiseFloor(noiseFloor, energy)
                }

                val gate = speechGate(noiseFloor)
                val snr = (energy - noiseFloor) / maxOf(noiseFloor, 0.002f)
                val isSpeech = energy >= gate || snr >= SPEECH_SNR

                if (isSpeech) {
                    speechFrames++
                    speechPeak = maxOf(speechPeak * 0.92f, energy)
                    speechLevel = if (speechLevel <= 0f) energy else speechLevel * 0.65f + energy * 0.35f
                    silenceFrames = 0
                    if (speechFrames >= MIN_SPEECH_FRAMES && !hasSpeechStarted) {
                        hasSpeechStarted = true
                        Log.d(TAG, "VAD: speech detected e=%.4f noise=%.4f".format(energy, noiseFloor))
                        onPartialText("Speaking…")
                    }
                    if (hasSpeechStarted) speechChunksTotal++
                } else {
                    speechFrames = 0
                    speechPeak *= 0.90f
                    if (hasSpeechStarted) {
                        val endFloor = endOfSpeechFloor(noiseFloor, speechPeak, speechLevel)
                        if (energy <= endFloor && speechChunksTotal >= MIN_SPEECH_CHUNKS_BEFORE_END) {
                            silenceFrames++
                            if (silenceFrames > SILENCE_CHUNKS_AFTER_SPEECH) {
                                Log.d(TAG, "VAD: silence after speech, stopping")
                                result.complete(true)
                                return@readLoop
                            }
                        } else if (silenceFrames > 0) {
                            silenceFrames--
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
     * Streams TTS: synthesizes each sentence as it arrives on [sentences] and plays
     * audio as soon as the first chunk is ready (Sherpa KittenTTS / VITS).
     */
    suspend fun speakSentences(sentences: ReceiveChannel<String>) {
        if (!tts.isInitialized) {
            awaitPetVoiceEnginesReady(timeoutMs = 90_000L)
        }
        if (!tts.isInitialized) {
            Log.e(TAG, "Sherpa TTS not ready — skipping speak (no system fallback)")
            for (ignored in sentences) { /* drain */ }
            return
        }

        val speakerId = preferenceManager.getTtsSpeakerId()
        _state.value = PipelineState.SPEAKING
        player.outputVolume = preferenceManager.getTtsVolume()
        try {
            coroutineScope {
                val pcm = Channel<FloatArray>(capacity = 4)
                val playbackJob = launch(Dispatchers.IO) {
                    player.playStreaming(pcm, tts.sampleRate)
                }
                val producer = launch(Dispatchers.Default) {
                    try {
                        var index = 0
                        val t0 = SystemClock.elapsedRealtime()
                        for (sentence in sentences) {
                            val piece = sentence.trim()
                            if (piece.isEmpty()) continue
                            val samples = tts.generate(piece, speakerId = speakerId) ?: continue
                            if (samples.isEmpty()) continue
                            if (index == 0) {
                                Log.i(
                                    TAG,
                                    "Sherpa TTS first audio chunk after " +
                                        "${SystemClock.elapsedRealtime() - t0}ms " +
                                        "(streaming with LLM)",
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
            _state.value = PipelineState.IDLE
        }
    }

    /**
     * One-shot speak of a full string (splits into sentences internally).
     * Sherpa KittenTTS only — no system TTS fallback.
     */
    suspend fun speak(text: String) {
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
            speakSentences(channel)
        }
    }

    fun stopListening() {
        recorder.stop()
        recordJob?.cancel()
        stt.endStream()
        _state.value = PipelineState.IDLE
    }

    fun stopSpeaking() {
        player.stop()
        _state.value = PipelineState.IDLE
    }

    fun release() {
        petVoicePrepareJob?.cancel()
        petVoicePrepareJob = null
        recorder.release()
        player.release()
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
        private const val MAX_RECORDING_DURATION_MS = 30_000L

        /** Default wall-clock budget for listen (must allow [MAX_RECORDING_DURATION_MS] to elapse). */
        private const val DEFAULT_LISTEN_TIMEOUT_MS = 35_000L

        /** First chunks used to estimate ambient noise (100ms each). */
        private const val NOISE_CALIBRATION_CHUNKS = 10

        /** Absolute margin above noise floor to count as speech. */
        private const val SPEECH_MARGIN = 0.0032f
        private const val MIN_SPEECH_GATE = 0.005f

        /**
         * Relative SNR: (energy - noise) / noise. Lets quiet speech win in a quiet room
         * and still reject steady background noise.
         */
        private const val SPEECH_SNR = 1.25f

        /** End-of-speech: just above ambient, relative to recent speech level. */
        private const val END_MARGIN = 0.0022f
        private const val MIN_END_FLOOR = 0.003f
        private const val SPEECH_END_RATIO = 0.38f
        private const val SPEECH_LEVEL_END_RATIO = 0.46f
        /** Cap so loud first syllables don't lock a high end threshold. */
        private const val MAX_END_ABOVE_NOISE = 0.018f

        /** Need this many speech chunks before we treat it as real speech (~200ms). */
        private const val MIN_SPEECH_FRAMES = 2

        /** Don't cut off until we've heard ~400ms of voiced audio. */
        private const val MIN_SPEECH_CHUNKS_BEFORE_END = 4

        /** ~100ms per chunk — 16 ≈ 1.6s trailing silence (was 1.0s; clipped mid-thought). */
        private const val SILENCE_CHUNKS_AFTER_SPEECH = 16

        /** Keep ~300ms before VAD speech-start so first phonemes aren't clipped for offline ASR. */
        private const val PRE_SPEECH_LOOKBACK_CHUNKS = 3

        /** ~250ms of zeros appended before offline decode. */
        private const val TRAILING_PAD_SAMPLES = SttEngineConfig.SAMPLE_RATE / 4

        /** ~100ms per chunk — 100 ≈ 10s with no voiced audio. */
        private const val LEADING_SILENCE_CHUNKS = 100

        /** Hard stop after speech was detected. */
        private const val MAX_UTTERANCE_AFTER_SPEECH_MS = 10_000L

        private fun adaptNoiseFloor(floor: Float, energy: Float): Float {
            val e = energy.coerceAtLeast(0.001f)
            return if (e < floor) {
                // Track downward quickly when the room gets quieter.
                floor * 0.88f + e * 0.12f
            } else {
                // Rise slowly so brief spikes don't raise the gate.
                floor * 0.985f + e * 0.015f
            }
        }

        private fun speechGate(noiseFloor: Float): Float =
            maxOf(MIN_SPEECH_GATE, noiseFloor + SPEECH_MARGIN, noiseFloor * (1f + SPEECH_SNR * 0.35f))

        private fun endOfSpeechFloor(
            noiseFloor: Float,
            speechPeak: Float,
            speechLevel: Float,
        ): Float {
            val relative = maxOf(
                speechPeak * SPEECH_END_RATIO,
                speechLevel * SPEECH_LEVEL_END_RATIO,
            )
            val aboveNoise = noiseFloor + END_MARGIN
            val uncapped = maxOf(MIN_END_FLOOR, aboveNoise, relative)
            return minOf(uncapped, noiseFloor + MAX_END_ABOVE_NOISE)
        }
    }
}
