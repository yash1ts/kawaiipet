package com.kawaiipet.app.audio

import android.content.Context
import android.util.Log
import com.piperplus.PiperPlus

/**
 * Native offline neural TTS backed by the piper-plus engine (`com.piperplus`).
 *
 * Wraps [PiperPlus] (JNI over libpiper_plus.so) and adapts it to the same
 * surface [AudioPipeline] used for the previous Sherpa engine: it converts the
 * engine's 16-bit PCM [ShortArray] chunks into the [FloatArray] mono format the
 * [AudioTrackManager] plays.
 *
 * arm64-v8a only: on other ABIs the native library is absent and
 * [initialize] returns false so callers fall back to platform TTS.
 */
class PiperPlusTts(
    private val appContext: Context,
    private val modelManager: ModelManager,
) {

    private val lock = Any()

    @Volatile
    private var engine: PiperPlus? = null

    @Volatile
    private var initializedForModelId: String? = null

    @Volatile
    var sampleRate: Int = 22050
        private set

    val isInitialized: Boolean
        get() = synchronized(lock) { engine != null }

    fun initialize(modelId: String): Boolean = synchronized(lock) {
        if (modelId.isBlank()) {
            releaseLocked()
            return false
        }
        if (initializedForModelId == modelId && engine != null) return true

        releaseLocked()
        if (!modelManager.isModelDownloaded(modelId)) {
            Log.w(TAG, "TTS model not on disk: $modelId")
            return false
        }
        val paths = modelManager.resolvePiperPlusTts(modelId) ?: run {
            Log.e(TAG, "Not a piper-plus voice bundle (need *.onnx + *.onnx.json): $modelId")
            return false
        }

        return try {
            val created = PiperPlus.create(
                context = appContext,
                modelPath = paths.modelPath,
                configPath = paths.configPath,
                dictDir = null,
            )
            engine = created
            initializedForModelId = modelId
            sampleRate = created.sampleRate
            Log.i(TAG, "piper-plus TTS initialized: $modelId sampleRate=$sampleRate speakers=${created.numSpeakers}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "piper-plus TTS init failed (missing native lib or bad model?)", t)
            engine = null
            initializedForModelId = null
            false
        }
    }

    /** One-shot synthesis. Returns mono float PCM at [sampleRate], or null on failure. */
    fun generate(text: String, speakerId: Int = DEFAULT_SPEAKER_ID): FloatArray? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val e = synchronized(lock) { engine ?: return null }
        return try {
            val pcm = e.synthesize(trimmed, speakerId = speakerId)
            sampleRate = e.sampleRate
            pcm16ToFloat(pcm)
        } catch (t: Throwable) {
            Log.e(TAG, "piper-plus synthesize failed", t)
            null
        }
    }

    /**
     * Sentence-level streaming: synthesizes each sentence with one-shot native
     * [PiperPlus.synthesize] and emits audio as soon as that sentence is ready,
     * so playback can start before later sentences finish.
     *
     * Avoids [PiperPlus.synthesizeStream]: after the last chunk that API leaves
     * the iterator finished, the next `synth_next` throws, and a naive fallback
     * would re-speak the whole utterance (double audio + full-text latency).
     */
    suspend fun generateChunked(
        text: String,
        speakerId: Int = DEFAULT_SPEAKER_ID,
        onChunk: suspend (FloatArray) -> Unit,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val e = synchronized(lock) { engine ?: return }
        for (sentence in splitIntoSentences(trimmed)) {
            val piece = sentence.trim()
            if (piece.isEmpty()) continue
            try {
                val t0 = android.os.SystemClock.elapsedRealtime()
                val pcm = e.synthesize(piece, speakerId = speakerId)
                sampleRate = e.sampleRate
                Log.d(
                    TAG,
                    "synthesized sentence in ${android.os.SystemClock.elapsedRealtime() - t0}ms " +
                        "(${piece.length} chars, ${pcm.size} samples)",
                )
                val floats = pcm16ToFloat(pcm)
                if (floats.isNotEmpty()) onChunk(floats)
            } catch (t: Throwable) {
                Log.e(TAG, "piper-plus synthesize failed for sentence", t)
            }
        }
    }

    fun release() = synchronized(lock) { releaseLocked() }

    private fun releaseLocked() {
        try {
            engine?.close()
        } catch (_: Throwable) {
        }
        engine = null
        initializedForModelId = null
    }

    companion object {
        private const val TAG = "PiperPlusTts"

        const val DEFAULT_SPEAKER_ID = 0

        private const val PCM16_SCALE = 1f / 32768f

        private fun pcm16ToFloat(pcm: ShortArray): FloatArray {
            val out = FloatArray(pcm.size)
            for (i in pcm.indices) out[i] = pcm[i] * PCM16_SCALE
            return out
        }

        /** Split on sentence end, and on long-clause commas, for earlier first audio. */
        private val SENTENCE_SPLIT = Regex("""(?<=[.!?;])\s+|(?<=,)\s+(?=.{30,})""")

        private val SENTENCE_END = Regex("""[.!?;]["')\]]*$""")

        fun splitIntoSentences(text: String): List<String> {
            val parts = text.split(SENTENCE_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size <= 1) return listOf(text.trim()).filter { it.isNotEmpty() }
            return parts
        }

        fun isCompleteSentence(piece: String): Boolean =
            SENTENCE_END.containsMatchIn(piece.trimEnd())
    }
}

/**
 * Turns an accumulating LLM string into discrete speakable sentences.
 * Emits each completed sentence once; [finish] flushes any leftover tail.
 */
class SentenceEmitBuffer {
    private var emittedCount = 0

    fun onPartial(accumulated: String): List<String> {
        val parts = PiperPlusTts.splitIntoSentences(accumulated)
        if (parts.isEmpty()) return emptyList()
        val complete = if (PiperPlusTts.isCompleteSentence(parts.last())) {
            parts
        } else {
            parts.dropLast(1)
        }
        if (emittedCount >= complete.size) return emptyList()
        val out = complete.subList(emittedCount, complete.size)
        emittedCount = complete.size
        return out
    }

    fun finish(accumulated: String): List<String> {
        val parts = PiperPlusTts.splitIntoSentences(accumulated)
        if (emittedCount >= parts.size) return emptyList()
        val out = parts.subList(emittedCount, parts.size)
        emittedCount = parts.size
        return out
    }
}
