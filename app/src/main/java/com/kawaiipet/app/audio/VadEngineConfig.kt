package com.kawaiipet.app.audio

/**
 * Silero VAD tuning for pet listen turns (noisy rooms + short conversational utterances).
 *
 * Feed ungained float PCM at [SttEngineConfig.SAMPLE_RATE] in windows of [WINDOW_SIZE].
 */
object VadEngineConfig {
    /** Silero expects 512 / 1024 / 1536 @ 16 kHz — 512 ≈ 32 ms, lowest latency. */
    const val WINDOW_SIZE = 512

    /**
     * Speech probability gate. Default 0.5.
     * Slightly below default so soft / far talk still trips; not so low that fans look like speech.
     */
    const val THRESHOLD = 0.42f

    /**
     * How long trailing non-speech must last before Silero closes a segment.
     * A bit above default (0.5) so mid-sentence pauses don't split the utterance for Moonshine.
     */
    const val MIN_SILENCE_SEC = 0.65f

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
     * App-side confirm: need this many consecutive mic chunks with [Vad.isSpeechDetected]
     * before we start feeding Moonshine (~3 × ~100 ms).
     */
    const val SPEECH_START_CONFIRM_CHUNKS = 3

    /**
     * Backup end-of-utterance if Silero segment hasn't flushed yet.
     * Keep slightly longer than [MIN_SILENCE_SEC] (~100 ms chunks → 8 ≈ 0.8 s).
     */
    const val SILENCE_END_CHUNKS = 8
}
