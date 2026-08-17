package com.kawaiipet.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.kawaiipet.app.llm.AiForegroundGate
import com.kawaiipet.app.overlay.PetOverlayService
import dagger.hilt.android.AndroidEntryPoint
import java.lang.ref.WeakReference
import javax.inject.Inject

/**
 * Nearly invisible trampoline kept for one pet conversation
 * (listen → LLM → speak loop until idle).
 * Reused by [AiForegroundGate] so the session does not open a second activity/flash.
 */
@AndroidEntryPoint
class AiTriggerActivity : ComponentActivity() {

    @Inject lateinit var gate: AiForegroundGate

    private var triggered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SoftForegroundWindow.apply(this)
        current = WeakReference(this)
    }

    override fun onResume() {
        super.onResume()
        SoftForegroundWindow.apply(this)
        gate.onActivityResumed(this)
        if (!triggered) {
            triggered = true
            startService(
                Intent(this, PetOverlayService::class.java).apply {
                    action = PetOverlayService.ACTION_TRIGGER_AI
                },
            )
        }
    }

    override fun onDestroy() {
        gate.onActivityDestroyed(this)
        if (current?.get() === this) {
            current = null
        }
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var current: WeakReference<AiTriggerActivity>? = null

        fun finishIfShowing() {
            current?.get()?.let { SoftForegroundWindow.finishQuietly(it) }
        }

        fun isShowing(): Boolean {
            val activity = current?.get() ?: return false
            return !activity.isFinishing && !activity.isDestroyed
        }
    }
}
