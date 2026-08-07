package com.kawaiipet.app.audio

/**
 * Default STT/TTS/LLM model IDs. Weights are downloaded on first launch into
 * `files/models/<id>/` (not shipped in the APK).
 */
object DefaultVoiceModels {
    /** Sherpa-ONNX NeMo Conformer CTC English small (int8 preferred). */
    const val STT_MODEL_ID = "sherpa-onnx-nemo-ctc-en-conformer-small"

    /** Sherpa-ONNX KittenTTS English nano v0.2. */
    const val TTS_MODEL_ID = "kitten-nano-en-v0_2-fp16"
}
