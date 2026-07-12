package com.kawaiipet.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.kawaiipet.app.ui.navigation.AppNavigation
import com.kawaiipet.app.ui.theme.KawaiiPetTheme
import com.kawaiipet.app.util.PetLauncher
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val startPetRequestViewModel: StartPetRequestViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleStartPetIntent(intent)
        requestBatteryOptimizationExemptionIfNeeded()
        enableEdgeToEdge()
        setContent {
            KawaiiPetTheme {
                AppNavigation()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleStartPetIntent(intent)
    }

    /**
     * Unrestricted battery helps keep the floating pet FGS from being throttled/killed
     * while the user is in other apps.
     */
    private fun requestBatteryOptimizationExemptionIfNeeded() {
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            },
        )
    }

    private fun handleStartPetIntent(intent: Intent?) {
        if (intent?.action == PetLauncher.ACTION_START_PET) {
            startPetRequestViewModel.setStartPetRequested(true)
        }
    }
}
