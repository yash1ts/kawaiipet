package com.kawaiipet.app.audio

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig

/**
 * Central STT tuning: capture rate, feature extraction, and streaming endpoint rules.
 */
object SttEngineConfig {
    const val SAMPLE_RATE = 16_000
    const val FEATURE_DIM = 80

    /**
     * Tiny dither stabilizes mel / log computations on quiet speech without adding audible noise.
     */
    const val FEATURE_DITHER = 1.0e-5f

    fun featureConfig(): FeatureConfig =
        FeatureConfig(SAMPLE_RATE, FEATURE_DIM, FEATURE_DITHER)

    /**
     * Endpoint rules for Zipformer streaming: more patient trailing silence so pauses
     * mid-sentence aren't cut as aggressively.
     */
    fun endpointConfig(): EndpointConfig = EndpointConfig(
        EndpointRule(true, minTrailingSilence = 2.8f, minUtteranceLength = 0f),
        EndpointRule(false, minTrailingSilence = 2.0f, minUtteranceLength = 0f),
        EndpointRule(false, minTrailingSilence = 0f, minUtteranceLength = 0f)
    )
}
