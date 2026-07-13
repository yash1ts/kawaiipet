package com.kawaiipet.app.audio

/**
 * Default STT/TTS model IDs when the user has not chosen others. Weights are **not** shipped in the APK;
 * users download compatible Sherpa-ONNX packs into app storage (same IDs as k2-fsa release archives).
 */
object DefaultVoiceModels {
    const val STT_MODEL_ID = "moonshine-tiny-en-quantized"

    /**
     * Native piper-plus voice. Expected on-disk layout:
     *   files/models/piper-en_US-amy-medium/
     *     en_US-amy-medium.onnx
     *     en_US-amy-medium.onnx.json
     */
    const val TTS_MODEL_ID = "piper-en_US-amy-medium"
}
