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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kawaiipet.app.assets.AssetDownloadState
import com.kawaiipet.app.ui.navigation.AppNavigation
import com.kawaiipet.app.ui.screens.DownloadingAssetsScreen
import com.kawaiipet.app.ui.theme.KawaiiPetTheme
import com.kawaiipet.app.util.PetLauncher
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val startPetRequestViewModel: StartPetRequestViewModel by viewModels()
    private var askedBatteryExemption = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleStartPetIntent(intent)
        enableEdgeToEdge()
        setContent {
            KawaiiPetTheme {
                val assetsViewModel: AssetsBootstrapViewModel = hiltViewModel()
                val assetsState by assetsViewModel.state.collectAsStateWithLifecycle()
                var batteryPromptDone by remember { mutableStateOf(askedBatteryExemption) }

                LaunchedEffect(Unit) {
                    assetsViewModel.ensureAssets()
                }

                // Don't interrupt first-run download with the battery dialog.
                LaunchedEffect(assetsState) {
                    if (assetsState is AssetDownloadState.Ready && !batteryPromptDone) {
                        batteryPromptDone = true
                        askedBatteryExemption = true
                        requestBatteryOptimizationExemptionIfNeeded()
                    }
                }

                when (val s = assetsState) {
                    AssetDownloadState.Ready -> AppNavigation()
                    else -> DownloadingAssetsScreen(
                        state = s,
                        onRetry = { assetsViewModel.retry() },
                    )
                }
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
