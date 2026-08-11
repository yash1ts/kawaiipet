package com.kawaiipet.app.llm

import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.NoRepeatNgramConfig
import com.google.ai.edge.litertlm.RepetitionPenaltyConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.kawaiipet.app.pet.SessionConfigStore
import com.kawaiipet.app.util.PreferenceManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * SmolLM2-360M via LiteRT-LM for multi-turn pet chat:
 * - Sticky conversation + KV cache (multi-turn TTFT win)
 * - Non-thinking instruct model (no CoT / think tags)
 * - Streams sanitized tokens to UI/TTS as they arrive
 * - Per-turn repetition / n-gram penalties for short pet replies
 */
@Singleton
class SmolLmLlmService @Inject constructor(
    private val smolLm: SmolLmAvailability,
    private val prefs: PreferenceManager,
    private val sessionConfigStore: SessionConfigStore,
) : LlmService {

    private val sessionMutex = Mutex()

    /** Reused while the system prompt matches. */
    private var stickyConversation: Conversation? = null
    private var stickySystemPrompt: String? = null
    /** Turns appended to sticky since last rebuild from ShortTermMemory. */
    private var stickyTurnCount: Int = 0
    /** Rough token estimate for sticky contents (system + history + replies). */
    private var stickyTokenEstimate: Int = 0

    override suspend fun warmUp() {
        smolLm.warmUp()
        // Prefill sticky session so turn 1 skips conversation create cost.
        sessionMutex.withLock {
            ensureStickyLocked(
                engine = smolLm.ensureReady(),
                systemPrompt = currentSystemPrompt(),
                priorMessages = emptyList(),
            )
        }
    }

    override suspend fun resetSession() {
        sessionMutex.withLock {
            closeStickyLocked("resetSession")
        }
    }

    override suspend fun chat(
        messages: List<ChatMessage>,
        memoryParagraph: String,
        onPartial: suspend (String) -> Unit,
    ): String = withContext(Dispatchers.Default) {
        val engine = smolLm.ensureReady()
        val latestUser = messages.lastOrNull { it.role == Role.USER }?.text?.trim().orEmpty()
            .take(LlmPromptDefaults.MAX_CHARS_PER_TURN)
        val systemPrompt = currentSystemPrompt()

        val prior = messages.dropLast(1)
        val promptUser = if (LlmPromptDefaults.PURE_SMOLLM_DEBUG) {
            latestUser
        } else {
            LlmPromptDefaults.attachMemoryToUserTurn(latestUser, memoryParagraph)
        }

        Log.d(
            TAG,
            "chat model=SmolLM2-360M systemLen=${systemPrompt.length} " +
                "memoryLen=${memoryParagraph.length} prior=${prior.size} " +
                "latestLen=${latestUser.length} " +
                "promptUser=${promptUser.take(160).toOneLineLog()}",
        )

        sessionMutex.withLock {
            closeStickyIfPromptChangedLocked(systemPrompt)
            var text = generateOnceLocked(
                engine = engine,
                systemPrompt = systemPrompt,
                priorMessages = prior,
                promptUser = promptUser,
                reuseSticky = true,
                onPartial = onPartial,
            )
            // Only retry a truly empty generation — trust the model otherwise.
            if (text.isBlank()) {
                Log.w(TAG, "Empty reply — one fresh retry")
                closeStickyLocked("empty_reply_retry")
                text = generateOnceLocked(
                    engine = engine,
                    systemPrompt = systemPrompt,
                    priorMessages = prior,
                    promptUser = promptUser,
                    reuseSticky = false,
                    onPartial = onPartial,
                )
            }
            text
        }
    }

    private suspend fun generateOnceLocked(
        engine: com.google.ai.edge.litertlm.Engine,
        systemPrompt: String,
        priorMessages: List<ChatMessage>,
        promptUser: String,
        reuseSticky: Boolean,
        onPartial: suspend (String) -> Unit,
    ): String {
        val conversation = if (reuseSticky) {
            ensureStickyLocked(engine, systemPrompt, priorMessages)
        } else {
            createConversation(
                engine = engine,
                systemPrompt = systemPrompt,
                priorMessages = priorMessages,
            )
        }
        val startedAt = SystemClock.elapsedRealtime()
        var keepSticky = reuseSticky
        return try {
            var rawAll = ""
            var contentFromMessage = ""
            var firstSpokenLogged = false
            var lastSpoken = ""

            conversation.sendMessageAsync(
                promptUser,
                repetitionPenaltyConfig = petRepetitionPenaltyConfig(),
                noRepeatNgramConfig = petNoRepeatNgramConfig(),
            ).collect { message ->
                val contentPiece = primaryContentText(message)
                contentFromMessage = mergeStreamText(contentFromMessage, contentPiece)
                rawAll = mergeStreamText(rawAll, contentPiece)

                val spoken = spokenAnswerForStream(
                    contentOnly = contentFromMessage,
                    finalized = false,
                ) ?: return@collect

                if (spoken.isNotEmpty() && spoken != lastSpoken) {
                    lastSpoken = spoken
                    if (!firstSpokenLogged) {
                        firstSpokenLogged = true
                        Log.i(
                            TAG,
                            "first spoken text after ${SystemClock.elapsedRealtime() - startedAt}ms " +
                                "spoken=${spoken.take(80).toOneLineLog()} reuseSticky=$reuseSticky",
                        )
                    }
                    onPartial(spoken)
                }
            }

            val spokenFinal = spokenAnswerForStream(
                contentOnly = contentFromMessage.ifBlank { rawAll },
                finalized = true,
            ).orEmpty().trim()

            if (spokenFinal.isBlank()) {
                keepSticky = false
            }

            Log.i(
                TAG,
                "llm done sticky=$keepSticky " +
                    "contentLen=${contentFromMessage.length} rawLen=${rawAll.length} " +
                    "spokenLen=${spokenFinal.length} " +
                    "raw=${rawAll.take(220).toOneLineLog()} " +
                    "spoken=${spokenFinal.take(120).toOneLineLog()}",
            )
            logBenchmark(startedAt, spokenFinal.length, rawAll.length)
            if (keepSticky && stickyConversation === conversation) {
                stickyTurnCount += 1
                stickyTokenEstimate += estimateTokens(promptUser) + estimateTokens(spokenFinal)
            }
            spokenFinal
        } catch (e: CancellationException) {
            keepSticky = false
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "generateOnce failed", t)
            keepSticky = false
            ""
        } finally {
            if (!keepSticky) {
                if (stickyConversation === conversation) {
                    closeStickyLocked("ephemeral_or_bad")
                } else {
                    runCatching { conversation.close() }
                }
            }
        }
    }

    private fun estimateTokens(text: String): Int =
        // Rough 4 chars/token heuristic for budget accounting.
        (text.length / 4).coerceAtLeast(1)

    private fun ensureStickyLocked(
        engine: com.google.ai.edge.litertlm.Engine,
        systemPrompt: String,
        priorMessages: List<ChatMessage>,
        forceRebuild: Boolean = false,
    ): Conversation {
        val budget = (KV_TOKEN_BUDGET * STICKY_BUDGET_FRACTION).toInt()
        val needsRebuild = forceRebuild ||
            stickyConversation == null ||
            stickySystemPrompt != systemPrompt ||
            stickyTurnCount >= LlmPromptDefaults.MAX_SHORT_TERM_MESSAGES ||
            stickyTokenEstimate >= budget

        stickyConversation?.let { existing ->
            if (!needsRebuild) {
                Log.d(
                    TAG,
                    "Reusing sticky conversation turns=$stickyTurnCount tokens~$stickyTokenEstimate",
                )
                return existing
            }
        }
        closeStickyLocked(if (forceRebuild) "force_rebuild" else "recreate_or_overflow")
        // Rebuild from ShortTermMemory (priorMessages) — source of truth for history.
        val created = createConversation(
            engine = engine,
            systemPrompt = systemPrompt,
            priorMessages = priorMessages,
        )
        stickyConversation = created
        stickySystemPrompt = systemPrompt
        stickyTurnCount = 0
        stickyTokenEstimate = estimateTokens(systemPrompt) +
            priorMessages.sumOf { estimateTokens(it.text) }
        Log.i(
            TAG,
            "Sticky conversation ready history=${priorMessages.size} " +
                "tokens~$stickyTokenEstimate",
        )
        return created
    }

    private fun closeStickyIfPromptChangedLocked(systemPrompt: String) {
        if (stickyConversation != null && stickySystemPrompt != systemPrompt) {
            closeStickyLocked("system_prompt_changed")
        }
    }

    private fun closeStickyLocked(reason: String) {
        val c = stickyConversation ?: return
        stickyConversation = null
        stickySystemPrompt = null
        stickyTurnCount = 0
        stickyTokenEstimate = 0
        runCatching { c.close() }
        Log.d(TAG, "Closed sticky ($reason)")
    }

    /** Sanitize and forward streamed text; trust the model content. */
    private fun spokenAnswerForStream(
        contentOnly: String,
        finalized: Boolean,
    ): String? {
        val spoken = LlmPromptDefaults.sanitizeModelSpeech(contentOnly).trim()
        if (spoken.isEmpty()) return if (finalized) "" else null
        if (!finalized && spoken.length < 2) return null
        return spoken
    }

    private fun createConversation(
        engine: com.google.ai.edge.litertlm.Engine,
        systemPrompt: String,
        priorMessages: List<ChatMessage>,
    ): Conversation {
        val history = ArrayList<Message>(priorMessages.size)
        for (msg in priorMessages) {
            val text = LlmPromptDefaults.sanitizeModelSpeech(msg.text)
                .trim()
                .take(LlmPromptDefaults.MAX_CHARS_PER_TURN)
            if (text.isEmpty()) continue
            when (msg.role) {
                Role.USER -> history += Message.user(text)
                Role.ASSISTANT -> history += Message.model(text)
            }
        }
        val sampler = SamplerConfig(
            topK = LlmPromptDefaults.SAMPLER_TOP_K,
            topP = LlmPromptDefaults.SAMPLER_TOP_P,
            temperature = LlmPromptDefaults.SAMPLER_TEMPERATURE,
        )
        val maxOut = LlmPromptDefaults.MAX_OUTPUT_TOKENS
        val config = ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            initialMessages = history,
            samplerConfig = sampler,
            prefillPrefaceOnInit = true,
            maxOutputToken = maxOut,
        )
        Log.i(
            TAG,
            "Created conversation history=${history.size} " +
                "maxOut=$maxOut temp=${sampler.temperature} topP=${sampler.topP} topK=${sampler.topK}",
        )
        return engine.createConversation(config)
    }

    private fun petRepetitionPenaltyConfig(): RepetitionPenaltyConfig =
        RepetitionPenaltyConfig(
            repetitionPenalty = LlmPromptDefaults.REPETITION_PENALTY,
            presencePenalty = LlmPromptDefaults.PRESENCE_PENALTY,
            frequencyPenalty = LlmPromptDefaults.FREQUENCY_PENALTY,
            windowSize = LlmPromptDefaults.PENALTY_WINDOW_SIZE,
        )

    private fun petNoRepeatNgramConfig(): NoRepeatNgramConfig =
        NoRepeatNgramConfig(
            noRepeatNgramSize = LlmPromptDefaults.NO_REPEAT_NGRAM_SIZE,
            windowSize = LlmPromptDefaults.NO_REPEAT_NGRAM_WINDOW,
        )

    private fun primaryContentText(message: Message): String {
        val fromContents = message.contents.contents
            .mapNotNull { (it as? Content.Text)?.text }
            .joinToString("")
        if (fromContents.isNotEmpty()) return fromContents
        return message.toString()
    }

    private fun mergeStreamText(previous: String, incoming: String): String {
        if (incoming.isEmpty()) return previous
        if (previous.isEmpty()) return incoming
        if (incoming.startsWith(previous)) return incoming
        if (previous.endsWith(incoming)) return previous
        return previous + incoming
    }

    private suspend fun currentSystemPrompt(): String {
        if (LlmPromptDefaults.PURE_SMOLLM_DEBUG) {
            return "You are a helpful assistant."
        }
        // Snapshot avoids DataStore.flow.first() on the chat hot path.
        val cfg = sessionConfigStore.snapshot()
        return LlmPromptDefaults.buildSystemPrompt(
            petName = cfg.petName.ifBlank { prefs.getPetName() },
            personality = cfg.personality.ifBlank { prefs.getPersonalityPrompt() },
        )
    }

    private fun logBenchmark(startedAtMs: Long, answerChars: Int, rawChars: Int) {
        val wallMs = SystemClock.elapsedRealtime() - startedAtMs
        Log.i(TAG, "llm timing wall=${wallMs}ms answerChars=$answerChars rawChars=$rawChars")
    }

    private fun String.toOneLineLog(): String =
        replace('\n', ' ').replace('\r', ' ').take(240)

    companion object {
        private const val TAG = "SmolLmLlmService"
        /** Must match [SmolLmAvailability] engine maxNumTokens. */
        private const val KV_TOKEN_BUDGET = 2048
        /** Rebuild sticky before filling the KV budget. */
        private const val STICKY_BUDGET_FRACTION = 0.65f
    }
}
