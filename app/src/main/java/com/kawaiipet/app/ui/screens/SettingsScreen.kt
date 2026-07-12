package com.kawaiipet.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.kawaiipet.app.R
import com.kawaiipet.app.llm.GeminiNanoAvailability
import com.kawaiipet.app.llm.NanoAiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val nanoAvailability: GeminiNanoAvailability,
) : ViewModel() {

    val nanoState = nanoAvailability.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NanoAiState.Checking)

    init {
        refreshNanoStatus()
    }

    fun refreshNanoStatus() {
        viewModelScope.launch {
            nanoAvailability.refreshStatus()
        }
    }

    fun downloadNanoModel() {
        viewModelScope.launch {
            runCatching { nanoAvailability.download() }
                .onFailure { nanoAvailability.refreshStatus() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val nanoState by viewModel.nanoState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.settings_on_device_ai_section),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when (val s = nanoState) {
                    NanoAiState.Checking -> stringResource(R.string.nano_status_checking)
                    NanoAiState.Available -> stringResource(R.string.nano_status_ready)
                    NanoAiState.Downloadable -> stringResource(R.string.nano_status_downloadable)
                    NanoAiState.Downloading -> stringResource(R.string.nano_status_downloading)
                    is NanoAiState.DownloadProgress -> stringResource(
                        R.string.nano_status_download_progress,
                        s.bytesDownloaded,
                    )
                    NanoAiState.Unavailable -> stringResource(R.string.nano_status_unavailable)
                    is NanoAiState.Failed -> stringResource(R.string.nano_status_failed, s.message)
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            when (nanoState) {
                NanoAiState.Downloadable -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.downloadNanoModel() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.nano_download_action))
                    }
                }
                NanoAiState.Unavailable, is NanoAiState.Failed -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.refreshNanoStatus() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.nano_refresh_status))
                    }
                }
                else -> Unit
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
