package com.kawaiipet.app.memory.rag

import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.google.ai.edge.localagents.rag.models.EmbedData
import com.google.ai.edge.localagents.rag.models.Embedder
import com.google.ai.edge.localagents.rag.models.EmbeddingRequest
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.io.File
import java.nio.LongBuffer
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * AI Edge RAG [Embedder] backed by on-device all-MiniLM-L6-v2 (ONNX).
 */
class MiniLmEmbedder(
    onnxModelFile: File,
    vocabFile: File,
) : Embedder<String>, AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val tokenizer = MiniLmBertTokenizer(vocabFile)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "minilm-embedder").apply { isDaemon = true }
    }
    private val listeningExecutor = MoreExecutors.listeningDecorator(executor)

    private val inputNames: Set<String>
    private val outputName: String

    init {
        require(onnxModelFile.isFile) { "Missing MiniLM onnx: ${onnxModelFile.absolutePath}" }
        require(vocabFile.isFile) { "Missing MiniLM vocab: ${vocabFile.absolutePath}" }
        val opts = OrtSession.SessionOptions().apply {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(4, 8)
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // Prefer XNNPACK — Pixel Darwinn often rejects MiniLM FLOAT32 via NNAPI.
            runCatching { addXnnpack(emptyMap()) }
                .onFailure { Log.d(TAG, "MiniLM XNNPACK unavailable — threaded CPU") }
        }
        session = env.createSession(onnxModelFile.absolutePath, opts)
        inputNames = session.inputNames
        outputName = session.outputNames.first()
        Log.i(TAG, "MiniLM ready inputs=$inputNames output=$outputName")
    }

    override fun getEmbeddings(
        request: EmbeddingRequest<String>,
    ): ListenableFuture<ImmutableList<Float>> {
        return listeningExecutor.submit<ImmutableList<Float>> {
            val text = request.embedData.firstOrNull()?.data.orEmpty()
            val vector = embedText(text)
            ImmutableList.copyOf(vector.toList())
        }
    }

    override fun getBatchEmbeddings(
        request: EmbeddingRequest<String>,
    ): ListenableFuture<ImmutableList<ImmutableList<Float>>> {
        return listeningExecutor.submit<ImmutableList<ImmutableList<Float>>> {
            val builder = ImmutableList.builder<ImmutableList<Float>>()
            for (item in request.embedData) {
                val vector = embedText(item.data.orEmpty())
                builder.add(ImmutableList.copyOf(vector.toList()))
            }
            builder.build()
        }
    }

    fun embedSync(text: String): FloatArray = embedText(text)

    private fun embedText(text: String): FloatArray {
        val encoded = tokenizer.encode(text)
        val shape = longArrayOf(1, MiniLmBertTokenizer.MAX_SEQ_LEN.toLong())
        val feeds = HashMap<String, OnnxTensor>()
        try {
            fun putLong(name: String, data: LongArray) {
                if (name in inputNames) {
                    feeds[name] = OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)
                }
            }
            // Common MiniLM / BERT ONNX input names.
            when {
                "input_ids" in inputNames -> {
                    putLong("input_ids", encoded.inputIds)
                    putLong("attention_mask", encoded.attentionMask)
                    putLong("token_type_ids", encoded.tokenTypeIds)
                }
                else -> {
                    val ordered = inputNames.toList()
                    if (ordered.isNotEmpty()) putLong(ordered[0], encoded.inputIds)
                    if (ordered.size > 1) putLong(ordered[1], encoded.attentionMask)
                    if (ordered.size > 2) putLong(ordered[2], encoded.tokenTypeIds)
                }
            }
            session.run(feeds).use { result ->
                val value = result[0].value
                return meanPoolAndNormalize(value, encoded.attentionMask)
            }
        } finally {
            feeds.values.forEach { it.close() }
        }
    }

    /**
     * Accepts token embeddings `[batch, seq, dim]` / `[seq, dim]` or pooled `[batch, dim]` / `[dim]`.
     */
    private fun meanPoolAndNormalize(value: Any, attentionMask: LongArray): FloatArray {
        when (value) {
            is Array<*> -> {
                val first = value.firstOrNull()
                    ?: return FloatArray(MiniLmBertTokenizer.EMBEDDING_DIM)
                return when (first) {
                    is Array<*> -> {
                        // [batch][seq][dim]
                        @Suppress("UNCHECKED_CAST")
                        val tokens = first as Array<FloatArray>
                        meanPool(tokens, attentionMask)
                    }
                    is FloatArray -> {
                        @Suppress("UNCHECKED_CAST")
                        val rows = value as Array<FloatArray>
                        // [seq][dim] token matrix vs [batch][dim] already-pooled.
                        if (rows.size > 1 && rows.size <= attentionMask.size &&
                            rows.all { it.size == rows[0].size }
                        ) {
                            meanPool(rows, attentionMask)
                        } else {
                            l2Normalize(rows[0].copyOf())
                        }
                    }
                    else -> error("Unexpected ONNX nested type: ${first::class.java}")
                }
            }
            is FloatArray -> return l2Normalize(value.copyOf())
            else -> error("Unexpected ONNX output type: ${value::class.java}")
        }
    }

    private fun meanPool(tokens: Array<FloatArray>, attentionMask: LongArray): FloatArray {
        val dim = tokens.firstOrNull()?.size ?: MiniLmBertTokenizer.EMBEDDING_DIM
        val sum = FloatArray(dim)
        var count = 0f
        val limit = minOf(tokens.size, attentionMask.size)
        for (i in 0 until limit) {
            if (attentionMask[i] == 0L) continue
            val row = tokens[i]
            for (d in 0 until dim) {
                sum[d] += row[d]
            }
            count += 1f
        }
        if (count > 0f) {
            for (d in 0 until dim) sum[d] /= count
        }
        return l2Normalize(sum)
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var ss = 0.0
        for (x in v) ss += (x * x).toDouble()
        val norm = sqrt(ss).toFloat()
        if (norm > 1e-12f) {
            for (i in v.indices) v[i] /= norm
        }
        return v
    }

    override fun close() {
        runCatching { session.close() }
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "MiniLmEmbedder"

        /** Unused helper kept for call sites that build [EmbedData] explicitly. */
        fun documentRequest(text: String): EmbeddingRequest<String> =
            EmbeddingRequest.create(
                listOf(EmbedData.create(text, EmbedData.TaskType.RETRIEVAL_DOCUMENT)),
            )
    }
}
