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

    /** Sherpa-ONNX KittenTTS English nano v0.2. */
    const val TTS_MODEL_ID = "kitten-nano-en-v0_2-fp16"
}
