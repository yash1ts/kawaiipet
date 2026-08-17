package com.kawaiipet.app.util

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Haptics + standard UI sounds ([AudioManager] FX) + short system tones ([ToneGenerator] ack/prompt).
 * Touch FX follow the user's "touch sounds" setting; tones use the notification stream at low volume.
 */
@Singleton
class UiFeedback @Inject constructor(@ApplicationContext context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager: AudioManager? =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** Tap / confirm (buttons, pet tap, dismiss). */
    fun click() {
        playSound(AudioManager.FX_KEY_CLICK)
        vibratePredefined(VibrationEffect.EFFECT_CLICK, legacyMs = 12L)
    }

    /** Voice engines loading — light cue without a full "mic on" pattern. */
    fun petPreparing() {
        vibratePredefined(VibrationEffect.EFFECT_TICK, legacyMs = 10L)
        playSound(AudioManager.FX_FOCUS_NAVIGATION_UP)
    }

    /** Mic live / listening — short double pulse + standard UI tone. */
    fun petListening() {
        playSound(AudioManager.FX_KEYPRESS_STANDARD)
        vibrateWaveform(longArrayOf(0, 22, 55, 28))
    }

    /** LLM / processing — double bump + soft navigation sound. */
    fun petThinking() {
        playSound(AudioManager.FX_FOCUS_NAVIGATION_UP)
        vibrateWaveform(longArrayOf(0, 14, 38, 18))
    }

    /** Reply about to play — short system "ack" chime + haptic (before TTS). */
    fun petSpeakingStart() {
        vibratePredefined(VibrationEffect.EFFECT_CLICK, legacyMs = 18L)
        playSystemTone(ToneGenerator.TONE_PROP_ACK, durationMs = 130)
    }

    /** Positive emotion after speaking — small chime + tick (not used on neutral/sad/angry). */
    fun petEmotionPositive() {
        vibratePredefined(VibrationEffect.EFFECT_TICK, legacyMs = 10L)
        playSystemTone(ToneGenerator.TONE_PROP_PROMPT, durationMs = 100)
    }

    /** Gentle "heads up" when something went wrong (error / sad state). Haptic only so it does not fight TTS. */
    fun softNegative() {
        vibratePredefined(VibrationEffect.EFFECT_DOUBLE_CLICK, legacyMs = 40L)
    }

    /** Usage-limit interrupt — stronger triple pulse + louder ack before TTS. */
    fun usageNudgeAttention() {
        vibrateWaveform(longArrayOf(0, 55, 70, 55, 70, 90))
        playSystemTone(ToneGenerator.TONE_PROP_ACK, durationMs = 220, volume = NUDGE_TONE_VOLUME)
    }

    private fun playSound(fxConstant: Int) {
        runCatching { audioManager?.playSoundEffect(fxConstant) }
    }

    private fun playSystemTone(
        toneType: Int,
        durationMs: Int,
        volume: Int = TONE_VOLUME,
    ) {
        mainHandler.post {
            runCatching {
                val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, volume.coerceIn(0, 100))
                tg.startTone(toneType, durationMs)
                mainHandler.postDelayed(
                    { runCatching { tg.release() } },
                    (durationMs + 90).toLong()
                )
            }
        }
    }

    private fun vibratePredefined(effectId: Int, legacyMs: Long) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                v.vibrate(VibrationEffect.createPredefined(effectId))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(legacyMs)
            }
        }
    }

    private fun vibrateWaveform(pattern: LongArray, delayMs: Long = 0L) {
        if (delayMs > 0L) {
            mainHandler.postDelayed({ vibrateWaveformNow(pattern) }, delayMs)
        } else {
            vibrateWaveformNow(pattern)
        }
    }

    private fun vibrateWaveformNow(pattern: LongArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            }
        }
    }

    companion object {
        private const val TONE_VOLUME = 28
        private const val NUDGE_TONE_VOLUME = 55
    }
}
