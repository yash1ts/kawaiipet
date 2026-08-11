package com.kawaiipet.app.llm

/**
 * Turns cumulative sanitized LLM text into speakable chunks as soon as possible
 * so TTS can start before the model finishes a full sentence.
 */
class SpokenSentenceStreamer {

    private var emitted = ""
    private var emittedCount = 0
    private var lastPieceKey = ""

    val hasEmitted: Boolean get() = emittedCount > 0

    fun consume(sanitizedCumulative: String, @Suppress("UNUSED_PARAMETER") userText: String = ""): List<String> {
        val text = sanitizedCumulative.trim()
        if (text.isEmpty()) return emptyList()
        if (emittedCount >= LlmPromptDefaults.MAX_REPLY_SENTENCES) return emptyList()

        val pending = pendingRemainder(text)
        if (pending.isEmpty()) return emptyList()

        val out = ArrayList<String>(4)
        var offset = 0
        while (offset < pending.length) {
            if (emittedCount >= LlmPromptDefaults.MAX_REPLY_SENTENCES) break
            val remaining = pending.substring(offset)
            val end = indexOfSentenceEnd(remaining)
            if (end < 0) {
                if (emittedCount == 0) {
                    val early = earlyFirstChunk(remaining) ?: break
                    appendEmitted(early)
                    out += early
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
            if (sentence.isNotEmpty() && acceptPiece(sentence)) {
                appendEmitted(sentence)
                out += sentence
            } else if (sentence.isNotEmpty()) {
                if (sentence.any { it.isLetterOrDigit() }) {
                    emitted = listOf(emitted.trim(), sentence)
                        .filter { it.isNotEmpty() }
                        .joinToString(" ")
                } else {
                    emitted = (emitted.trimEnd() + sentence.trim()).trim()
                }
            }
        }
        return out
    }

    fun flush(sanitizedFinal: String, @Suppress("UNUSED_PARAMETER") userText: String = ""): List<String> {
        if (emittedCount >= LlmPromptDefaults.MAX_REPLY_SENTENCES) return emptyList()
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
        if (breakAt < FIRST_STREAM_MIN) {
            if (pending.length < FIRST_STREAM_FORCE) return null
            val forced = pending.take(FIRST_STREAM_TARGET).trim()
            return forced.takeIf { acceptPiece(it) }
        }
        val piece = pending.take(breakAt).trim()
        if (piece.length < FIRST_STREAM_MIN) return null
        return piece.takeIf { acceptPiece(it) }
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
