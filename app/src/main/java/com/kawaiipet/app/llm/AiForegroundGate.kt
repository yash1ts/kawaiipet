package com.kawaiipet.app.llm

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import com.kawaiipet.app.ui.AiForegroundGateActivity
import com.kawaiipet.app.ui.AiTriggerActivity
import com.kawaiipet.app.ui.SoftForegroundWindow
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AICore blocks background inference. Prefer an already-resumed trampoline
 * ([AiTriggerActivity]); only launch a fallback gate activity when needed.
 */
@Singleton
class AiForegroundGate @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    private val mutex = Mutex()
    private var resumeSignal: CompletableDeferred<Unit>? = null
    private var activityRef: WeakReference<ComponentActivity>? = null

    fun onActivityResumed(activity: ComponentActivity) {
        activityRef = WeakReference(activity)
        resumeSignal?.complete(Unit)
    }

    fun onActivityDestroyed(activity: ComponentActivity) {
        if (activityRef?.get() === activity) {
            activityRef = null
        }
    }

    private fun resumedHost(): ComponentActivity? {
        val activity = activityRef?.get() ?: return null
        if (activity.isFinishing || activity.isDestroyed) return null
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return null
        return activity
    }

    suspend fun <T> withForeground(block: suspend () -> T): T = mutex.withLock {
        val existing = resumedHost()
        if (existing != null) {
            Log.d(TAG, "Reusing existing foreground host ${existing.javaClass.simpleName}")
            return@withLock block()
        }

        val signal = CompletableDeferred<Unit>()
        resumeSignal = signal
        var launchedFallback: ComponentActivity? = null
        try {
            appContext.startActivity(
                Intent(appContext, AiForegroundGateActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                    )
                },
            )
            withTimeout(FOREGROUND_TIMEOUT_MS) { signal.await() }
            launchedFallback = resumedHost()
            delay(FOREGROUND_SETTLE_MS)
            Log.d(TAG, "AI foreground gate ready")
            block()
        } finally {
            resumeSignal = null
            // Never finish AiTriggerActivity here — the overlay owns its lifetime.
            val toFinish = launchedFallback
            if (toFinish != null && toFinish !is AiTriggerActivity) {
                SoftForegroundWindow.finishQuietly(toFinish)
                if (activityRef?.get() === toFinish) {
                    activityRef = null
                }
            }
        }
    }

    companion object {
        private const val TAG = "AiForegroundGate"
        private const val FOREGROUND_TIMEOUT_MS = 8_000L
        private const val FOREGROUND_SETTLE_MS = 40L
    }
}
