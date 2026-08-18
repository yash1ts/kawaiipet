package com.kawaiipet.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleStartPetIntent(intent)
        enableEdgeToEdge()
        setContent {
            KawaiiPetTheme {
                val assetsViewModel: AssetsBootstrapViewModel = hiltViewModel()
                val assetsState by assetsViewModel.state.collectAsStateWithLifecycle()

                LaunchedEffect(Unit) {
                    assetsViewModel.ensureAssets()
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

    private fun handleStartPetIntent(intent: Intent?) {
        if (intent?.action == PetLauncher.ACTION_START_PET) {
            startPetRequestViewModel.setStartPetRequested(true)
        }
    }
}
