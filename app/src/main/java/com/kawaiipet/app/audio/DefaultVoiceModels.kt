package com.kawaiipet.app.audio

/**
 * Default STT/TTS/LLM model IDs. Weights are downloaded on first launch into
 * `files/models/<id>/` (not shipped in the APK).
 */
object DefaultVoiceModels {
    /**
     * Sherpa-ONNX Moonshine base English (quantized) — stronger WER than NeMo CTC small
     * for conversational pet chat; already supported by [SherpaSTT] offline path.
     */
    const val STT_MODEL_ID = "sherpa-onnx-moonshine-base-en-quantized-2026-02-27"

    /**
     * Sherpa-ONNX KittenTTS English nano 0.8 INT8 — same 0.8 family as PocketPal,
     * leaner download than fp32; 8 speakers (sid 0–7).
     */
    const val TTS_MODEL_ID = "kitten-nano-en-v0_8-int8"

    /** Silero neural VAD (~2 MB) — used for speech start/end before Moonshine decode. */
    const val VAD_MODEL_ID = "silero-vad"
    const val VAD_FILE_NAME = "silero_vad.onnx"
}
