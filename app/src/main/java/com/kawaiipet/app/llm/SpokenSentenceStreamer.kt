package com.kawaiipet.app.llm

/**
 * Turns cumulative sanitized LLM text into speakable chunks as soon as possible
 * so TTS can start before the model finishes.
 */
class SpokenSentenceStreamer {

    private var emitted = ""
    private var emittedCount = 0
    private var spokenCount = 0
    private var lastPieceKey = ""

    /** True if any sentence was handed to TTS. */
    val hasSpoken: Boolean get() = spokenCount > 0

    @Deprecated("Use hasSpoken", ReplaceWith("hasSpoken"))
    val hasEmitted: Boolean get() = hasSpoken

    fun consume(sanitizedCumulative: String, @Suppress("UNUSED_PARAMETER") userText: String = ""): List<String> {
        val text = sanitizedCumulative.trim()
        if (text.isEmpty()) return emptyList()
        if (spokenCount >= LlmPromptDefaults.MAX_REPLY_SENTENCES) return emptyList()

        val pending = pendingRemainder(text)
        if (pending.isEmpty()) return emptyList()

        val out = ArrayList<String>(4)
        var offset = 0
        while (offset < pending.length) {
            if (spokenCount >= LlmPromptDefaults.MAX_REPLY_SENTENCES) break
            val remaining = pending.substring(offset)
            val end = indexOfSentenceEnd(remaining)
            if (end < 0) {
                if (spokenCount == 0 && emittedCount == 0) {
                    val early = earlyFirstChunk(remaining) ?: break
                    appendEmitted(early)
                    if (acceptPiece(early)) {
                        spokenCount++
                        out += early
                    }
                    offset += early.length
                    while (offset < pending.length && pending[offset].isWhitespace()) offset++
                    continue
                }
                break
            }
            val rawPiece = remaining.substring(0, end + 1)
            offset += end + 1
            while (offset < pending.length && pending[offset].isWhitespace()) offset++
            val sentence = rawPiece.trim()
            if (sentence.isEmpty()) continue
            appendEmitted(sentence)
            if (!acceptPiece(sentence)) continue
            spokenCount++
            out += sentence
        }
        return out
    }

    fun flush(sanitizedFinal: String, @Suppress("UNUSED_PARAMETER") userText: String = ""): List<String> {
        if (spokenCount >= LlmPromptDefaults.MAX_REPLY_SENTENCES) return emptyList()
        val text = sanitizedFinal.trim()
        val rem = pendingRemainder(text).trim()
        if (rem.isEmpty()) return emptyList()
        if (rem.length < 8 && !rem.any { it == '.' || it == '!' || it == '?' }) {
            emitted = text
            return emptyList()
        }
        if (!acceptPiece(rem)) {
            emitted = text
            return emptyList()
        }
        appendEmitted(rem)
        spokenCount++
        return listOf(rem)
    }

    private fun acceptPiece(piece: String): Boolean {
        val p = piece.trim()
        if (p.isEmpty() || !p.any { it.isLetterOrDigit() }) return false
        val key = p.lowercase().replace(Regex("[^a-z0-9\\s]"), " ").replace(Regex("\\s+"), " ").trim()
        if (key.isNotEmpty() && key == lastPieceKey) return false
        lastPieceKey = key
        return true
    }

    private fun earlyFirstChunk(pending: String): String? {
        if (pending.length < FIRST_STREAM_MIN) return null
        val window = pending.take(FIRST_STREAM_TARGET)
        val breakAt = window.indexOfLast { it.isWhitespace() }
        val piece = if (breakAt < FIRST_STREAM_MIN) {
            if (pending.length < FIRST_STREAM_FORCE) return null
            pending.take(FIRST_STREAM_TARGET).trim()
        } else {
            pending.take(breakAt).trim().takeIf { it.length >= FIRST_STREAM_MIN } ?: return null
        }
        return piece
    }

    private fun pendingRemainder(text: String): String {
        if (emitted.isEmpty()) return text
        if (text.startsWith(emitted)) return text.substring(emitted.length).trimStart()
        val lcp = longestCommonPrefix(emitted, text)
        emitted = text.take(lcp)
        return text.substring(lcp).trimStart()
    }

    private fun appendEmitted(piece: String) {
        emitted = listOf(emitted.trim(), piece.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        emittedCount++
    }

    companion object {
        private const val FIRST_STREAM_MIN = 10
        private const val FIRST_STREAM_TARGET = 24
        private const val FIRST_STREAM_FORCE = 36

        private fun longestCommonPrefix(a: String, b: String): Int {
            val n = minOf(a.length, b.length)
            var i = 0
            while (i < n && a[i] == b[i]) i++
            return i
        }

        private fun indexOfSentenceEnd(text: String): Int {
            for (i in text.indices) {
                val c = text[i]
                if (c != '.' && c != '!' && c != '?') continue
                val next = text.getOrNull(i + 1)
                if (next == null || next.isWhitespace()) return i
            }
            return -1
        }
    }
}
