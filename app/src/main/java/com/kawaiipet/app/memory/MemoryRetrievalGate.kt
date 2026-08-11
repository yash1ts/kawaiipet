package com.kawaiipet.app.memory

import com.kawaiipet.app.llm.LlmPromptDefaults
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Decides whether to fetch long-term memory for a user turn.
 *
 * - Skip greetings / politeness
 * - Always fetch for high-intent / personal recall
 * - Otherwise: at most once every N turns (N in 5..8) with a ~20% roll
 */
@Singleton
class MemoryRetrievalGate @Inject constructor() {

    enum class Decision { Skip, Always, Maybe }

    private val lock = Any()
    private var turnsSinceLastAttempt = 0
    private var cooldownN: Int = Random.nextInt(COOLDOWN_MIN, COOLDOWN_MAX + 1)

    fun classify(userText: String): Decision {
        val t = userText.trim()
        if (t.isEmpty()) return Decision.Skip
        val lower = t.lowercase()
        if (isGreetingOrPolite(lower)) return Decision.Skip
        if (isHighIntent(lower, t)) return Decision.Always
        return Decision.Maybe
    }

    /**
     * @return true when LTM retrieval should run for this turn.
     */
    fun shouldRetrieve(userText: String): Boolean {
        return when (classify(userText)) {
            Decision.Skip -> {
                synchronized(lock) { turnsSinceLastAttempt++ }
                false
            }
            Decision.Always -> {
                synchronized(lock) { markAttemptLocked() }
                true
            }
            Decision.Maybe -> synchronized(lock) {
                turnsSinceLastAttempt++
                if (turnsSinceLastAttempt < cooldownN) return@synchronized false
                // Cooldown elapsed: this is a retrieve attempt (hit or miss).
                markAttemptLocked()
                Random.nextFloat() < MAYBE_ROLL
            }
        }
    }

    private fun markAttemptLocked() {
        turnsSinceLastAttempt = 0
        cooldownN = Random.nextInt(COOLDOWN_MIN, COOLDOWN_MAX + 1)
    }

    private fun isGreetingOrPolite(lower: String): Boolean {
        val stripped = lower.trim().trimEnd('.', '!', '?')
        if (GREETING_EXACT.any { it == stripped }) return true
        if (GREETING_PREFIX.any { stripped == it || stripped.startsWith("$it ") }) return true
        // Very short reaction / expression lines.
        if (stripped.length <= 12 && REACTION_EXACT.contains(stripped)) return true
        return false
    }

    private fun isHighIntent(lower: String, original: String): Boolean {
        if (HIGH_INTENT_PHRASES.any { lower.contains(it) }) return true
        if (LlmPromptDefaults.looksLikeMemorableUserTurn(original)) return true
        return false
    }

    companion object {
        private const val COOLDOWN_MIN = 5
        private const val COOLDOWN_MAX = 8
        private const val MAYBE_ROLL = 0.20f

        private val GREETING_EXACT = setOf(
            "hi", "hello", "hey", "yo", "sup", "hiya", "howdy",
            "good morning", "good afternoon", "good evening", "good night",
            "thanks", "thank you", "ty", "thx",
            "ok", "okay", "k", "kk", "cool", "nice",
            "bye", "goodbye", "see you", "see ya", "later",
            "yes", "yeah", "yep", "yup", "no", "nope", "nah",
        )

        private val GREETING_PREFIX = listOf(
            "hi", "hello", "hey", "good morning", "good evening", "good night",
            "thanks", "thank you",
        )

        private val REACTION_EXACT = setOf(
            "lol", "haha", "hehe", "wow", "omg", "hmm", "huh", "yay", "aww", "ugh",
        )

        private val HIGH_INTENT_PHRASES = listOf(
            "remember", "forgot", "do you know", "what's my", "whats my", "what is my",
            "my name", "i like", "i love", "i hate", "i prefer", "i live", "i work",
            "i study", "my job", "my birthday", "allerg", "favorite", "favourite",
            "call me", "i'm called", "i am called", "remind me", "you know that",
            "don't forget", "do not forget",
        )
    }
}
