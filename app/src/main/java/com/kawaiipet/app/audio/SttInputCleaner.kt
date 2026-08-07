package com.kawaiipet.app.audio

import kotlin.math.sqrt

/**
 * Light conditioning before Sherpa: DC block + mild high-pass, then capped leveling.
 * Keep gain modest so VAD / decode aren't flooded with amplified noise.
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

    /** Raw RMS of PCM16 in [0, 1] — use for VAD before AGC. */
    fun rawRms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        val scale = 1.0 / 32768.0
        for (s in samples) {
            val x = s * scale
            sum += x * x
        }
        return sqrt(sum / samples.size).toFloat()
    }

    /**
     * Converts 16-bit PCM to float in [-1, 1], DC-blocks, high-passes ~80 Hz, then gentle
     * level normalization with a smoothed gain envelope.
     */
    fun cleanPcm16ToFloat(samples: ShortArray): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)

        val n = samples.size
        val tmp = FloatArray(n)

        val dcAlpha = 0.995f
        val fc = 80f
        val dt = 1f / sampleRate
        val rc = 1f / (2f * Math.PI.toFloat() * fc)
        val hpCoeff = rc / (rc + dt)

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

        val targetRms = 0.06f
        val rawGain = if (rms > 1e-5f) (targetRms / rms).coerceIn(MIN_GAIN, MAX_GAIN) else 1f
        smoothedGain = SMOOTH * smoothedGain + (1f - SMOOTH) * rawGain

        for (i in 0 until n) {
            tmp[i] = (tmp[i] * smoothedGain).coerceIn(-0.97f, 0.97f)
        }
        return tmp
    }

    companion object {
        private const val MIN_GAIN = 0.8f
        private const val MAX_GAIN = 1.8f
        private const val SMOOTH = 0.92f
    }
}
