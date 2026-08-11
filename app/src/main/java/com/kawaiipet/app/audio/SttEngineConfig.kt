package com.kawaiipet.app.audio

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig

/**
 * Central STT tuning: capture rate, feature extraction, and streaming endpoint rules.
 *
 * Moonshine (offline) has no built-in VAD knobs in sherpa-onnx — speech bounds come from
 * [SherpaVad] (Silero); capture noise reduction from [AudioRecordManager] NS/AEC.
 */
object SttEngineConfig {
    const val SAMPLE_RATE = 16_000
    const val FEATURE_DIM = 80

    /**
     * Tiny dither stabilizes mel / log computations on quiet speech without adding audible noise.
     * Slightly higher than stock helps very quiet talkers after aggressive NS.
     */
    const val FEATURE_DITHER = 2.0e-5f

    /**
     * Offline transducer blank penalty (no-op for Moonshine / NeMo CTC in sherpa-onnx).
     * Kept mild for any future offline transducer STT.
     */
    const val OFFLINE_BLANK_PENALTY = 0.85f

    fun featureConfig(): FeatureConfig =
        FeatureConfig(SAMPLE_RATE, FEATURE_DIM, FEATURE_DITHER)

    /**
     * Endpoint rules for Zipformer streaming only (unused by Moonshine offline).
     */
    fun endpointConfig(): EndpointConfig = EndpointConfig(
        EndpointRule(true, minTrailingSilence = 2.8f, minUtteranceLength = 0f),
        EndpointRule(false, minTrailingSilence = 2.0f, minUtteranceLength = 0f),
        EndpointRule(false, minTrailingSilence = 0f, minUtteranceLength = 0f)
    )
}
