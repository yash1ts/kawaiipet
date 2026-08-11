package com.kawaiipet.app.memory

import android.util.Log
import com.kawaiipet.app.llm.LlmPromptDefaults
import com.kawaiipet.app.memory.rag.RagMemoryStore
import com.kawaiipet.app.util.PreferenceManager
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Long-term pet memory via on-device RAG (MiniLM + SqliteVectorStore).
 *
 * Indexing always runs on a background IO scope after app start — never blocks chat/UI.
 * Retrieval is gated so greetings skip and casual chat only rarely pulls LTM.
 */
@Singleton
class MemoryPipeline @Inject constructor(
    private val ragMemoryStore: RagMemoryStore,
    private val retrievalGate: MemoryRetrievalGate,
    private val prefs: PreferenceManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val migrateOnce = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Memorable turns waiting for MiniLM/assets to become ready. Guarded by [writeMutex]. */
    private val pendingIndex = ArrayDeque<String>()

    val memoryChunks: StateFlow<List<String>> = ragMemoryStore.chunks

    /**
     * Decide whether to pull LTM for [userText] and return a prompt-sized context string.
     * Runs embedding/search on IO.
     */
    suspend fun retrieveForChat(userText: String): String = withContext(Dispatchers.IO) {
        // Gate first — don't take writeMutex / migrate on greetings (chat critical path).
        if (!retrievalGate.shouldRetrieve(userText)) {
            Log.d(TAG, "LTM retrieve skipped by gate")
            return@withContext ""
        }
        // Legacy migration runs via scheduleFlushSession / index — not on chat critical path.
        if (!ragMemoryStore.ensureReady()) {
            Log.d(TAG, "LTM retrieve skipped (store not ready)")
            return@withContext ""
        }
        val chunks = ragMemoryStore.retrieve(userText, topK = RETRIEVE_TOP_K)
        if (chunks.isEmpty()) {
            Log.d(TAG, "LTM retrieve empty")
            return@withContext ""
        }
        // Rewrite first-person user lines into third-person friend facts so the tiny
        // model does not adopt them ("I like cookie" → "I'm not a smart cookie").
        val facts = chunks.mapNotNull { friendFactForPrompt(it) }
        if (facts.isEmpty()) {
            Log.d(TAG, "LTM retrieve had chunks but none usable as facts")
            return@withContext ""
        }
        val joined = facts.joinToString(separator = "; ")
        val clamped = LlmPromptDefaults.clampRetrievedMemory(joined)
        Log.d(TAG, "LTM retrieve ok facts=${facts.size}/${chunks.size} len=${clamped.length}")
        clamped
    }

    /** Prompt-only rewrite; storage keeps the original user utterance. Null = skip. */
    private fun friendFactForPrompt(raw: String): String? {
        val t = raw.trim().trimEnd('.', '!', '?')
        if (t.isEmpty()) return null
        // Never inject questions / capability lines as "memory".
        if (!LlmPromptDefaults.looksLikeMemorableUserTurn(t)) return null
        val lower = t.lowercase()
        return when {
            lower.startsWith("i like ") -> "friend likes ${t.drop(7).trim()}"
            lower.startsWith("i love ") -> "friend loves ${t.drop(7).trim()}"
            lower.startsWith("i hate ") -> "friend dislikes ${t.drop(7).trim()}"
            lower.startsWith("i prefer ") -> "friend prefers ${t.drop(9).trim()}"
            lower.startsWith("my name is ") -> "friend's name is ${t.drop(11).trim()}"
            lower.startsWith("i'm a ") || lower.startsWith("i am a ") ||
                lower.startsWith("i'm an ") || lower.startsWith("i am an ") ->
                "friend is ${t.lowercase().removePrefix("i'm ").removePrefix("i am ").trim()}"
            lower.startsWith("i live ") -> "friend lives ${t.drop(7).trim()}"
            lower.startsWith("i work ") -> "friend works ${t.drop(7).trim()}"
            lower.startsWith("i study ") -> "friend studies ${t.drop(8).trim()}"
            lower.startsWith("i have ") -> "friend has ${t.drop(7).trim()}"
            lower.startsWith("call me ") -> "friend's name is ${t.drop(8).trim()}"
            lower.startsWith("do you know ") -> friendFactForPrompt(t.drop(12).trim())
            else -> "friend: $t"
        }
    }

    /**
     * Fire-and-forget: index a memorable user turn on IO. Safe to call from any thread.
     */
    fun scheduleIndexTurn(userText: String, assistantText: String) {
        scope.launch {
            runCatching { indexTurnNow(userText, assistantText) }
                .onFailure { Log.w(TAG, "Background memory index failed", it) }
        }
    }

    /**
     * Warm RAG store, migrate legacy paragraph, and drain any pending indexes (IO).
     */
    fun scheduleFlushSession() {
        scope.launch {
            runCatching {
                writeMutex.withLock {
                    migrateLegacyLocked()
                    if (ragMemoryStore.ensureReady()) {
                        drainPendingLocked()
                    }
                }
            }.onFailure { Log.w(TAG, "Memory flush/warm failed", it) }
        }
    }

    suspend fun clearMemory() = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            pendingIndex.clear()
            ragMemoryStore.clear()
            prefs.setMemoryParagraph("")
        }
    }

    private suspend fun indexTurnNow(userText: String, assistantText: String) {
        writeMutex.withLock {
            migrateLegacyLocked()

            val user = LlmPromptDefaults.sanitizeParagraph(userText)
                .take(LlmPromptDefaults.MAX_CHARS_PER_TURN)
            if (user.isEmpty()) return
            if (!LlmPromptDefaults.looksLikeMemorableUserTurn(user)) {
                Log.d(TAG, "Index skipped (not memorable)")
                return
            }
            if (LlmPromptDefaults.isCannedFallback(assistantText)) return

            if (!ragMemoryStore.ensureReady()) {
                enqueuePendingLocked(user)
                Log.d(TAG, "Index queued — store not ready (pending=${pendingIndex.size})")
                return
            }
            drainPendingLocked()
            val ok = ragMemoryStore.index(user)
            Log.d(TAG, if (ok) "Indexed turn" else "Index returned false")
        }
    }

    /** Caller must hold [writeMutex]. */
    private suspend fun drainPendingLocked() {
        if (pendingIndex.isEmpty()) return
        if (!ragMemoryStore.isReady && !ragMemoryStore.ensureReady()) return
        while (pendingIndex.isNotEmpty()) {
            val text = pendingIndex.removeFirst()
            val ok = runCatching { ragMemoryStore.index(text) }
                .onFailure { Log.w(TAG, "Pending index failed", it) }
                .getOrDefault(false)
            if (!ok) {
                pendingIndex.addFirst(text)
                return
            }
        }
    }

    /** Caller must hold [writeMutex]. */
    private fun enqueuePendingLocked(text: String) {
        if (pendingIndex.lastOrNull() == text) return
        pendingIndex.addLast(text)
        while (pendingIndex.size > MAX_PENDING) {
            pendingIndex.removeFirst()
        }
    }

    /** Caller must hold [writeMutex]. */
    private suspend fun migrateLegacyLocked() {
        if (!migrateOnce.compareAndSet(false, true)) return
        val legacy = prefs.getMemoryParagraph().trim()
        if (legacy.isEmpty()) return
        if (!ragMemoryStore.ensureReady()) {
            migrateOnce.set(false)
            enqueuePendingLocked(legacy)
            return
        }
        Log.i(TAG, "Migrating legacy memory paragraph into RAG (${legacy.length} chars)")
        val ok = ragMemoryStore.index(legacy)
        if (ok) {
            prefs.setMemoryParagraph("")
        } else {
            migrateOnce.set(false)
            enqueuePendingLocked(legacy)
        }
    }

    companion object {
        private const val TAG = "MemoryPipeline"
        private const val RETRIEVE_TOP_K = 3
        private const val MAX_PENDING = 64
    }
}
