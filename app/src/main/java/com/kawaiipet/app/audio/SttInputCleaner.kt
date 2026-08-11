package com.kawaiipet.app.audio

import kotlin.math.sqrt

/**
 * Light conditioning before Sherpa: DC block + speech-band high-pass, then capped leveling.
 * Speech start/end is handled by Silero VAD on [pcm16ToFloat] (no AGC); Moonshine gets
 * [cleanPcm16ToFloat].
 */
class SttInputCleaner(
    private val sampleRate: Int = SttEngineConfig.SAMPLE_RATE
) {
    private var dcEstimate = 0f
    private var hpXPrev = 0f
    private var hpYPrev = 0f
    private var smoothedGain = 1f

    fun reset() {
        dcEstimate = 0f
        hpXPrev = 0f
        hpYPrev = 0f
        smoothedGain = 1f
    }

    /**
     * Raw PCM16 → float in [-1, 1] for Silero. No gain — AGC makes ambient noise look like speech.
     */
    fun pcm16ToFloat(samples: ShortArray): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)
        return FloatArray(samples.size) { i -> samples[i].toFloat() / 32768f }
    }

    /**
     * Converts 16-bit PCM to float in [-1, 1], DC-blocks, high-passes, then gentle
     * level normalization with a smoothed gain envelope (helps quiet speakers for ASR).
     */
    fun cleanPcm16ToFloat(samples: ShortArray): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)

        val n = samples.size
        val tmp = FloatArray(n)

        val dcAlpha = 0.995f
        val hpCoeff = highPassCoeff(HP_CUTOFF_HZ)

        for (i in 0 until n) {
            val xIn = samples[i].toFloat() / 32768f
            dcEstimate = dcAlpha * dcEstimate + (1f - dcAlpha) * xIn
            val x = xIn - dcEstimate
            val y = hpCoeff * (hpYPrev + x - hpXPrev)
            hpXPrev = x
            hpYPrev = y
            tmp[i] = y
        }

        var sumSq = 0.0
        for (v in tmp) sumSq += (v * v).toDouble()
        val rms = sqrt(sumSq / n).toFloat()

        val targetRms = 0.08f
        val rawGain = if (rms > 1e-5f) (targetRms / rms).coerceIn(MIN_GAIN, MAX_GAIN) else 1f
        smoothedGain = SMOOTH * smoothedGain + (1f - SMOOTH) * rawGain

        for (i in 0 until n) {
            tmp[i] = (tmp[i] * smoothedGain).coerceIn(-0.97f, 0.97f)
        }
        return tmp
    }

    private fun highPassCoeff(fcHz: Float): Float {
        val dt = 1f / sampleRate
        val rc = 1f / (2f * Math.PI.toFloat() * fcHz)
        return rc / (rc + dt)
    }

    companion object {
        private const val MIN_GAIN = 0.7f
        private const val MAX_GAIN = 4.5f
        private const val SMOOTH = 0.88f
        /** 120 Hz cuts low rumble without eating most vowels. */
        private const val HP_CUTOFF_HZ = 120f
    }
}
