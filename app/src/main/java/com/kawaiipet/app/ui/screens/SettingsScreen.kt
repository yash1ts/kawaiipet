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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavController
import com.kawaiipet.app.R
import com.kawaiipet.app.assets.RequiredAssets
import com.kawaiipet.app.audio.DefaultVoiceModels
import com.kawaiipet.app.util.PreferenceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: PreferenceManager,
) : ViewModel() {
    val ttsVolume = prefs.ttsVolume
    val ttsSpeed = prefs.ttsSpeed
    suspend fun setVolume(value: Float) = prefs.setTtsVolume(value)
    suspend fun setSpeed(value: Float) = prefs.setTtsSpeed(value)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val volume by viewModel.ttsVolume.collectAsState(
        initial = PreferenceManager.TTS_VOLUME_DEFAULT,
    )
    val speed by viewModel.ttsSpeed.collectAsState(
        initial = PreferenceManager.TTS_SPEED_DEFAULT,
    )
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.settings_voice_section),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.tts_volume_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = stringResource(R.string.tts_volume_value, (volume * 100).toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.tts_volume_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = volume.coerceIn(
                    PreferenceManager.TTS_VOLUME_MIN,
                    PreferenceManager.TTS_VOLUME_MAX,
                ),
                onValueChange = { v -> scope.launch { viewModel.setVolume(v) } },
                valueRange = PreferenceManager.TTS_VOLUME_MIN..PreferenceManager.TTS_VOLUME_MAX,
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.tts_speed_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = stringResource(
                    R.string.tts_speed_value,
                    String.format("%.1f", speed),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.tts_speed_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = speed.coerceIn(
                    PreferenceManager.TTS_SPEED_MIN,
                    PreferenceManager.TTS_SPEED_MAX,
                ),
                onValueChange = { v ->
                    val snapped = (kotlin.math.round(v * 10f) / 10f)
                        .coerceIn(PreferenceManager.TTS_SPEED_MIN, PreferenceManager.TTS_SPEED_MAX)
                    scope.launch { viewModel.setSpeed(snapped) }
                },
                valueRange = PreferenceManager.TTS_SPEED_MIN..PreferenceManager.TTS_SPEED_MAX,
                steps = ((PreferenceManager.TTS_SPEED_MAX - PreferenceManager.TTS_SPEED_MIN) * 10f).toInt() - 1,
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.settings_on_device_ai_section),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_models_summary),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "• LLM: ${RequiredAssets.LLM_MODEL_ID}\n" +
                    "• STT: ${DefaultVoiceModels.STT_MODEL_ID}\n" +
                    "• TTS: ${DefaultVoiceModels.TTS_MODEL_ID}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
