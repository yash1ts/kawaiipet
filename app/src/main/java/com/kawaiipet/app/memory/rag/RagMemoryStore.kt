package com.kawaiipet.app.memory.rag

import android.content.Context
import android.util.Log
import com.google.ai.edge.localagents.rag.memory.DefaultSemanticTextMemory
import com.google.ai.edge.localagents.rag.memory.SqliteVectorStore
import com.google.ai.edge.localagents.rag.retrieval.RetrievalConfig
import com.google.ai.edge.localagents.rag.retrieval.RetrievalRequest
import com.kawaiipet.app.assets.RequiredAssets
import com.kawaiipet.app.audio.ModelManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Persistent long-term memory via Google AI Edge RAG [SqliteVectorStore] + MiniLM embeddings.
 * All public suspend APIs hop to [Dispatchers.IO]; do not call blocking work on Main.
 *
 * UI catalog is a sidecar JSON file (not a second SQLite connection into the JNI-owned DB).
 */
@Singleton
class RagMemoryStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val modelManager: ModelManager,
) {
    private val mutex = Mutex()
    private val ready = AtomicBoolean(false)

    private var embedder: MiniLmEmbedder? = null
    private var vectorStore: SqliteVectorStore? = null
    private var semanticMemory: DefaultSemanticTextMemory? = null

    private val dbFile: File
        get() = File(context.filesDir, DB_FILE_NAME)

    private val catalogFile: File
        get() = File(context.filesDir, CATALOG_FILE_NAME)

    private val _chunks = MutableStateFlow<List<String>>(emptyList())
    val chunks: StateFlow<List<String>> = _chunks.asStateFlow()

    val isReady: Boolean get() = ready.get()

    suspend fun ensureReady(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (ready.get()) return@withLock true
            val dir = modelManager.getModelDir(RequiredAssets.EMBEDDER_MODEL_ID)
            val onnx = File(dir, RequiredAssets.EMBEDDER_ONNX_FILE)
            val vocab = File(dir, RequiredAssets.EMBEDDER_VOCAB_FILE)
            if (!onnx.isFile || !vocab.isFile || onnx.length() <= 0L || vocab.length() <= 0L) {
                Log.w(TAG, "MiniLM assets not ready yet")
                return@withLock false
            }
            runCatching {
                // Construct the vector store first — it needs protobuf-javalite at runtime.
                // Creating MiniLM only after that avoids reloading the ONNX session on store failures.
                val store = SqliteVectorStore(MiniLmBertTokenizer.EMBEDDING_DIM, dbFile.absolutePath)
                val emb = MiniLmEmbedder(onnx, vocab)
                embedder = emb
                vectorStore = store
                semanticMemory = DefaultSemanticTextMemory(store, emb)
                loadCatalogLocked()
                ready.set(true)
                Log.i(TAG, "RAG memory store ready at ${dbFile.absolutePath} (${_chunks.value.size} chunks)")
                true
            }.onFailure {
                Log.e(TAG, "Failed to init RAG memory store", it)
                closeLocked()
            }.getOrDefault(false)
        }
    }

    suspend fun index(text: String): Boolean = withContext(Dispatchers.IO) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return@withContext false
        if (!ensureReady()) return@withContext false
        mutex.withLock {
            val memory = semanticMemory ?: return@withLock false
            // Skip exact duplicate of the latest chunk to reduce noise.
            if (_chunks.value.lastOrNull() == cleaned) {
                Log.d(TAG, "Index skipped (duplicate of latest)")
                return@withLock true
            }
            runCatching {
                val ok = memory.recordMemoryItem(cleaned).await()
                if (ok) {
                    appendCatalogLocked(cleaned)
                    Log.d(TAG, "Indexed memory chunk (${cleaned.length} chars)")
                }
                ok
            }.onFailure {
                Log.w(TAG, "Index failed", it)
            }.getOrDefault(false)
        }
    }

    suspend fun retrieve(query: String, topK: Int = DEFAULT_TOP_K): List<String> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            if (!ensureReady()) return@withContext emptyList()
            mutex.withLock {
                val memory = semanticMemory ?: return@withLock emptyList()
                runCatching {
                    val request = RetrievalRequest.create(
                        q,
                        RetrievalConfig.create(
                            topK,
                            MIN_SIMILARITY,
                            RetrievalConfig.TaskType.RETRIEVAL_QUERY,
                        ),
                    )
                    val response = memory.retrieveResults(request).await()
                    response.entities.mapNotNull { entity ->
                        entity.data?.trim()?.takeIf { it.isNotEmpty() }
                    }.distinct().take(topK)
                }.onFailure {
                    Log.w(TAG, "Retrieve failed", it)
                }.getOrDefault(emptyList())
            }
        }

    suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                vectorStore?.sqlQuery("DELETE FROM $TABLE_NAME")
            }.onFailure { Log.w(TAG, "Clear sqlQuery failed", it) }

            // If the native store was never opened, still wipe the on-disk DB + catalog.
            if (vectorStore == null) {
                runCatching {
                    if (dbFile.exists()) {
                        dbFile.delete()
                        File(dbFile.path + "-wal").delete()
                        File(dbFile.path + "-shm").delete()
                        File(dbFile.path + "-journal").delete()
                    }
                }.onFailure { Log.w(TAG, "Clear db file failed", it) }
            }

            runCatching {
                if (catalogFile.exists()) catalogFile.delete()
            }.onFailure { Log.w(TAG, "Clear catalog failed", it) }
            _chunks.value = emptyList()
            Log.i(TAG, "Cleared RAG memory store")
        }
    }

    suspend fun refreshCatalog(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock { loadCatalogLocked() }
    }

    private fun appendCatalogLocked(text: String) {
        val next = (_chunks.value + text).takeLast(MAX_CATALOG_CHUNKS)
        _chunks.value = next
        persistCatalogLocked(next)
    }

    private fun loadCatalogLocked() {
        if (!catalogFile.isFile) {
            _chunks.value = emptyList()
            return
        }
        runCatching {
            val raw = catalogFile.readText()
            if (raw.isBlank()) {
                _chunks.value = emptyList()
                return
            }
            val arr = JSONArray(raw)
            val list = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.optString(i, "").trim()
                if (item.isNotEmpty()) list.add(item)
            }
            _chunks.value = list.takeLast(MAX_CATALOG_CHUNKS)
        }.onFailure {
            Log.w(TAG, "Catalog load failed", it)
            _chunks.value = emptyList()
        }
    }

    private fun persistCatalogLocked(chunks: List<String>) {
        runCatching {
            val arr = JSONArray()
            chunks.forEach { arr.put(it) }
            catalogFile.writeText(arr.toString())
        }.onFailure {
            Log.w(TAG, "Catalog persist failed", it)
        }
    }

    private fun closeLocked() {
        ready.set(false)
        runCatching { embedder?.close() }
        embedder = null
        semanticMemory = null
        vectorStore = null
    }

    companion object {
        private const val TAG = "RagMemoryStore"
        private const val DB_FILE_NAME = "memory_vectors.db"
        private const val CATALOG_FILE_NAME = "memory_chunks.json"
        private const val TABLE_NAME = "rag_vector_store"
        private const val DEFAULT_TOP_K = 3
        private const val MIN_SIMILARITY = 0.28f
        private const val MAX_CATALOG_CHUNKS = 200
    }
}
