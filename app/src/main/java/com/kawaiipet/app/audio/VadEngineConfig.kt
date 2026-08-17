package com.kawaiipet.app.audio

/**
 * Silero VAD tuning for pet listen turns (noisy rooms + short conversational utterances).
 *
 * Feed ungained float PCM at [SttEngineConfig.SAMPLE_RATE] in windows of [WINDOW_SIZE].
 * [THRESHOLD] and [MIN_SILENCE_SEC] can be overridden via [com.kawaiipet.app.util.PreferenceManager].
 */
object VadEngineConfig {
    /** Silero expects 512 / 1024 / 1536 @ 16 kHz — 512 ≈ 32 ms, lowest latency. */
    const val WINDOW_SIZE = 512

    /**
     * Speech probability gate. Silero default 0.5.
     */
    const val THRESHOLD = 0.5f
    const val THRESHOLD_MIN = 0.30f
    const val THRESHOLD_MAX = 0.80f

    /**
     * How long trailing non-speech must last before Silero closes a segment.
     * 1.5s so a mid-sentence pause does not cut the user off.
     */
    const val MIN_SILENCE_SEC = 1.50f
    const val MIN_SILENCE_SEC_MIN = 0.40f
    const val MIN_SILENCE_SEC_MAX = 2.50f

    /**
     * Ignore brief noise blips shorter than this before counting as speech.
     * Matches sherpa-onnx default.
     */
    const val MIN_SPEECH_SEC = 0.25f

    /**
     * After this much continuous speech Silero temporarily raises the threshold to 0.9
     * (force-split). Keep high enough for a long pet utterance.
     */
    const val MAX_SPEECH_SEC = 15f

    /**
     * App-side confirm: consecutive mic chunks with [Vad.isSpeechDetected]
     * before we start counting speech (~8 × ~32 ms ≈ 256 ms).
     */
    const val SPEECH_START_CONFIRM_CHUNKS = 8

    /**
     * Backup end-of-utterance if Silero segment hasn't flushed yet.
     * ~47 × 32 ms ≈ 1.5 s, matched to [MIN_SILENCE_SEC].
     */
    const val SILENCE_END_CHUNKS = 47
}
