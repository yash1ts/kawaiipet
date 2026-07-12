package com.kawaiipet.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.kawaiipet.app.llm.AiForegroundGate
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Fallback invisible host for AICore when [AiTriggerActivity] is not already up.
 */
@AndroidEntryPoint
class AiForegroundGateActivity : ComponentActivity() {

    @Inject lateinit var gate: AiForegroundGate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SoftForegroundWindow.apply(this)
    }

    override fun onResume() {
        super.onResume()
        SoftForegroundWindow.apply(this)
        gate.onActivityResumed(this)
    }

    override fun onDestroy() {
        gate.onActivityDestroyed(this)
        super.onDestroy()
    }
}
