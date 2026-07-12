package com.kawaiipet.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavController
import com.kawaiipet.app.R
import com.kawaiipet.app.util.PreferenceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

private val kittenVoiceOptions = listOf(0, 1, 2, 3)

@HiltViewModel
class CustomizeViewModel @Inject constructor(
    private val prefs: PreferenceManager,
) : ViewModel() {

    val petName = prefs.petName
    val personalityPrompt = prefs.personalityPrompt
    val ttsSpeakerId = prefs.ttsSpeakerId
    val ttsVolume = prefs.ttsVolume

    suspend fun setPetName(value: String) = prefs.setPetName(value)
    suspend fun setPersonality(value: String) = prefs.setPersonalityPrompt(value)
    suspend fun setSpeakerId(value: Int) = prefs.setTtsSpeakerId(value)
    suspend fun setVolume(value: Float) = prefs.setTtsVolume(value)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeScreen(
    navController: NavController,
    viewModel: CustomizeViewModel = hiltViewModel(),
) {
    val petNameSaved by viewModel.petName.collectAsState(initial = "Mochi")
    val personalitySaved by viewModel.personalityPrompt.collectAsState(
        initial = PreferenceManager.DEFAULT_PERSONALITY,
    )
    var petNameEdit by remember { mutableStateOf<String?>(null) }
    var personalityEdit by remember { mutableStateOf<String?>(null) }
    val petName = petNameEdit ?: petNameSaved
    val personality = personalityEdit ?: personalitySaved
    val speakerId by viewModel.ttsSpeakerId.collectAsState(initial = 1)
    val volume by viewModel.ttsVolume.collectAsState(initial = 1f)
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.customize_title)) },
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

            Text(stringResource(R.string.pet_name_label), style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = petName,
                onValueChange = { v ->
                    petNameEdit = v
                    scope.launch { viewModel.setPetName(v) }
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.pet_name_hint)) },
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(stringResource(R.string.pet_personality_label), style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = personality,
                onValueChange = { v ->
                    personalityEdit = v
                    scope.launch { viewModel.setPersonality(v) }
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.pet_personality_hint)) },
                minLines = 4,
                maxLines = 8,
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(stringResource(R.string.kitten_voice_label), style = MaterialTheme.typography.labelLarge)
            Text(
                text = stringResource(R.string.kitten_voice_model_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            val voiceNames = stringArrayResource(R.array.kitten_voice_names)
            kittenVoiceOptions.forEach { sid ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = sid == speakerId,
                            onClick = { scope.launch { viewModel.setSpeakerId(sid) } },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = sid == speakerId,
                        onClick = null,
                    )
                    Text(
                        text = voiceNames.getOrNull(sid)
                            ?: stringResource(R.string.kitten_voice_option, sid + 1),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(stringResource(R.string.tts_volume_label), style = MaterialTheme.typography.labelLarge)
            Text(
                text = "${(volume * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = volume,
                onValueChange = { v -> scope.launch { viewModel.setVolume(v) } },
                valueRange = 0f..1f,
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
