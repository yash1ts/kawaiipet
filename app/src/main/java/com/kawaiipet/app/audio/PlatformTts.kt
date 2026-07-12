package com.kawaiipet.app.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * System [TextToSpeech] fallback when Sherpa/Kitten models are not installed on device.
 */
class PlatformTts(private val appContext: Context) {

    @Volatile
    private var engine: TextToSpeech? = null

    @Volatile
    private var ready = false

    suspend fun ensureReady(): Boolean = withContext(Dispatchers.Main) {
        if (ready && engine != null) return@withContext true
        suspendCancellableCoroutine { cont ->
            var resumed = false
            val tts = TextToSpeech(appContext) { status ->
                if (resumed) return@TextToSpeech
                resumed = true
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    cont.resume(true)
                } else {
                    Log.w(TAG, "Platform TTS init failed status=$status")
                    ready = false
                    cont.resume(false)
                }
            }
            engine = tts
            cont.invokeOnCancellation {
                tts.stop()
                tts.shutdown()
                engine = null
                ready = false
            }
        }.also { ok ->
            if (ok) {
                val tts = engine ?: return@also
                val lang = tts.setLanguage(Locale.US)
                if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "US English missing; trying default locale")
                    tts.language = Locale.getDefault()
                }
                tts.setSpeechRate(1.05f)
                tts.setPitch(1.08f)
            }
        }
    }

    suspend fun speak(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (!ensureReady()) return false
        return withContext(Dispatchers.Main) {
            val tts = engine ?: return@withContext false
            suspendCancellableCoroutine { cont ->
                val utteranceId = UUID.randomUUID().toString()
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.w(TAG, "Platform TTS error utterance=$utteranceId")
                        if (cont.isActive) cont.resume(false)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.w(TAG, "Platform TTS error=$errorCode utterance=$utteranceId")
                        if (cont.isActive) cont.resume(false)
                    }
                })
                cont.invokeOnCancellation {
                    runCatching { tts.stop() }
                }
                val result = tts.speak(trimmed, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                if (result != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "Platform TTS speak() returned $result")
                    if (cont.isActive) cont.resume(false)
                }
            }
        }
    }

    fun stop() {
        runCatching { engine?.stop() }
    }

    fun release() {
        stop()
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
    }

    companion object {
        private const val TAG = "PlatformTts"
    }
}
