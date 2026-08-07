package com.kawaiipet.app.assets

import com.kawaiipet.app.audio.DefaultVoiceModels

/**
 * Catalog of runtime assets that must be on disk before the pet UI is shown.
 */
object RequiredAssets {

    const val LLM_MODEL_ID = "smollm2-135m-instruct"
    const val LLM_FILE_NAME = "SmolLM2_135M_Instruct.litertlm"

    val ALL: List<AssetSpec> = listOf(
        AssetSpec(
            id = LLM_MODEL_ID,
            displayName = "Pet brain",
            url = "https://huggingface.co/litert-community/SmolLM2-135M-Instruct/resolve/main/" +
                LLM_FILE_NAME,
            kind = AssetKind.RawFile(fileName = LLM_FILE_NAME),
        ),
        AssetSpec(
            id = DefaultVoiceModels.STT_MODEL_ID,
            displayName = "Speech recognition",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                "${DefaultVoiceModels.STT_MODEL_ID}.tar.bz2",
            kind = AssetKind.TarBz2,
        ),
        AssetSpec(
            id = DefaultVoiceModels.TTS_MODEL_ID,
            displayName = "Pet voice",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
                "${DefaultVoiceModels.TTS_MODEL_ID}.tar.bz2",
            kind = AssetKind.TarBz2,
        ),
    )
}

data class AssetSpec(
    val id: String,
    val displayName: String,
    val url: String,
    val kind: AssetKind,
)

sealed class AssetKind {
    data class RawFile(val fileName: String) : AssetKind()
    data object TarBz2 : AssetKind()
}
