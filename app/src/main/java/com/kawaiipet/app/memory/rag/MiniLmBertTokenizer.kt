package com.kawaiipet.app.memory.rag

import java.io.File
import java.text.Normalizer

/**
 * Minimal WordPiece tokenizer for all-MiniLM-L6-v2 (BERT uncased vocab).
 */
class MiniLmBertTokenizer(
    vocabFile: File,
    private val maxLength: Int = MAX_SEQ_LEN,
) {
    private val tokenToId: Map<String, Int>
    private val unkId: Int
    private val padId: Int

    init {
        val map = LinkedHashMap<String, Int>()
        vocabFile.bufferedReader().useLines { lines ->
            lines.forEachIndexed { index, line ->
                val token = line.trim()
                if (token.isNotEmpty()) map[token] = index
            }
        }
        require(map.isNotEmpty()) { "Empty MiniLM vocab: ${vocabFile.absolutePath}" }
        require(map.containsKey("[CLS]")) { "vocab missing [CLS]" }
        require(map.containsKey("[SEP]")) { "vocab missing [SEP]" }
        tokenToId = map
        unkId = map["[UNK]"] ?: error("vocab missing [UNK]")
        padId = map["[PAD]"] ?: 0
    }

    data class Encoded(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        val tokenTypeIds: LongArray,
    )

    fun encode(text: String): Encoded {
        val tokens = mutableListOf<String>()
        tokens.add("[CLS]")
        for (word in basicTokenize(text)) {
            tokens.addAll(wordPiece(word))
        }
        tokens.add("[SEP]")

        val truncated = if (tokens.size > maxLength) {
            tokens.subList(0, maxLength - 1).toMutableList().also { it.add("[SEP]") }
        } else {
            tokens
        }

        val ids = LongArray(maxLength) { padId.toLong() }
        val mask = LongArray(maxLength)
        val types = LongArray(maxLength)
        for (i in truncated.indices) {
            ids[i] = (tokenToId[truncated[i]] ?: unkId).toLong()
            mask[i] = 1L
        }
        return Encoded(ids, mask, types)
    }

    private fun basicTokenize(text: String): List<String> {
        val normalized = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) {
                out.add(sb.toString())
                sb.clear()
            }
        }
        for (ch in normalized) {
            when {
                ch.isWhitespace() -> flush()
                isPunctuation(ch) -> {
                    flush()
                    out.add(ch.toString())
                }
                else -> sb.append(ch)
            }
        }
        flush()
        return out
    }

    private fun wordPiece(token: String): List<String> {
        if (token.isEmpty()) return emptyList()
        if (tokenToId.containsKey(token)) return listOf(token)

        val chars = token
        val output = ArrayList<String>()
        var start = 0
        while (start < chars.length) {
            var end = chars.length
            var cur: String? = null
            while (start < end) {
                val substr = if (start == 0) {
                    chars.substring(start, end)
                } else {
                    "##" + chars.substring(start, end)
                }
                if (tokenToId.containsKey(substr)) {
                    cur = substr
                    break
                }
                end--
            }
            if (cur == null) {
                return listOf("[UNK]")
            }
            output.add(cur)
            start = end
        }
        return output
    }

    private fun isPunctuation(ch: Char): Boolean {
        if (ch in '!'..'/' || ch in ':'..'@' || ch in '['..'`' || ch in '{'..'~') return true
        val type = Character.getType(ch).toByte()
        return type == Character.CONNECTOR_PUNCTUATION ||
            type == Character.DASH_PUNCTUATION ||
            type == Character.START_PUNCTUATION ||
            type == Character.END_PUNCTUATION ||
            type == Character.INITIAL_QUOTE_PUNCTUATION ||
            type == Character.FINAL_QUOTE_PUNCTUATION ||
            type == Character.OTHER_PUNCTUATION
    }

    companion object {
        const val MAX_SEQ_LEN = 128
        const val EMBEDDING_DIM = 384
    }
}
