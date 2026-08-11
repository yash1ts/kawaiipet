package com.kawaiipet.app.audio

/**
 * Shared ONNX Runtime tuning for Sherpa STT/TTS.
 * Cap at 4 threads — spilling onto little cores on big.LITTLE phones is often slower.
 */
object SherpaOnnxRuntime {
    fun numThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    fun preferredProviders(): List<String> = listOf("xnnpack", "cpu")
}
